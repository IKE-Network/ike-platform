package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.FeatureName;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The shared substrate of {@code ws:sibling-remove-draft} and
 * {@code -publish} (IKE-Network/ike-issues#600): target resolution,
 * the safety preflight, and the deletion itself.
 *
 * <p>The preflight names exactly what dies with a deleted tree, per
 * member repository: uncommitted changes, stashes, and unlanded branches
 * (squash-aware — see {@link SiblingInventory}). Bare member trees are
 * unverifiable, so they block too. {@code -Dforce=true} overrides those
 * content checks; it never overrides a live lease on another machine —
 * displacing a live holder stays a human decision on every surface.
 */
final class SiblingRemoval {

    private SiblingRemoval() {}

    /**
     * The resolved removal target and its assessed state.
     *
     * @param sibling the sibling to remove
     * @param states  per-member assessment, root first
     */
    record Target(SiblingInventory.Sibling sibling,
                  List<SiblingInventory.MemberState> states) {}

    /**
     * Resolves the removal target from the goal's parameters.
     *
     * @param primaryRoot the primary working set's root
     * @param baseName    the primary's directory name
     * @param feature     the {@code -Dfeature} value, or {@code null}
     * @param name        the {@code -Dname} value, or {@code null}
     * @return the target, fully assessed (origins fetched first)
     * @throws MojoException if neither or both parameters are given, the
     *                       directory is missing, it is not a sibling of
     *                       this primary, or the goal runs inside it
     */
    static Target resolve(File primaryRoot, String baseName,
                          String feature, String name) throws MojoException {
        boolean hasFeature = feature != null && !feature.isBlank();
        boolean hasName = name != null && !name.isBlank();
        if (hasFeature == hasName) {
            throw new MojoException("Name the sibling to remove with exactly "
                    + "one of -Dfeature=<name> (→ " + baseName
                    + FeatureName.SIBLING_SEPARATOR + "<name>) or "
                    + "-Dname=<directory>.");
        }
        String directoryName = hasName ? name.trim()
                : AbstractWorkspaceMojo.validateFeatureName(feature.trim())
                        .siblingDirectoryName(baseName);
        List<SiblingInventory.Sibling> siblings =
                SiblingInventory.discover(primaryRoot, baseName);
        SiblingInventory.Sibling sibling = siblings.stream()
                .filter(candidate -> candidate.name().equals(directoryName))
                .findFirst()
                .orElseThrow(() -> new MojoException("'" + directoryName
                        + "' is not a sibling of " + baseName + " (looked "
                        + "beside " + primaryRoot.getAbsolutePath() + "). "
                        + "`ws:sibling-list` shows what exists."));
        if (isSelfOrAncestor(sibling.root(), primaryRoot)) {
            throw new MojoException("Refusing: the goal is running inside "
                    + "the sibling it would delete. Run it from the primary: "
                    + "cd " + primaryRoot.getAbsolutePath());
        }
        return new Target(sibling,
                SiblingInventory.assess(sibling.root(), false));
    }

    /**
     * Assesses a sibling already in hand — the delete-on-finish path
     * (IKE-Network/ike-issues#992), where the finish runs inside the
     * sibling and needs no discovery or target resolution.
     *
     * @param siblingRoot the sibling's root directory
     * @param conformant  whether the local-origin chain is confirmed
     * @return the target, fully assessed (origins fetched first)
     */
    static Target assessKnown(File siblingRoot, boolean conformant) {
        String name = siblingRoot.getName();
        int separator = name.indexOf(FeatureName.SIBLING_SEPARATOR);
        String feature = separator >= 0
                ? name.substring(separator + FeatureName.SIBLING_SEPARATOR.length())
                : name;
        return new Target(
                new SiblingInventory.Sibling(name, siblingRoot, feature,
                        conformant),
                SiblingInventory.assess(siblingRoot, false));
    }

    /**
     * Renders the preflight into report rows and an overall verdict.
     *
     * @param target the resolved target
     * @return rows of {@code [mark, member, finding]}; every row marked
     *         {@code ✗} blocks removal without {@code -Dforce=true}
     */
    static List<String[]> preflightRows(Target target) {
        List<String[]> rows = new ArrayList<>();
        for (SiblingInventory.MemberState state : target.states()) {
            List<String> findings = new ArrayList<>();
            if (state.bare()) {
                findings.add("bare tree — no git state to verify; its "
                        + "content dies with the removal");
            } else {
                if (state.uncommitted() > 0) {
                    findings.add(state.uncommitted() + " uncommitted");
                }
                if (state.stashes() > 0) {
                    findings.add(state.stashes() + " stash(es) — stashes "
                            + "live only in this repository");
                }
                if (state.unlanded() != null && !state.unlanded().isEmpty()) {
                    findings.add("unlanded: "
                            + String.join(", ", state.unlanded()));
                }
                if (state.unlanded() == null) {
                    findings.add("landed-state not determinable "
                            + "(no origin, or fetch failed)");
                }
            }
            boolean ok = state.removable();
            rows.add(new String[]{ok ? "✓" : "✗", state.path(),
                    ok ? "clean, landed" : String.join("; ", findings)});
        }
        return rows;
    }

    /**
     * Reports whether every member passed the preflight.
     *
     * @param target the resolved target
     * @return {@code true} when nothing blocks removal
     */
    static boolean clean(Target target) {
        return target.states().stream()
                .allMatch(SiblingInventory.MemberState::removable);
    }

    /**
     * Deletes the sibling tree, then garbage-collects its lease record —
     * a record for a working set that no longer exists is the exact thing
     * the reconciliation daemon's GC removes (IKE-Network/ike-issues#1006),
     * done eagerly here. Removing a whole working set is the supported
     * operation; the sync layer propagates the deletion and staggered file
     * versioning is the net.
     *
     * @param target the resolved target
     * @return the deleted lease-record path, or {@code null} when none
     *         existed
     * @throws MojoException if the tree cannot be fully deleted
     */
    static Path delete(Target target) throws MojoException {
        Path root = target.sibling().root().toPath();
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new MojoException("Could not delete "
                    + root + ": " + e.getMessage() + ". The tree may be "
                    + "partially removed; re-run to finish.", e);
        }
        Path record = root.getParent()
                .resolve("leases")
                .resolve(target.sibling().name() + ".lease");
        try {
            return Files.deleteIfExists(record) ? record : null;
        } catch (IOException e) {
            // The record is now garbage either way; the daemon's GC or the
            // operator can sweep it. Removal itself has succeeded.
            return null;
        }
    }

    /**
     * The report preamble both goals share.
     *
     * @param report the report under construction
     * @param target the resolved target
     */
    static void describeTarget(GoalReportBuilder report, Target target) {
        SiblingInventory.Sibling sibling = target.sibling();
        report.paragraph("**Sibling:** `" + sibling.name() + "`")
                .paragraph("**Feature:** `" + sibling.feature() + "`")
                .paragraph("**Location:** `"
                        + sibling.root().getAbsolutePath() + "`")
                .paragraph("**Origin chain:** "
                        + (sibling.conformant()
                                ? "local parent ✓ (ike-issues#992)"
                                : "legacy remote-remote — landed-state is "
                                        + "measured against that remote"));
    }

    private static boolean isSelfOrAncestor(File sibling, File primaryRoot) {
        try {
            Path siblingPath = sibling.getCanonicalFile().toPath();
            Path cwd = Path.of("").toAbsolutePath();
            Path primary = primaryRoot.getCanonicalFile().toPath();
            return cwd.startsWith(siblingPath) || primary.startsWith(siblingPath);
        } catch (IOException e) {
            return false;
        }
    }
}
