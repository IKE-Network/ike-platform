package network.ike.plugin.ws.vcs;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Git and VCS state operations for the IKE VCS Bridge.
 *
 * <p>All git commands use {@link ProcessBuilder}. Commands that modify
 * state (commit, push, branch creation) set {@code IKE_VCS_CONTEXT}
 * in the subprocess environment so that the pre-commit and pre-push
 * hooks allow the operation through.
 */
public class VcsOperations {

    private static final String IKE_VCS_CONTEXT = "IKE_VCS_CONTEXT";
    private static final String CONTEXT_VALUE = "ike-maven-plugin";

    private VcsOperations() {}

    // ── Git queries ──────────────────────────────────────────────

    /**
     * Get the 8-character short SHA of HEAD.
     *
     * @param dir the repository root directory
     * @return the short SHA string
     * @throws MojoException if the git command fails
     */
    public static String headSha(File dir) throws MojoException {
        return capture(dir, "git", "rev-parse", "--short=8", "HEAD");
    }

    /**
     * Get the current branch name.
     *
     * @param dir the repository root directory
     * @return the current branch name
     * @throws MojoException if the git command fails
     */
    public static String currentBranch(File dir) throws MojoException {
        return capture(dir, "git", "branch", "--show-current");
    }

    /**
     * Get the 8-character short SHA of a remote branch, or empty if unreachable.
     *
     * @param dir    the repository root directory
     * @param remote the remote name (e.g., "origin")
     * @param branch the branch name to query
     * @return the short SHA, or empty if the remote branch is unreachable
     * @throws MojoException if the git command fails
     */
    public static Optional<String> remoteSha(File dir, String remote, String branch)
            throws MojoException {
        try {
            String output = capture(dir, "git", "ls-remote", remote, branch);
            if (output.isEmpty()) {
                return Optional.empty();
            }
            // ls-remote output: <full-sha>\trefs/heads/<branch>
            String fullSha = output.split("\\s+")[0];
            return Optional.of(fullSha.substring(0, 8));
        } catch (MojoException e) {
            return Optional.empty();
        }
    }

    /**
     * Check whether the working tree is clean (no staged or unstaged changes).
     *
     * @param dir the repository root directory
     * @return true if the working tree has no changes
     */
    public static boolean isClean(File dir) {
        try {
            String status = capture(dir, "git", "status", "--porcelain");
            return status.isEmpty();
        } catch (MojoException e) {
            return false;
        }
    }

    /**
     * Check whether there are staged changes ready to commit.
     *
     * @param dir the repository root directory
     * @return true if the index has staged changes
     */
    public static boolean hasStagedChanges(File dir) {
        try {
            String diff = capture(dir, "git", "diff", "--cached", "--name-only");
            return !diff.isEmpty();
        } catch (MojoException e) {
            return false;
        }
    }

    /**
     * Check whether there are modified but unstaged changes in the working tree.
     *
     * @param dir the repository root directory
     * @return true if there are unstaged modifications
     */
    public static boolean hasUnstagedChanges(File dir) {
        try {
            String diff = capture(dir, "git", "diff", "--name-only");
            return !diff.isEmpty();
        } catch (MojoException e) {
            return false;
        }
    }

    /**
     * List files with unstaged modifications in the working tree.
     * Returns a comma-separated summary suitable for log messages.
     *
     * @param dir the repository root directory
     * @return comma-separated file names, or empty string if clean
     */
    public static String unstagedFiles(File dir) {
        try {
            String diff = capture(dir, "git", "diff", "--name-only");
            if (diff.isEmpty()) return "";
            return String.join(", ", diff.split("\n"));
        } catch (MojoException e) {
            return "";
        }
    }

    /**
     * List files with any uncommitted changes (staged, unstaged, or untracked).
     * Returns the raw porcelain output suitable for detailed error messages.
     *
     * @param dir the repository root directory
     * @return porcelain status output, or empty string if clean
     */
    public static String uncommittedStatus(File dir) {
        try {
            return capture(dir, "git", "status", "--porcelain");
        } catch (MojoException e) {
            return "";
        }
    }

    /**
     * List files with unresolved merge conflicts.
     *
     * @param dir the repository root directory
     * @return list of conflicting file paths, empty if none
     */
    public static List<String> conflictingFiles(File dir) {
        try {
            String output = capture(dir, "git", "diff", "--name-only", "--diff-filter=U");
            if (output.isEmpty()) return List.of();
            return List.of(output.split("\n"));
        } catch (MojoException e) {
            return List.of();
        }
    }

    /**
     * Predict merge conflicts without touching the index or working tree.
     *
     * <p>Uses {@code git merge-tree --write-tree} (git 2.38+) to perform
     * a trial merge in memory. Returns the list of conflicting file paths,
     * or an empty list if the merge would be clean.
     *
     * <p>Falls back gracefully on older git versions — returns an empty
     * list (conflict prediction unavailable).
     *
     * @param dir    the repository root directory
     * @param branch the branch to merge into (e.g., current feature branch)
     * @param other  the branch to merge from (e.g., "main")
     * @return list of file paths that would conflict, empty if clean or unknown
     */
    public static List<String> predictConflicts(File dir, String branch, String other) {
        try {
            // git merge-tree --write-tree exits 0 if clean, 1 if conflicts
            // With --name-only, conflicting file names appear after a blank line
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "merge-tree", "--write-tree", "--name-only",
                    branch, other)
                    .directory(dir)
                    .redirectErrorStream(false);
            Process proc = pb.start();

            String stdout = new String(
                    proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(
                    proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = proc.waitFor();

            if (exit == 0) {
                return List.of(); // clean merge
            }

            if (exit == 1 && !stdout.isEmpty()) {
                // Output format: tree SHA on first line, then blank line,
                // then conflicting file names (one per line)
                String[] sections = stdout.split("\n\n", 2);
                if (sections.length == 2 && !sections[1].isBlank()) {
                    return List.of(sections[1].trim().split("\n"));
                }
            }

            // Unexpected exit or format — can't predict
            return List.of();
        } catch (Exception e) {
            // git merge-tree not available or failed — can't predict
            return List.of();
        }
    }

    /**
     *
     * @param dir  the repository root directory
     * @param base the starting ref (exclusive)
     * @param head the ending ref (inclusive)
     * @return list of one-line commit summaries between the two refs
     * @throws MojoException if the git command fails
     */
    public static List<String> commitLog(File dir, String base, String head)
            throws MojoException {
        String output = capture(dir, "git", "log",
                base + ".." + head, "--oneline", "--no-decorate");
        if (output.isEmpty()) return List.of();
        return List.of(output.split("\n"));
    }

    // ── Git operations ───────────────────────────────────────────

    /**
     * Fetch from all remotes.
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @throws MojoException if the git command fails
     */
    public static void fetch(File dir, Log log) throws MojoException {
        run(dir, log, null, "git", "fetch", "--all", "--quiet");
    }

    /**
     * Soft reset (no --hard) — updates HEAD and index, leaves working tree.
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @param ref the ref to reset to (e.g., "origin/main")
     * @throws MojoException if the git command fails
     */
    public static void resetSoft(File dir, Log log, String ref)
            throws MojoException {
        run(dir, log, null, "git", "reset", ref, "--quiet");
    }

    /**
     * Hard reset — updates HEAD, index, and working tree to match
     * {@code ref}, discarding uncommitted changes. Also clears any
     * in-progress merge state ({@code .git/SQUASH_MSG},
     * {@code .git/MERGE_MSG}), which is useful after a {@code git merge
     * --squash} whose squashed diff turned out to be empty — see
     * issue #162.
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @param ref the ref to reset to (e.g., {@code "HEAD"})
     * @throws MojoException if the git command fails
     */
    public static void resetHard(File dir, Log log, String ref)
            throws MojoException {
        run(dir, log, null, "git", "reset", "--hard", ref, "--quiet");
    }

    /**
     * Check whether one commit is an ancestor of (or equal to) another.
     *
     * <p>Uses {@code git merge-base --is-ancestor}: exit 0 means yes,
     * exit 1 means no, any other exit is an error (e.g., unknown ref).
     *
     * @param dir           the repository root directory
     * @param maybeAncestor candidate ancestor commit (ref or sha)
     * @param descendant    candidate descendant commit (ref or sha)
     * @return {@code true} iff {@code maybeAncestor} is reachable from
     *         {@code descendant} via parent edges (or is equal)
     * @throws MojoException if either ref is unknown or the git command fails
     */
    public static boolean isAncestor(File dir, String maybeAncestor, String descendant)
            throws MojoException {
        try {
            Process proc = new ProcessBuilder("git", "merge-base",
                    "--is-ancestor", maybeAncestor, descendant)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            int exit = proc.waitFor();
            if (exit == 0) return true;
            if (exit == 1) return false;
            throw new MojoException(
                    "git merge-base --is-ancestor " + maybeAncestor + " "
                            + descendant + " failed (exit " + exit + "): " + output);
        } catch (IOException | InterruptedException e) {
            throw new MojoException(
                    "Failed to check ancestry of " + maybeAncestor + " / "
                            + descendant + ": " + e.getMessage(), e);
        }
    }

    /**
     * Checkout an existing branch.
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param branch the branch to check out
     * @throws MojoException if the git command fails
     */
    public static void checkout(File dir, Log log, String branch)
            throws MojoException {
        run(dir, log, null, "git", "checkout", branch);
    }

    /**
     * Create and checkout a new branch.
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param branch the new branch name to create
     * @throws MojoException if the git command fails
     */
    public static void checkoutNew(File dir, Log log, String branch)
            throws MojoException {
        run(dir, log, null, "git", "checkout", "-b", branch);
    }

    /**
     * Stage all changes and commit with the given message.
     * Sets {@code IKE_VCS_CONTEXT} to bypass the pre-commit hook.
     *
     * @param dir     the repository root directory
     * @param log     Maven logger
     * @param message the commit message
     * @throws MojoException if the git command fails
     */
    public static void commit(File dir, Log log, String message)
            throws MojoException {
        commitWithStdin(dir, log, message, "git", "commit", "-F", "-");
    }

    /**
     * Commit without staging (assumes files are already staged).
     * Sets {@code IKE_VCS_CONTEXT} to bypass the pre-commit hook.
     *
     * @param dir     the repository root directory
     * @param log     Maven logger
     * @param message the commit message, or {@code null} to open the editor
     * @throws MojoException if the git command fails
     */
    public static void commitStaged(File dir, Log log, String message)
            throws MojoException {
        if (message == null) {
            runWithContext(dir, log, "git", "commit");
        } else {
            commitWithStdin(dir, log, message, "git", "commit", "-F", "-");
        }
    }

    /**
     * Stage all files.
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @throws MojoException if the git command fails
     */
    public static void addAll(File dir, Log log) throws MojoException {
        run(dir, log, null, "git", "add", "-A");
    }

    /**
     * Push to remote. Sets {@code IKE_VCS_CONTEXT} to bypass the pre-push hook.
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param remote the remote name (e.g., "origin")
     * @param branch the branch to push
     * @throws MojoException if the git command fails
     */
    public static void push(File dir, Log log, String remote, String branch)
            throws MojoException {
        runWithContext(dir, log, "git", "push", remote, branch);
    }

    /**
     * Push to remote, ignoring failures (no remote, offline, etc.).
     * Logs a warning on failure instead of throwing.
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param remote the remote name (e.g., "origin")
     * @param branch the branch to push
     */
    public static void pushSafe(File dir, Log log, String remote, String branch) {
        try {
            push(dir, log, remote, branch);
        } catch (MojoException e) {
            log.warn("  Push failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Push to remote if it exists. If no remote is configured, logs
     * a helpful message instead of failing with a cryptic git error.
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param remote the remote name (e.g., "origin")
     * @param branch the branch to push
     */
    public static void pushIfRemoteExists(File dir, Log log,
                                            String remote, String branch) {
        try {
            if (!network.ike.plugin.ReleaseSupport.hasRemote(dir, remote)) {
                log.info("  No remote '" + remote + "' configured for "
                        + dir.getName() + " — changes remain local.");
                return;
            }
            push(dir, log, remote, branch);
        } catch (MojoException e) {
            log.warn("  Push failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Push to remote with upstream tracking.
     * Sets {@code IKE_VCS_CONTEXT} to bypass the pre-push hook.
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param remote the remote name (e.g., "origin")
     * @param branch the branch to push
     * @throws MojoException if the git command fails
     */
    public static void pushWithUpstream(File dir, Log log, String remote, String branch)
            throws MojoException {
        runWithContext(dir, log, "git", "push", "-u", remote, branch);
    }

    /**
     * Delete a local branch. Uses {@code -D} (force) because squash-merged
     * branches are not recognized as "fully merged" by git.
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param branch the branch to delete
     * @throws MojoException if the git command fails
     */
    public static void deleteBranch(File dir, Log log, String branch)
            throws MojoException {
        run(dir, log, null, "git", "branch", "-D", branch);
    }

    /**
     * Delete a remote branch.
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param remote the remote name (e.g., "origin")
     * @param branch the branch to delete on the remote
     * @throws MojoException if the git command fails
     */
    public static void deleteRemoteBranch(File dir, Log log, String remote, String branch)
            throws MojoException {
        runWithContext(dir, log, "git", "push", remote, "--delete", branch);
    }

    /**
     * Squash-merge a branch into the current branch (does not commit).
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param branch the branch to squash-merge
     * @throws MojoException if the git command fails
     */
    public static void mergeSquash(File dir, Log log, String branch)
            throws MojoException {
        run(dir, log, null, "git", "merge", "--squash", branch);
    }

    /**
     * No-fast-forward merge with a merge commit.
     *
     * @param dir     the repository root directory
     * @param log     Maven logger
     * @param branch  the branch to merge
     * @param message the merge commit message
     * @throws MojoException if the git command fails
     */
    public static void mergeNoFf(File dir, Log log, String branch, String message)
            throws MojoException {
        runWithContext(dir, log, "git", "merge", "--no-ff", branch, "-m", message);
    }

    /**
     * Check whether a local branch exists.
     *
     * @param dir    the repository root directory
     * @param branch the branch name to check
     * @return true if the branch exists locally
     */
    public static boolean localBranchExists(File dir, String branch) {
        try {
            String output = capture(dir, "git", "branch", "--list", branch);
            return !output.trim().isEmpty();
        } catch (MojoException e) {
            return false;
        }
    }

    /**
     * List local branches matching a prefix that are fully merged into
     * the given target branch.
     *
     * @param dir    the repository root directory
     * @param target the branch to check merge status against (e.g., "main")
     * @param prefix the branch name prefix to filter (e.g., "feature/")
     * @return list of merged branch names (trimmed, without leading {@code * })
     */
    public static List<String> mergedBranches(File dir, String target, String prefix) {
        try {
            String output = capture(dir, "git", "branch", "--merged", target);
            if (output.isEmpty()) return List.of();
            return output.lines()
                    .map(line -> line.replaceFirst("^[* ] +", ""))
                    .filter(b -> b.startsWith(prefix))
                    .filter(b -> !b.equals(target))
                    .toList();
        } catch (MojoException e) {
            return List.of();
        }
    }

    /**
     * List all local branches matching a prefix.
     *
     * @param dir    the repository root directory
     * @param prefix the branch name prefix to filter (e.g., "feature/")
     * @return list of branch names
     */
    public static List<String> localBranches(File dir, String prefix) {
        try {
            String output = capture(dir, "git", "branch");
            if (output.isEmpty()) return List.of();
            return output.lines()
                    .map(line -> line.replaceFirst("^[* ] +", ""))
                    .filter(b -> b.startsWith(prefix))
                    .toList();
        } catch (MojoException e) {
            return List.of();
        }
    }

    /**
     * Get the date of the last commit on a branch (ISO format).
     *
     * @param dir    the repository root directory
     * @param branch the branch name
     * @return the commit date string, or "unknown" on failure
     */
    public static String branchLastCommitDate(File dir, String branch) {
        try {
            return capture(dir, "git", "log", "-1", "--format=%ci", branch);
        } catch (MojoException e) {
            return "unknown";
        }
    }

    // ── Auto-stash via pushable refs (ws:switch, #153) ────────────

    /**
     * Read {@code git config user.email} for the repository.
     *
     * @param dir the repository root directory
     * @return the configured email address
     * @throws MojoException if no email is configured
     */
    public static String userEmail(File dir) throws MojoException {
        String email = capture(dir, "git", "config", "user.email").trim();
        if (email.isEmpty()) {
            throw new MojoException(
                    "git config user.email is not set in " + dir);
        }
        return email;
    }

    /**
     * Derive an ASCII-safe slug from a git email address. Used as the
     * per-user component of the {@code refs/ws-stash/<slug>/<branch>}
     * ref naming scheme. Lowercases, replaces {@code @} with
     * {@code --}, and {@code .} with {@code -}.
     *
     * @param email the email address (typically from {@link #userEmail})
     * @return an ASCII slug safe for ref names and cross-platform shells
     */
    public static String userSlug(String email) {
        return email.toLowerCase()
                .replace("@", "--")
                .replace(".", "-");
    }

    /**
     * Check whether a remote ref exists by shelling out to
     * {@code git ls-remote}. Distinguishes "ref absent" from
     * "remote unreachable": a zero-exit with empty stdout means absent,
     * a zero-exit with output means present, and a non-zero exit
     * surfaces the network/auth error to the caller.
     *
     * @param dir    the repository root directory
     * @param remote the remote name (e.g., {@code "origin"})
     * @param ref    the full ref to probe (e.g.,
     *               {@code "refs/ws-stash/kec--knowledge-design/feature/A"})
     * @return {@code true} if the ref exists on the remote,
     *         {@code false} if absent
     * @throws MojoException if the remote is unreachable or the probe fails
     */
    public static boolean remoteRefExists(File dir, String remote, String ref)
            throws MojoException {
        String output = capture(dir, "git", "ls-remote", remote, ref);
        return !output.isEmpty();
    }

    /**
     * Stash the working tree including untracked files
     * ({@code git stash push -u -m <message>}). Ignored files are
     * skipped.
     *
     * @param dir     the repository root directory
     * @param log     Maven logger
     * @param message the stash message
     * @throws MojoException if the git command fails
     */
    public static void stashPushUntracked(File dir, Log log, String message)
            throws MojoException {
        run(dir, log, null, "git", "stash", "push", "-u", "-m", message);
    }

    /**
     * Apply a stash identified by its ref ({@code git stash apply <ref>}).
     * Unlike {@code git stash apply} with a numbered index, this form
     * accepts a full ref path (e.g. {@code refs/ws-stash/slug/branch}).
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @param ref the stash ref to apply
     * @throws MojoException if the git command fails
     */
    public static void stashApply(File dir, Log log, String ref)
            throws MojoException {
        run(dir, log, null, "git", "stash", "apply", ref);
    }

    /**
     * Drop the top of the stash stack ({@code git stash drop}).
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @throws MojoException if the git command fails
     */
    public static void stashDrop(File dir, Log log) throws MojoException {
        run(dir, log, null, "git", "stash", "drop");
    }

    /**
     * Point a ref at a given target ({@code git update-ref <ref> <target>}).
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param ref    the ref to update (full path, e.g.
     *               {@code "refs/ws-stash/slug/branch"})
     * @param target the ref or SHA to point at
     * @throws MojoException if the git command fails
     */
    public static void updateRef(File dir, Log log, String ref, String target)
            throws MojoException {
        run(dir, log, null, "git", "update-ref", ref, target);
    }

    /**
     * Delete a local ref ({@code git update-ref -d <ref>}).
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @param ref the ref to delete
     * @throws MojoException if the git command fails
     */
    public static void deleteLocalRef(File dir, Log log, String ref)
            throws MojoException {
        run(dir, log, null, "git", "update-ref", "-d", ref);
    }

    /**
     * Push a ref to a remote under the same name
     * ({@code git push <remote> <ref>:<ref>}).
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param remote the remote name
     * @param ref    the ref to push
     * @throws MojoException if the git command fails
     */
    public static void pushRef(File dir, Log log, String remote, String ref)
            throws MojoException {
        run(dir, log, null, "git", "push", remote, ref + ":" + ref);
    }

    /**
     * Delete a ref from a remote ({@code git push <remote> :<ref>}).
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param remote the remote name
     * @param ref    the ref to delete on the remote
     * @throws MojoException if the git command fails
     */
    public static void deleteRemoteRef(File dir, Log log,
                                        String remote, String ref)
            throws MojoException {
        run(dir, log, null, "git", "push", remote, ":" + ref);
    }

    /**
     * Fetch a remote ref into a local ref of the same name
     * ({@code git fetch <remote> <ref>:<ref>}).
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param remote the remote name
     * @param ref    the ref to fetch
     * @throws MojoException if the git command fails
     */
    public static void fetchRef(File dir, Log log, String remote, String ref)
            throws MojoException {
        run(dir, log, null, "git", "fetch", remote, ref + ":" + ref);
    }

    // ── VCS state operations ─────────────────────────────────────

    /**
     * Write the VCS state file for the given directory.
     *
     * @param dir    the repository root directory
     * @param action the action being performed
     * @throws MojoException if writing the state file fails
     */
    public static void writeVcsState(File dir, VcsState.Action action)
            throws MojoException {
        try {
            String branch = currentBranch(dir);
            String sha = headSha(dir);
            VcsState state = VcsState.create(branch, sha, action);
            VcsState.writeTo(dir.toPath(), state);
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to write VCS state file: " + e.getMessage(), e);
        }
    }

    /**
     * Check whether the local HEAD matches the VCS state file.
     *
     * @param dir the repository root directory
     * @return true if in sync or if no state file exists, false if catch-up is needed
     * @throws MojoException if reading git state fails
     */
    public static boolean needsSync(File dir) throws MojoException {
        Optional<VcsState> state = VcsState.readFrom(dir.toPath());
        if (state.isEmpty()) {
            return false;
        }
        String localSha = headSha(dir);
        String localBranch = currentBranch(dir);
        VcsState s = state.get();
        return !s.sha().equals(localSha) || !s.branch().equals(localBranch);
    }

    /**
     * Synchronize local git state to match the VCS state file.
     * Fetches from all remotes, switches branch if needed, and soft-resets.
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @return the resulting HEAD SHA after sync
     * @throws MojoException if a git command or state file read fails
     */
    public static String sync(File dir, Log log) throws MojoException {
        Optional<VcsState> stateOpt = VcsState.readFrom(dir.toPath());
        if (stateOpt.isEmpty()) {
            log.info("  No VCS state file — nothing to sync.");
            return headSha(dir);
        }

        VcsState state = stateOpt.get();
        log.info("  Syncing to: " + state.action().label() + " by " + state.machine()
                + " at " + state.timestamp());

        fetch(dir, log);

        String localBranch = currentBranch(dir);
        if (!state.branch().equals(localBranch)) {
            if (!localBranchExists(dir, state.branch())) {
                // Branch doesn't exist locally — check remote
                Optional<String> remoteCheck = remoteSha(dir, "origin", state.branch());
                if (remoteCheck.isEmpty()) {
                    log.warn("  Branch " + state.branch()
                            + " does not exist locally or on origin.");
                    log.warn("  The branch may not have been pushed from "
                            + state.machine() + " yet.");
                    log.warn("  Push from " + state.machine()
                            + " first, then retry sync.");
                    return headSha(dir);
                }
                // Create local tracking branch from remote
                log.info("  Creating local branch from origin: " + state.branch());
                run(dir, log, null, "git", "checkout", "-b",
                        state.branch(), "origin/" + state.branch());
            } else {
                log.info("  Switching branch: " + localBranch + " → " + state.branch());
                checkout(dir, log, state.branch());
            }
        }

        Optional<String> remoteRef = remoteSha(dir, "origin", state.branch());
        if (remoteRef.isEmpty()) {
            log.info("  No remote ref for " + state.branch()
                    + " on origin — branch is local-only, using local state.");
            return headSha(dir);
        }

        // Evaluate the relationship between local branch tip and origin
        // before touching HEAD. An unconditional reset-to-origin would
        // silently discard unpushed local commits (#144).
        String localSha = headSha(dir);
        String remoteShaValue = remoteRef.get();

        if (localSha.equals(remoteShaValue)) {
            log.info("  Already at origin/" + state.branch()
                    + " — no reset needed.");
        } else if (isAncestor(dir, remoteShaValue, localSha)) {
            // Local is strictly ahead of origin. Preserve the unpushed
            // commits — the caller (usually ws:push) will push them.
            log.info("  Local " + state.branch()
                    + " is ahead of origin — keeping unpushed commits.");
        } else if (isAncestor(dir, localSha, remoteShaValue)) {
            // Local is strictly behind origin. Fast-forward is safe
            // (equivalent to git pull --ff-only).
            log.info("  Fast-forwarding " + state.branch() + " to origin.");
            resetSoft(dir, log, "origin/" + state.branch());
        } else {
            // Diverged — local and origin each have unique commits.
            // Refuse to silently pick a side; ask the human.
            throw new MojoException(
                    "Local " + state.branch() + " (" + localSha
                            + ") has diverged from origin/" + state.branch()
                            + " (" + remoteShaValue + ") — neither is an "
                            + "ancestor of the other. Resolve manually "
                            + "(git pull --rebase, git rebase origin/"
                            + state.branch() + ", or ws:update-feature), "
                            + "then retry.");
        }

        String newSha = headSha(dir);
        if (!newSha.equals(state.sha())) {
            log.warn("  HEAD after sync (" + newSha + ") does not match state file ("
                    + state.sha() + ").");
            log.warn("  The push from " + state.machine() + " may not have completed.");
            log.warn("  Push from " + state.machine() + " first, then retry sync.");
        } else {
            log.info("  HEAD now matches state file: " + newSha);
        }

        return newSha;
    }

    /**
     * Catch-up preamble: sync if needed, otherwise report that we're current.
     * Used by all goals that modify state (commit, push, feature-start, etc.).
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @throws MojoException if sync fails
     */
    public static void catchUp(File dir, Log log) throws MojoException {
        if (!VcsState.isIkeManaged(dir.toPath())) {
            return;
        }
        if (needsSync(dir)) {
            log.info("  VCS state is behind — catching up...");
            sync(dir, log);
        }
    }

    // ── Internal helpers ─────────────────────────────────────────

    /**
     * Run a command with output routed through the Maven logger.
     * Optionally sets environment variables.
     */
    private static void run(File workDir, Log log, Map<String, String> env,
                            String... command) throws MojoException {
        log.debug("» " + String.join(" ", command));
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true);
            if (env != null) {
                pb.environment().putAll(env);
            }
            Process proc = pb.start();
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("  " + line);
                }
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new MojoException(
                        "Command failed (exit " + exit + "): "
                                + String.join(" ", command));
            }
        } catch (IOException | InterruptedException e) {
            throw new MojoException(
                    "Failed to execute: " + String.join(" ", command), e);
        }
    }

    /**
     * Run a command with {@code IKE_VCS_CONTEXT} set in the environment.
     */
    private static void runWithContext(File workDir, Log log, String... command)
            throws MojoException {
        run(workDir, log, Map.of(IKE_VCS_CONTEXT, CONTEXT_VALUE), command);
    }

    /**
     * Run a git command that reads its message from stdin via {@code -F -}.
     *
     * <p>This supports multi-line messages reliably — no shell quoting
     * issues, no argument-length limits. The command array should include
     * {@code "-F", "-"} where the message would normally follow {@code -m}.
     *
     * <p>Sets {@code IKE_VCS_CONTEXT} to bypass the pre-commit hook.
     *
     * @param workDir the repository root directory
     * @param log     Maven logger
     * @param message the message to write to stdin
     * @param command the git command (including {@code -F -})
     * @throws MojoException if the command fails
     */
    private static void commitWithStdin(File workDir, Log log,
                                         String message, String... command)
            throws MojoException {
        log.debug("» " + String.join(" ", command) + " <<< (message via stdin)");
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true);
            pb.environment().put(IKE_VCS_CONTEXT, CONTEXT_VALUE);
            Process proc = pb.start();

            // Write message to stdin, then close to signal EOF
            try (var out = proc.getOutputStream()) {
                out.write(message.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            // Consume stdout/stderr
            String output;
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(),
                            StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exit = proc.waitFor();
            if (exit != 0) {
                throw new MojoException(
                        "Command failed (exit " + exit + "): "
                                + String.join(" ", command)
                                + (output.isEmpty() ? "" : "\n" + output));
            }
        } catch (IOException | InterruptedException e) {
            throw new MojoException(
                    "Failed to execute: " + String.join(" ", command), e);
        }
    }

    /**
     * Run a command and capture stdout as a trimmed string.
     */
    private static String capture(File workDir, String... command)
            throws MojoException {
        try {
            Process proc = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(false)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(),
                            StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new MojoException(
                        "Command failed (exit " + exit + "): "
                                + String.join(" ", command));
            }
            return output;
        } catch (IOException | InterruptedException e) {
            throw new MojoException(
                    "Failed to execute: " + String.join(" ", command), e);
        }
    }
}
