package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Branch-qualification cascade across a subproject's whole module tree
 * (IKE-Network/ike-issues#1051).
 *
 * <p>The two #574 qualification arms — {@code ws:add}'s add-time
 * qualification and the scaffold-publish self-heal — rewrote only the
 * subproject <em>root</em> POM's own {@code <version>}. A multi-module
 * subproject was left internally split: every child's
 * {@code <parent><version>} still named the unqualified version, so the
 * children either built against a stale external parent resolved from a
 * repository (inheriting the unqualified version) or failed parent
 * resolution outright. Observed 2026-08-18 adding ikm-reasoner
 * (21 child modules, two aggregator levels) to the incremental-reasoner
 * sibling.
 *
 * <p>This cascade applies one version move {@code old → new} to the whole
 * tree, precisely:
 * <ul>
 *   <li>every tree POM whose own literal {@code <version>} is {@code old}
 *       (the root's own version; a child that redundantly declares its
 *       own version) is rewritten to {@code new};</li>
 *   <li>every tree POM whose {@code <parent>} names an <em>in-tree</em>
 *       POM by full groupId:artifactId and whose parent version is
 *       {@code old} is rewritten to {@code new} via the OpenRewrite-LST
 *       editor ({@link PomModel#updateParentVersion}) — never string
 *       replacement.</li>
 * </ul>
 *
 * <p>What it never touches: an external {@code <parent>} (GA not produced
 * by the tree — e.g. ikm-reasoner's {@code java-parent}), and dependency
 * versions that merely coincide with {@code old} literally. GA matching,
 * not literal string matching, is the precision guarantee (#241 set the
 * precedent for full-GA matching).
 *
 * <p>De-qualification already cascades on the finish side
 * ({@code FeatureFinishSupport.setAllVersions} +
 * {@code stripAllBranchQualifiedVersions}), so the round trip is
 * symmetric once both #574 arms route through here.
 */
public final class QualificationCascade {

    private QualificationCascade() {}

    /**
     * Apply the version move {@code oldVersion → newVersion} across the
     * member's whole module tree, as described in the class javadoc.
     *
     * <p>Failures on individual POMs (unreadable, unparseable) are logged
     * and skipped rather than failing the move — matching the non-fatal
     * posture of both #574 arms, whose callers treat qualification as
     * self-healable on the next scaffold-publish.
     *
     * @param memberDir  the subproject root directory
     * @param oldVersion the version currently in the tree
     * @param newVersion the version the tree should carry
     * @param log        Maven logger for per-file debug and warnings
     * @return the number of POM files rewritten
     * @throws IOException if the root POM cannot be rewritten
     */
    public static int apply(Path memberDir, String oldVersion,
                            String newVersion, Log log) throws IOException {
        if (oldVersion == null || newVersion == null
                || oldVersion.equals(newVersion)) {
            return 0;
        }
        List<PomEntry> tree = readTree(memberDir, log);
        Set<String> treeGAs = treeCoordinates(tree);

        int changed = 0;
        for (PomEntry entry : tree) {
            String content = entry.model().content();
            String updated = content;

            Parent parent = entry.model().parent();
            if (parent != null && oldVersion.equals(parent.getVersion())
                    && treeGAs.contains(
                            ga(parent.getGroupId(), parent.getArtifactId()))) {
                updated = PomModel.updateParentVersion(updated,
                        parent.getGroupId(), parent.getArtifactId(),
                        newVersion);
            }
            if (oldVersion.equals(entry.model().model().getVersion())) {
                updated = spliceOwnVersion(updated, oldVersion, newVersion);
            }
            if (!updated.equals(content)) {
                Files.writeString(entry.file().toPath(), updated,
                        StandardCharsets.UTF_8);
                changed++;
                log.debug("    qualification cascade: "
                        + memberDir.relativize(entry.file().toPath())
                        + " " + oldVersion + " → " + newVersion);
            }
        }
        return changed;
    }

    /**
     * Whether the member's tree still references {@code baseVersion} —
     * an in-tree {@code <parent>} at that version, or a POM's own literal
     * {@code <version>} at it. This is the reconciler's detection arm for
     * trees whose root is already qualified but whose children were left
     * behind by a pre-#1051 qualification (the state the reconciler's
     * root-only read could not see).
     *
     * @param memberDir   the subproject root directory
     * @param baseVersion the unqualified version to look for
     * @param log         Maven logger for warnings on unreadable POMs
     * @return {@code true} when at least one tree POM still carries
     *         {@code baseVersion} as described
     */
    public static boolean hasStaleTree(Path memberDir, String baseVersion,
                                       Log log) {
        if (baseVersion == null) {
            return false;
        }
        List<PomEntry> tree = readTree(memberDir, log);
        Set<String> treeGAs = treeCoordinates(tree);
        for (PomEntry entry : tree) {
            Parent parent = entry.model().parent();
            if (parent != null && baseVersion.equals(parent.getVersion())
                    && treeGAs.contains(
                            ga(parent.getGroupId(), parent.getArtifactId()))) {
                return true;
            }
            if (baseVersion.equals(entry.model().model().getVersion())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrite a POM's own {@code <version>} in {@code content}, searching
     * past the {@code </parent>} block so a parent version that
     * coincidentally equals {@code oldVersion} is never mistaken for the
     * project's own (the project {@code <version>} precedes
     * {@code <dependencies>}, so the first match after {@code </parent>}
     * is the project's own). The single own-version splice of the
     * cascade — successor of the retired root-only
     * {@code FeatureVersionReconciler.rewriteOwnVersion} (#1051).
     *
     * @param content    the POM text
     * @param oldVersion the current project version
     * @param newVersion the replacement version
     * @return the updated text, or {@code content} unchanged when the old
     *         version is absent outside the parent block
     */
    public static String spliceOwnVersion(String content, String oldVersion,
                                          String newVersion) {
        int searchFrom = 0;
        int parentEnd = content.indexOf("</parent>");
        if (parentEnd >= 0) {
            searchFrom = parentEnd + "</parent>".length();
        }
        String needle = "<version>" + oldVersion + "</version>";
        int idx = content.indexOf(needle, searchFrom);
        if (idx < 0) {
            return content;
        }
        return content.substring(0, idx)
                + "<version>" + newVersion + "</version>"
                + content.substring(idx + needle.length());
    }

    // ── Tree reading ─────────────────────────────────────────────

    /** One tree POM: its file and parsed model. */
    private record PomEntry(File file, PomModel model) {}

    /**
     * Parse every project POM under {@code memberDir}. Unreadable or
     * unparseable POMs are logged and skipped — they can neither be
     * matched nor rewritten.
     */
    private static List<PomEntry> readTree(Path memberDir, Log log) {
        List<File> poms;
        try {
            poms = ReleaseSupport.findPomFiles(memberDir.toFile());
        } catch (MojoException e) {
            log.warn("    qualification cascade: cannot scan "
                    + memberDir + " — " + e.getMessage());
            return List.of();
        }
        List<PomEntry> tree = new ArrayList<>();
        for (File pom : poms) {
            try {
                tree.add(new PomEntry(pom, PomModel.parse(pom.toPath())));
            } catch (IOException e) {
                log.warn("    qualification cascade: skipping " + pom
                        + " — " + e.getMessage());
            }
        }
        return tree;
    }

    /**
     * The full groupId:artifactId set produced by the tree, groupId
     * falling back to the declared parent's when inherited — the set an
     * in-tree {@code <parent>} reference is matched against.
     */
    private static Set<String> treeCoordinates(List<PomEntry> tree) {
        Set<String> gas = new LinkedHashSet<>();
        for (PomEntry entry : tree) {
            String groupId = entry.model().groupId();
            String artifactId = entry.model().model().getArtifactId();
            if (groupId != null && artifactId != null) {
                gas.add(ga(groupId, artifactId));
            }
        }
        return gas;
    }

    private static String ga(String groupId, String artifactId) {
        return groupId + ":" + artifactId;
    }
}
