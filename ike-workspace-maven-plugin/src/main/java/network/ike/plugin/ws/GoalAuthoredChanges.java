package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Commit only the paths a goal itself authored, safely — the reusable
 * primitive behind the {@link AuthoredCommit#IN_ISOLATION} contract
 * (IKE-Network/ike-issues#780). Generalized from {@link PostMutationSync}'s
 * {@code workspace.yaml} self-commit (IKE-Network/ike-issues#774).
 *
 * <p>Usage: {@link #snapshot snapshot} the paths the goal is about to write,
 * <em>before</em> it writes them; let the goal run; then
 * {@link #commitAuthored commitAuthored}. Only paths that were unmodified
 * before the goal ran <em>and</em> were modified by it are committed — so a
 * caller's concurrent WIP, a {@code -DstagedOnly} subset, work syncing in over
 * Syncthing, or a not-yet-bootstrapped sibling's untracked state is never
 * swept in. The helper never runs {@code git add -A}; it commits exact paths
 * via {@link VcsOperations#commitPaths}.
 */
public final class GoalAuthoredChanges {

    private final File root;
    private final Log log;
    private final List<String> unmodifiedBefore;

    private GoalAuthoredChanges(File root, Log log,
                               List<String> unmodifiedBefore) {
        this.root = root;
        this.log = log;
        this.unmodifiedBefore = unmodifiedBefore;
    }

    /**
     * Snapshot, before the goal runs, which of {@code paths} are currently
     * unmodified in the working tree. Only those become eligible to be
     * committed in isolation later — a path that already carries a user or
     * caller edit is excluded, so a later commit can never sweep that edit in.
     *
     * @param root  the repository root directory
     * @param log   Maven logger
     * @param paths the paths the goal intends to author, relative to
     *              {@code root}
     * @return a snapshot to {@link #commitAuthored} after the goal has run
     */
    public static GoalAuthoredChanges snapshot(File root, Log log,
                                               String... paths) {
        List<String> unmodified = new ArrayList<>();
        for (String path : paths) {
            if (VcsOperations.isPathClean(root, path)) {
                unmodified.add(path);
            }
        }
        return new GoalAuthoredChanges(root, log, unmodified);
    }

    /**
     * Commit, in isolation, exactly the snapshotted paths that were unmodified
     * before the goal ran and have since been modified by it. A no-op
     * (returns {@code false}) when no such path exists. A commit failure is
     * logged at WARN and treated as a no-op rather than aborting the caller.
     *
     * @param message the commit message — a {@code <type>: <summary>} line
     *                plus a {@code Refs:} trailer; must not be {@code null} or
     *                blank
     * @return {@code true} if a commit was made
     */
    public boolean commitAuthored(String message) {
        List<String> authored = new ArrayList<>();
        for (String path : unmodifiedBefore) {
            if (!VcsOperations.isPathClean(root, path)) {
                authored.add(path);
            }
        }
        if (authored.isEmpty()) {
            return false;
        }
        try {
            VcsOperations.commitPaths(root, log, message,
                    authored.toArray(new String[0]));
            return true;
        } catch (MojoException e) {
            log.warn("  could not commit goal-authored change(s) " + authored
                    + " — " + e.getMessage());
            return false;
        }
    }
}
