package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;

/**
 * Auto-stash a working tree's uncommitted changes before a VCS-state sync
 * (pull / sync / refresh-main) and restore them after — so the SYNC goals run
 * against a clean tree instead of refusing on in-flight work (#780). Reuses the
 * per-user pushable-stash machinery {@link ParkSupport} extracted from
 * {@code ws:switch}: WIP is stashed to {@code refs/ws-stash/<slug>/<branch>} on
 * origin, then re-applied — so even a hard failure mid-sync never loses work.
 *
 * <p>Because the branch does not change across a pull/refresh, the stash is
 * keyed on the current branch and restored onto that same (now-advanced)
 * branch — a 3-way re-apply, the intended "keep my edits on top of what I
 * pulled" outcome. This is what makes a Syncthing-bridged workspace's normal
 * state (uncommitted edits in flight) compatible with a routine pull.
 *
 * <p>The slug is resolved lazily — only when a directory is actually dirty —
 * so a clean sync never requires a configured {@code git user.email}.
 */
final class AutoStashGuard {

    private AutoStashGuard() {}

    /**
     * Stash {@code dir}'s uncommitted changes (to the per-user ref for its
     * current branch) when it is dirty, leaving the tree clean for the sync.
     *
     * @param dir the repository directory
     * @param log Maven logger
     * @return {@code true} if a stash was left — pass it to
     *         {@link #restoreIfStashed} after the sync
     * @throws MojoException if the tree is dirty but {@code git user.email} is
     *                       unset (the stash ref can't be keyed), or a stash
     *                       step fails
     */
    static boolean stashIfDirty(File dir, Log log) throws MojoException {
        if (VcsOperations.isClean(dir)) {
            return false;
        }
        String branch = VcsOperations.currentBranch(dir);
        if (branch.isBlank()) {
            // Detached HEAD (release-tag checkout, mid-bisect): there is no
            // branch to key the per-user stash ref on, and the work is not on a
            // shareable branch anyway. Skip auto-stash rather than build a
            // malformed (trailing-slash) ref; leave the tree for the caller's
            // own handling instead of failing the whole sync.
            log.warn("    " + dir.getName() + ": detached HEAD — skipping "
                    + "auto-stash (commit or branch the work first)");
            return false;
        }
        ParkSupport.stashLeave(dir, log, slug(dir), branch);
        return true;
    }

    /**
     * Re-apply the stash left by {@link #stashIfDirty} onto the current branch
     * (now advanced by the sync). A no-op when {@code stashed} is false.
     *
     * @param dir     the repository directory
     * @param log     Maven logger
     * @param stashed whether {@link #stashIfDirty} left a stash
     * @throws MojoException if the re-apply or ref cleanup fails
     */
    static void restoreIfStashed(File dir, Log log, boolean stashed)
            throws MojoException {
        if (!stashed) {
            return;
        }
        ParkSupport.stashArrive(dir, log, slug(dir),
                VcsOperations.currentBranch(dir));
    }

    /**
     * Resolve the per-user stash slug for {@code dir} from its
     * {@code git user.email}, failing loud with a friendly message rather than
     * silently dropping WIP when the email is unset.
     */
    private static String slug(File dir) throws MojoException {
        try {
            return VcsOperations.userSlug(VcsOperations.userEmail(dir));
        } catch (MojoException e) {
            throw new MojoException("Auto-stash needs a git user.email to key "
                    + "the per-user stash ref so in-flight work is never lost. "
                    + "Set it (git config user.email <you@example>), or commit "
                    + "your changes first. Cause: " + e.getMessage(), e);
        }
    }
}
