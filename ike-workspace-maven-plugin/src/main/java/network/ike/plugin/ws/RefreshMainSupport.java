package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Refresh local main from origin/main as a workspace invariant.
 *
 * <p>Goals that read or write local main (update-feature, feature-finish,
 * feature-start, release goals) call this helper first so the operation
 * runs against current main rather than whatever stale state happens to
 * be on the local machine. Particularly relevant in the workspace's
 * Syncthing + independent-{@code .git} architecture, where each machine
 * evolves its local {@code main} ref independently between push/pull
 * cycles.
 *
 * <p>For each subproject, the helper:
 * <ol>
 *   <li>Fetches origin to update remote-tracking refs.</li>
 *   <li>Compares local main to {@code origin/main}:
 *     <ul>
 *       <li><b>Up to date</b> &mdash; no-op.</li>
 *       <li><b>Behind only</b> &mdash; fast-forward local main.</li>
 *       <li><b>Ahead only</b> &mdash; leave local main alone (unpushed
 *           commits remain intact); the caller's downstream merge will
 *           include them.</li>
 *       <li><b>Diverged</b> &mdash; auto-resolve by merging
 *           {@code origin/main} into local main with {@code --no-ff}.
 *           The Syncthing architecture means working-tree content is
 *           usually pre-aligned, so the merge is usually conflict-free.
 *           If file conflicts <i>are</i> predicted, return a
 *           {@link Conflicts} outcome without touching the working
 *           tree &mdash; the caller decides whether to hard-error.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Auto-resolve merge commits are local-only. They are never pushed
 * to the remote by this helper; the user publishes via
 * {@code ws:push} / {@code ws:sync}.
 *
 * <p>The helper restores the caller's checked-out branch when it
 * temporarily switches to main to perform an auto-resolve merge.
 *
 * <p>See ike-issues#284.
 */
final class RefreshMainSupport {

    /** Default git remote name used for the {@code origin/main} comparison. */
    static final String DEFAULT_REMOTE = "origin";

    private RefreshMainSupport() {}

    // ── Outcome model ───────────────────────────────────────────

    /**
     * Outcome of refreshing local main in a single subproject.
     */
    sealed interface Outcome
            permits Skipped, UpToDate, FastForwarded, CreatedFromRemote,
                    AheadOnly, AutoResolved, Conflicts {

        /** Subproject name this outcome applies to. */
        String component();
    }

    /** Subproject was skipped (not initialized as a git repo). */
    record Skipped(String component, String reason) implements Outcome {}

    /** Local main equals {@code origin/main}. No work performed. */
    record UpToDate(String component) implements Outcome {}

    /** Local main was fast-forwarded {@code commits} commit(s) to {@code origin/main}. */
    record FastForwarded(String component, int commits) implements Outcome {}

    /** Local main did not exist; created by tracking {@code origin/main}. */
    record CreatedFromRemote(String component) implements Outcome {}

    /**
     * Local main is purely ahead of {@code origin/main}: it contains
     * {@code unpushed} commit(s) that the remote does not. The local
     * ref is intentionally left alone &mdash; the caller's merge will
     * use it, and the user publishes via {@code ws:push}.
     */
    record AheadOnly(String component, int unpushed) implements Outcome {}

    /**
     * Local main and {@code origin/main} had diverged; resolved by
     * merging {@code origin/main} into local main with no file
     * conflicts. {@code localCommits} is the count of unpushed local
     * commits preserved in the merge; {@code remoteCommits} is the
     * count brought in from {@code origin/main}.
     */
    record AutoResolved(String component,
                        int localCommits,
                        int remoteCommits) implements Outcome {}

    /**
     * Local main and {@code origin/main} had diverged AND merging
     * {@code origin/main} into local main would produce file conflicts.
     * The working tree was not touched &mdash; the caller decides
     * whether to hard-error or report and continue.
     */
    record Conflicts(String component, List<String> files) implements Outcome {}

    // ── Operations ──────────────────────────────────────────────

    /**
     * Refresh local main in a single subproject.
     *
     * @param subprojectDir the subproject root directory
     * @param component     subproject name (used in outcome labelling)
     * @param mainBranch    the conceptual main branch (e.g. {@code "main"})
     * @param remote        remote name (e.g. {@code "origin"})
     * @param log           Maven logger
     * @return outcome describing what was done
     * @throws MojoException if a git operation fails for an unexpected reason
     */
    static Outcome refresh(File subprojectDir, String component,
                           String mainBranch, String remote, Log log)
            throws MojoException {
        File gitDir = new File(subprojectDir, ".git");
        if (!gitDir.exists()) {
            return new Skipped(component, "not initialized");
        }
        if (!network.ike.plugin.ReleaseSupport.hasRemote(subprojectDir, remote)) {
            return new Skipped(component, "no '" + remote + "' remote");
        }

        VcsOperations.fetch(subprojectDir, log);

        String remoteRef = remote + "/" + mainBranch;

        // Fresh-clone case: local main does not exist yet.
        if (!VcsOperations.localBranchExists(subprojectDir, mainBranch)) {
            VcsOperations.fetchRef(subprojectDir, log, remote, mainBranch);
            return new CreatedFromRemote(component);
        }

        boolean localIsAncestor = VcsOperations.isAncestor(
                subprojectDir, mainBranch, remoteRef);
        boolean remoteIsAncestor = VcsOperations.isAncestor(
                subprojectDir, remoteRef, mainBranch);

        if (localIsAncestor && remoteIsAncestor) {
            return new UpToDate(component);
        }
        if (localIsAncestor) {
            int behind = VcsOperations.commitLog(
                    subprojectDir, mainBranch, remoteRef).size();
            fastForwardLocalMain(subprojectDir, log, mainBranch, remote, remoteRef);
            return new FastForwarded(component, behind);
        }
        if (remoteIsAncestor) {
            int ahead = VcsOperations.commitLog(
                    subprojectDir, remoteRef, mainBranch).size();
            return new AheadOnly(component, ahead);
        }

        // Diverged.
        List<String> predicted = VcsOperations.predictConflicts(
                subprojectDir, mainBranch, remoteRef);
        if (!predicted.isEmpty()) {
            return new Conflicts(component, predicted);
        }

        int localCommits = VcsOperations.commitLog(
                subprojectDir, remoteRef, mainBranch).size();
        int remoteCommits = VcsOperations.commitLog(
                subprojectDir, mainBranch, remoteRef).size();
        autoResolveDiverged(subprojectDir, log, mainBranch, remoteRef);
        return new AutoResolved(component, localCommits, remoteCommits);
    }

    /**
     * Refresh local main across a list of subprojects, in the given
     * order. Returns one outcome per subproject. The caller decides
     * how to surface conflict outcomes &mdash; typically by raising
     * a {@link MojoException} that stops the orchestrating goal with
     * a clear instruction.
     *
     * @param workspaceRoot workspace root directory
     * @param components    subproject names to refresh, in order
     * @param mainBranch    the conceptual main branch (e.g. {@code "main"})
     * @param remote        remote name (e.g. {@code "origin"})
     * @param log           Maven logger
     * @return outcomes in the same order as {@code components}
     * @throws MojoException if a git operation fails for an unexpected reason
     */
    static List<Outcome> refreshAll(File workspaceRoot,
                                    List<String> components,
                                    String mainBranch,
                                    String remote,
                                    Log log) throws MojoException {
        List<Outcome> results = new ArrayList<>(components.size());
        for (String name : components) {
            File dir = new File(workspaceRoot, name);
            results.add(refresh(dir, name, mainBranch, remote, log));
        }
        return results;
    }

    /** Filter outcomes to just the {@link Conflicts} entries. */
    static List<Conflicts> conflictsIn(List<Outcome> outcomes) {
        List<Conflicts> result = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o instanceof Conflicts c) result.add(c);
        }
        return result;
    }

    /**
     * Orchestrate the standard refresh-or-stop flow: log a header,
     * call {@link #refreshAll}, log each per-subproject outcome,
     * and throw a {@link MojoException} if any subproject produced a
     * {@link Conflicts} outcome (the genuine "two machines edited the
     * same file on main without push/pull" case). Returns the outcome
     * list when the refresh is conflict-free, so callers can include
     * it in their reports if desired.
     *
     * @param workspaceRoot workspace root directory
     * @param components    subproject names to refresh, in order
     * @param mainBranch    the conceptual main branch (e.g. {@code "main"})
     * @param log           Maven logger
     * @return outcomes (caller may inspect for non-conflict variants)
     * @throws MojoException if any conflicts arise, with file-level detail
     *                       and resolution instructions
     */
    static List<Outcome> refreshOrThrow(File workspaceRoot,
                                        List<String> components,
                                        String mainBranch,
                                        Log log) throws MojoException {
        log.info("  " + Ansi.cyan("→ ") + "Refreshing local " + mainBranch
                + " from " + DEFAULT_REMOTE + "/" + mainBranch + "...");
        List<Outcome> outcomes = refreshAll(workspaceRoot, components,
                mainBranch, DEFAULT_REMOTE, log);
        for (Outcome o : outcomes) {
            log.info("    " + describe(o));
        }
        List<Conflicts> conflicts = conflictsIn(outcomes);
        if (!conflicts.isEmpty()) {
            var sb = new StringBuilder();
            sb.append("Cannot proceed — refreshing local ").append(mainBranch)
              .append(" from ").append(DEFAULT_REMOTE).append("/")
              .append(mainBranch).append(" would conflict in:\n");
            for (Conflicts c : conflicts) {
                sb.append("  ").append(c.component()).append(":\n");
                for (String f : c.files()) {
                    sb.append("    • ").append(f).append("\n");
                }
            }
            sb.append("Resolve the divergence on ").append(mainBranch)
              .append(" first (e.g., open the affected subproject and ")
              .append("merge ").append(DEFAULT_REMOTE).append("/")
              .append(mainBranch).append(" into ").append(mainBranch)
              .append(" by hand), then re-run.");
            throw new MojoException(sb.toString());
        }
        log.info("");
        return outcomes;
    }

    /**
     * Format a one-line user-facing summary of an outcome for log output.
     */
    static String describe(Outcome outcome) {
        return switch (outcome) {
            case Skipped(var c, var r) ->
                    c + " — skipped (" + r + ")";
            case UpToDate(var c) ->
                    c + " — main up to date";
            case FastForwarded(var c, var n) ->
                    c + " — main fast-forwarded (" + n + " commit"
                            + (n == 1 ? "" : "s") + ")";
            case CreatedFromRemote(var c) ->
                    c + " — main created from origin/main";
            case AheadOnly(var c, var n) ->
                    c + " — local main has " + n + " unpushed commit"
                            + (n == 1 ? "" : "s") + "; left as-is";
            case AutoResolved(var c, var local, var remote) ->
                    c + " — auto-resolved divergent main (kept " + local
                            + " local, merged " + remote + " from origin)";
            case Conflicts(var c, var files) ->
                    c + " — divergent main, " + files.size() + " file conflict"
                            + (files.size() == 1 ? "" : "s");
        };
    }

    // ── Internal git mechanics ──────────────────────────────────

    /**
     * Fast-forward local main to {@code origin/main}. Handles both the
     * case where the caller is on main (uses {@code git merge --ff-only})
     * and the case where they are not (uses
     * {@code git fetch origin main:main}, which fails on non-FF rather
     * than discarding work).
     */
    private static void fastForwardLocalMain(File subprojectDir, Log log,
                                              String mainBranch, String remote,
                                              String remoteRef)
            throws MojoException {
        String currentBranch = VcsOperations.currentBranch(subprojectDir);
        if (currentBranch.equals(mainBranch)) {
            VcsOperations.mergeFfOnly(subprojectDir, log, remoteRef);
        } else {
            VcsOperations.fetchRef(subprojectDir, log, remote, mainBranch);
        }
    }

    /**
     * Merge {@code origin/main} into local main with a merge commit.
     * If the caller is not already on main, switch to main, merge, and
     * switch back. If the merge fails, abort it before propagating so
     * the working tree is not left with conflict markers from a
     * mispredicted merge.
     */
    private static void autoResolveDiverged(File subprojectDir, Log log,
                                             String mainBranch, String remoteRef)
            throws MojoException {
        String originalBranch = VcsOperations.currentBranch(subprojectDir);
        boolean switched = !originalBranch.equals(mainBranch);
        if (switched) {
            VcsOperations.checkout(subprojectDir, log, mainBranch);
        }
        try {
            VcsOperations.mergeNoFf(subprojectDir, log, remoteRef,
                    "refresh: merge " + remoteRef + " into " + mainBranch);
        } catch (MojoException e) {
            VcsOperations.mergeAbortQuiet(subprojectDir, log);
            throw e;
        } finally {
            if (switched) {
                VcsOperations.checkout(subprojectDir, log, originalBranch);
            }
        }
    }
}
