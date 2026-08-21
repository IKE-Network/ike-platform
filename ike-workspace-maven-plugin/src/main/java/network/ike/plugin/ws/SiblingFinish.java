package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The finish half of the local-origin sibling model
 * (IKE-Network/ike-issues#992): a sibling finishes <em>locally to its
 * local origin</em> — the working set it was derived from — and GitHub
 * enters only when that parent later pushes.
 *
 * <p>Under the model, {@code origin} in every sibling member is the
 * parent's member path, so the finish's old "push {@code main} to
 * origin" phase cannot apply: git refuses to update a non-bare
 * repository's checked-out branch ({@code denyCurrentBranch}), and that
 * refusal is the right invariant — a sibling must not mutate the
 * parent's mainline from outside. Instead the mutation executes
 * <em>in the parent</em>:
 *
 * <ol>
 *   <li>the sibling squash- or merge-lands the feature on its own local
 *       target branch, exactly as before (a purely local act);</li>
 *   <li>each parent member fetches that target from its sibling member
 *       and fast-forwards — {@code --ff-only} when the branch is checked
 *       out, a refusing ref update when it is not. The sibling's target
 *       is the parent's target plus the landing commit, so this is a
 *       fast-forward by construction;</li>
 *   <li>nothing is pushed anywhere. Externalization is the parent's own
 *       explicit {@code ws:push}.</li>
 * </ol>
 *
 * <p>A member whose parent side is not a fast-forward (the parent moved
 * on independently) is reported, never forced and never merged: the
 * operator reconciles in the parent. Feature-branch deletion in the
 * sibling is gated on the parent having absorbed the landing — the
 * local-origin analogue of the {@code #858} no-stranded-squash contract,
 * with the parent playing the role the remote played.
 *
 * @see FeatureStartSiblingPublishMojo for the creation half
 */
final class SiblingFinish {

    /** Where the creation half records the derived-from parent (#992). */
    static final String PARENT_RECORD = ".ike/parent-workspace";

    private SiblingFinish() {}

    /**
     * The parent working set this sibling was derived from, when the
     * sibling really does chain to it locally.
     *
     * <p>Resolution order: the {@link #PARENT_RECORD} written at
     * creation, then the {@code <parent>꞉<feature>} naming convention
     * (so siblings created before the record existed still resolve).
     * Either way the result is confirmed against the sibling root's
     * {@code origin}: only when origin resolves to the parent's path is
     * this a local-origin sibling. A sibling still pointing at GitHub —
     * every sibling created before #992 — resolves to empty here and
     * keeps the remote-origin finish path.
     *
     * @param siblingRoot the workspace root the finish is running in
     * @return the parent workspace root, or empty when this is not a
     *         local-origin sibling
     */
    static Optional<File> localParent(File siblingRoot) {
        Optional<File> candidate = recordedParent(siblingRoot)
                .or(() -> FeatureFinishSupport.deriveParentWorkspace(siblingRoot));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        File parent = candidate.get();
        if (!new File(parent, ".git").exists()) {
            return Optional.empty();
        }
        return originResolvesTo(siblingRoot, parent)
                ? Optional.of(parent) : Optional.empty();
    }

    /**
     * The parent recorded at creation in {@link #PARENT_RECORD}, resolved
     * against the sibling root. The record holds a relative path, so it
     * stays valid on every machine the sync layer carries it to.
     */
    private static Optional<File> recordedParent(File siblingRoot) {
        Path record = siblingRoot.toPath().resolve(PARENT_RECORD);
        if (!Files.exists(record)) {
            return Optional.empty();
        }
        try {
            String value = Files.readString(record, StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(siblingRoot.toPath().resolve(value)
                    .normalize().toFile());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Whether {@code dir}'s {@code origin} is {@code expected}'s path. */
    private static boolean originResolvesTo(File dir, File expected) {
        if (!ReleaseSupport.hasRemote(dir, "origin")) {
            return false;
        }
        try {
            String url = ReleaseSupport.execCapture(dir,
                    "git", "remote", "get-url", "origin").trim();
            if (url.isEmpty()) {
                return false;
            }
            File origin = url.startsWith("file://")
                    ? new File(java.net.URI.create(url))
                    : new File(url);
            return origin.getAbsoluteFile().toPath().normalize()
                    .equals(expected.getAbsoluteFile().toPath().normalize());
        } catch (MojoException | IllegalArgumentException e) {
            return false;
        }
    }

    /** One member's parent-side outcome. */
    record Absorbed(String member, boolean ok, String detail) {}

    /**
     * Fast-forward every parent member from its sibling member, landing
     * the finished target branch in the parent.
     *
     * <p>Members are addressed by name against both roots, with the
     * workspace root itself included last (mirroring the push phase's
     * member set). A member missing on either side is reported as
     * absorbed-with-nothing-to-do rather than failing the finish: the
     * sibling may legitimately carry members the parent never
     * materialized.
     *
     * @param siblingRoot  the sibling workspace root the finish ran in
     * @param parentRoot   the parent workspace root it chains to
     * @param members      member names finished this run
     * @param targetBranch the branch that was finished into (e.g. main)
     * @param log          Maven logger
     * @return one result per member (root included), in order
     */
    static List<Absorbed> absorbIntoParent(File siblingRoot, File parentRoot,
                                           List<String> members,
                                           String targetBranch, Log log) {
        Map<String, File> siblingDirs = new LinkedHashMap<>();
        for (String name : members) {
            siblingDirs.put(name, new File(siblingRoot, name));
        }
        siblingDirs.put(RefreshMainSupport.ROOT_LABEL, siblingRoot);

        List<Absorbed> results = new ArrayList<>();
        log.info("");
        log.info("  " + Ansi.cyan("→ ") + "Landing " + targetBranch
                + " in the parent working set '" + parentRoot.getName()
                + "' (fast-forward only)...");
        for (Map.Entry<String, File> entry : siblingDirs.entrySet()) {
            String member = entry.getKey();
            File siblingDir = entry.getValue();
            File parentDir = RefreshMainSupport.ROOT_LABEL.equals(member)
                    ? parentRoot : new File(parentRoot, member);
            results.add(absorbMember(member, siblingDir, parentDir,
                    targetBranch, log));
        }
        return results;
    }

    /**
     * Fast-forward one parent member from its sibling counterpart:
     * fetch the sibling's target into {@code FETCH_HEAD}, verify the
     * parent's target is an ancestor of it, then fast-forward — with
     * {@code merge --ff-only} when the parent has the branch checked
     * out, or a refusing {@code fetch <path> <target>:<target>} ref
     * update when it does not (never touching the parent's working
     * tree).
     */
    private static Absorbed absorbMember(String member, File siblingDir,
                                         File parentDir, String targetBranch,
                                         Log log) {
        if (!new File(siblingDir, ".git").exists()
                || !new File(parentDir, ".git").exists()) {
            log.debug("    " + member + " — no counterpart pair; skipped");
            return new Absorbed(member, true, "no counterpart");
        }
        String source = siblingDir.getAbsolutePath();
        try {
            if (!VcsOperations.localBranchExists(siblingDir, targetBranch)) {
                return new Absorbed(member, true,
                        "no " + targetBranch + " in the sibling");
            }
            String siblingSha = VcsOperations.branchSha(siblingDir, targetBranch);

            if (!VcsOperations.localBranchExists(parentDir, targetBranch)) {
                ReleaseSupport.exec(parentDir, log, "git", "fetch", source,
                        targetBranch + ":" + targetBranch);
                log.info("    " + Ansi.green("✓ ") + member + " — "
                        + targetBranch + " created in the parent (" + siblingSha + ")");
                return new Absorbed(member, true, siblingSha);
            }

            String parentSha = VcsOperations.branchSha(parentDir, targetBranch);
            if (parentSha.equals(siblingSha)) {
                log.info("    " + member + " — parent already at " + siblingSha);
                return new Absorbed(member, true, siblingSha);
            }

            // Fetch the sibling's target so ancestry can be tested
            // against a local ref, then fast-forward if and only if the
            // parent has not moved on independently.
            ReleaseSupport.exec(parentDir, log, "git", "fetch", source, targetBranch);
            if (!VcsOperations.isAncestor(parentDir, targetBranch, siblingSha)) {
                log.warn("    " + Ansi.yellow("⚠ ") + member + " — parent "
                        + targetBranch + " is not an ancestor of the sibling's;"
                        + " left alone");
                return new Absorbed(member, false,
                        "parent " + targetBranch + " diverged (" + parentSha
                        + " vs " + siblingSha + ") — reconcile in the parent");
            }

            if (targetBranch.equals(VcsOperations.currentBranch(parentDir))) {
                VcsOperations.mergeFfOnly(parentDir, log, siblingSha);
            } else {
                // Refuses on non-fast-forward rather than discarding work.
                ReleaseSupport.exec(parentDir, log, "git", "fetch", source,
                        targetBranch + ":" + targetBranch);
            }
            int landed = VcsOperations.commitLog(
                    parentDir, parentSha, siblingSha).size();
            log.info("    " + Ansi.green("✓ ") + member + " — fast-forwarded ("
                    + landed + " commit" + (landed == 1 ? "" : "s") + ")");
            return new Absorbed(member, true, siblingSha);
        } catch (MojoException e) {
            log.warn("    " + Ansi.red("✗ ") + member
                    + " — could not land in the parent: " + e.getMessage());
            return new Absorbed(member, false, e.getMessage());
        }
    }

    /**
     * The failure message when the parent could not absorb every member:
     * names each member and its reason, states that no feature branch was
     * deleted, and gives the recovery. The local-origin analogue of
     * {@code FeatureFinishSupport.pushPhaseFailureMessage} — the parent
     * plays the role the remote played in {@code #858}.
     *
     * @param failures     members the parent could not fast-forward
     * @param targetBranch the branch being landed
     * @param parentRoot   the parent working set
     * @return the formatted failure message
     */
    static String absorbFailureMessage(List<Absorbed> failures,
                                       String targetBranch, File parentRoot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Feature-finish stopped: the parent working set '")
                .append(parentRoot.getName()).append("' could not absorb ")
                .append(failures.size()).append(" member(s) — the landing "
                        + "exists on the sibling's ").append(targetBranch)
                .append(" only:\n");
        for (Absorbed f : failures) {
            sb.append("  ").append(f.member()).append(" — ")
                    .append(f.detail()).append("\n");
        }
        sb.append("\nNo feature branch was deleted. Reconcile the parent, "
                + "then re-run the finish:\n")
                .append("  cd ").append(parentRoot.getAbsolutePath())
                .append("\n  mvn ").append(WsGoal.PULL.qualified())
                .append("   # reconcile ").append(targetBranch)
                .append("\n\nThe sibling's work is intact — nothing was forced "
                        + "or discarded.");
        return sb.toString();
    }
}
