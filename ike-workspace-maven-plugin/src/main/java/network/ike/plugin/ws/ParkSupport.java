package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Work-preserving park and per-user auto-stash primitives shared by the
 * branch-scoped workspace goals (ike-issues#573, ike-issues#575).
 *
 * <p>"Parking" a subproject sets its clone aside without losing work: the
 * subproject's branch is first pushed to {@value #STASH_REMOTE} (origin is the
 * park), and only then is the local clone removed. If the push fails the park
 * <b>aborts in place</b> — the clone is never reduced below what is on origin.
 * "Stashing" moves uncommitted work (including untracked files) to a pushable
 * per-user custom ref ({@code refs/ws-stash/<user-slug>/<branch>}) on origin so
 * it follows the developer across branches and machines, and re-applies it on
 * return.
 *
 * <p>These primitives were extracted verbatim from {@code WsSwitchDraftMojo}
 * (where {@code ws:switch} introduced them in ike-issues#573) so {@code ws:remove}
 * can reuse them, taking an explicit {@link Log} rather than reaching for a
 * mojo's {@code getLog()}. The extraction is behavior-preserving for
 * {@code ws:switch}.
 */
final class ParkSupport {

    /** Full ref prefix for auto-stash refs (see ike-issues#153). */
    static final String STASH_REF_PREFIX = "refs/ws-stash/";

    /** Remote name for auto-stash and park push/fetch/delete. */
    static final String STASH_REMOTE = "origin";

    private ParkSupport() {
    }

    /**
     * Build the auto-stash ref path for a given user slug and branch.
     * Branches with {@code /} in their name (e.g. {@code feature/A})
     * become multi-segment ref paths, which git supports.
     *
     * @param slug   user slug from {@link VcsOperations#userSlug(String)}
     * @param branch the branch name
     * @return full ref path, e.g.
     *         {@code "refs/ws-stash/kec--knowledge-design/feature/A"}
     */
    static String stashRef(String slug, String branch) {
        return STASH_REF_PREFIX + slug + "/" + branch;
    }

    /**
     * Park a subproject that is not a member of the target branch (#573):
     * push its branch to {@value #STASH_REMOTE} so no work is lost, then
     * remove the local clone. Aborts in place (no deletion) if the push
     * fails — the working tree is never reduced below what is on origin.
     *
     * @param dir        the subproject directory
     * @param log        Maven logger
     * @param name       the subproject name
     * @param compBranch the branch the subproject is currently on
     * @throws MojoException if the branch cannot be pushed (park aborts)
     */
    static void parkSubproject(File dir, Log log, String name, String compBranch)
            throws MojoException {
        try {
            VcsOperations.push(dir, log, STASH_REMOTE, compBranch);
        } catch (MojoException e) {
            throw new MojoException("Park aborted for '" + name + "': could not "
                    + "push '" + compBranch + "' to " + STASH_REMOTE
                    + " — refusing to remove a clone whose work is not on "
                    + "origin. " + e.getMessage(), e);
        }
        log.info("  " + Ansi.cyan("⇲ ") + name + " — parked ("
                + compBranch + " → " + STASH_REMOTE + ", clone removed)");
        deleteDirectory(dir.toPath());
    }

    /**
     * Execute the leave flow on a subproject with uncommitted work:
     * stash (including untracked), move stash to custom ref, drop local
     * stash entry, push ref to origin. A collision on the source ref is
     * detected at preflight; hitting it here means state changed between
     * preflight and execute (racy), so fail loudly.
     *
     * @param dir          the subproject directory
     * @param log          Maven logger
     * @param slug         user slug
     * @param sourceBranch the branch we're leaving
     * @throws MojoException if any step fails
     */
    static void stashLeave(File dir, Log log, String slug, String sourceBranch)
            throws MojoException {
        String ref = stashRef(slug, sourceBranch);
        String message = "ws-auto/" + sourceBranch;
        VcsOperations.stashPushUntracked(dir, log, message);
        VcsOperations.updateRef(dir, log, ref, "refs/stash");
        VcsOperations.stashDrop(dir, log);
        VcsOperations.pushRef(dir, log, STASH_REMOTE, ref);
        log.info("    " + Ansi.yellow("↟ ") + "stashed → " + ref);
    }

    /**
     * Execute the arrive flow on a subproject that's just checked out
     * the target branch: probe for a remote stash ref for this
     * user/branch; if present, fetch it, apply it, and delete the ref
     * locally and remotely.
     *
     * @param dir          the subproject directory
     * @param log          Maven logger
     * @param slug         user slug
     * @param targetBranch the branch we just switched to
     * @return {@code true} if a stash was applied, {@code false} if no
     *         stash was present
     * @throws MojoException if the apply or cleanup fails
     */
    static boolean stashArrive(File dir, Log log, String slug, String targetBranch)
            throws MojoException {
        String ref = stashRef(slug, targetBranch);
        boolean present;
        try {
            present = VcsOperations.remoteRefExists(dir, STASH_REMOTE, ref);
        } catch (MojoException e) {
            log.warn("    " + Ansi.yellow("⚠ ") + "could not probe "
                    + STASH_REMOTE + " for " + ref + " — " + e.getMessage());
            return false;
        }
        if (!present) return false;

        VcsOperations.fetchRef(dir, log, STASH_REMOTE, ref);
        VcsOperations.stashApply(dir, log, ref);
        VcsOperations.deleteLocalRef(dir, log, ref);
        VcsOperations.deleteRemoteRef(dir, log, STASH_REMOTE, ref);
        log.info("    " + Ansi.green("↡ ") + "stash applied from " + ref);
        return true;
    }

    /**
     * Recursively delete a directory tree (removes a parked clone).
     *
     * @param dir the directory to delete
     * @throws MojoException if deletion fails
     */
    static void deleteDirectory(Path dir) throws MojoException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException | RuntimeException e) {
            throw new MojoException(
                    "Failed to delete " + dir + ": " + e.getMessage(), e);
        }
    }
}
