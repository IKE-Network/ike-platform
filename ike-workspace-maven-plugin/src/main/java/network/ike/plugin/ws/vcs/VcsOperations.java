package network.ike.plugin.ws.vcs;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    /**
     * Attempts for a transient-safe git command — the original call
     * plus one retry. Covers read-only queries (ike-issues#602) and
     * idempotent mutations (ike-issues#636).
     */
    private static final int TRANSIENT_RETRY_ATTEMPTS = 2;

    /** Backoff before the single retry of a transient-safe git command. */
    private static final long TRANSIENT_RETRY_BACKOFF_MILLIS = 150L;

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
        return captureRead(dir, "git", "rev-parse", "--short=8", "HEAD");
    }

    /**
     * Get the 8-character short SHA of a local branch tip (without
     * checking it out). Same width as {@link #remoteSha}, so the two are
     * directly comparable for push verification.
     *
     * @param dir    the repository root directory
     * @param branch the local branch name
     * @return the branch tip's short SHA
     * @throws MojoException if the branch does not resolve
     */
    public static String branchSha(File dir, String branch) throws MojoException {
        return captureRead(dir, "git", "rev-parse", "--short=8",
                "refs/heads/" + branch);
    }

    /**
     * Get the current branch name.
     *
     * @param dir the repository root directory
     * @return the current branch name
     * @throws MojoException if the git command fails
     */
    public static String currentBranch(File dir) throws MojoException {
        return captureRead(dir, "git", "branch", "--show-current");
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
            String output = captureRead(dir, "git", "ls-remote", remote, branch);
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
     * Check whether a single path is committed-clean — no staged or
     * unstaged modification and not untracked. Used to decide whether a
     * machine-derived rewrite of that path (e.g. the
     * {@code workspace.yaml depends-on} re-derivation in
     * {@link network.ike.plugin.ws.PostMutationSync}) is the <em>sole</em>
     * pending change to it, so it can be committed in isolation without
     * sweeping in a caller's own concurrent edit
     * (IKE-Network/ike-issues#774).
     *
     * @param dir  the repository root directory
     * @param path the path to check, relative to {@code dir}
     * @return true if {@code path} has no pending changes
     */
    public static boolean isPathClean(File dir, String path) {
        try {
            String status =
                    capture(dir, "git", "status", "--porcelain", "--", path);
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
     * Count tracked files with modifications (staged or unstaged), excluding
     * untracked files. Use {@link #untrackedFiles(File)} for the new-file list.
     *
     * @param dir the repository root directory
     * @return count of modified tracked files; zero if clean or on error
     */
    public static int modifiedTrackedCount(File dir) {
        try {
            String porcelain = capture(dir, "git", "status", "--porcelain");
            if (porcelain.isEmpty()) return 0;
            int count = 0;
            for (String line : porcelain.split("\n")) {
                if (line.length() < 2) continue;
                // ?? = untracked, !! = ignored; everything else is tracked
                if (!line.startsWith("??") && !line.startsWith("!!")) count++;
            }
            return count;
        } catch (MojoException e) {
            return 0;
        }
    }

    /**
     * List untracked, non-ignored files in the working tree.
     *
     * @param dir the repository root directory
     * @return list of untracked file paths; empty if clean or on error
     */
    public static List<String> untrackedFiles(File dir) {
        try {
            String output = capture(dir, "git", "ls-files",
                    "--others", "--exclude-standard");
            if (output.isEmpty()) return List.of();
            return List.of(output.split("\n"));
        } catch (MojoException e) {
            return List.of();
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
     * List files changed on {@code head} relative to its merge-base with
     * {@code base} ({@code git diff --name-only base...head}). Read-only —
     * touches neither the index nor the working tree.
     *
     * @param dir  the repository root directory
     * @param base the base ref (e.g., {@code "main"})
     * @param head the head ref (e.g., a feature branch)
     * @return changed file paths; empty if none or on error
     */
    public static List<String> changedFiles(File dir, String base, String head) {
        try {
            String output = capture(dir, "git", "diff", "--name-only",
                    base + "..." + head);
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
     * Read the configured URL for a named git remote, e.g. {@code origin}.
     * Returns empty when the remote does not exist on this clone.
     *
     * @param dir    the repository root directory
     * @param remote the remote name (typically {@code origin})
     * @return the URL configured for the remote, empty when absent
     */
    public static java.util.Optional<String> remoteUrl(File dir, String remote) {
        try {
            String url = capture(dir, "git", "remote", "get-url", remote);
            return url.isEmpty()
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(url);
        } catch (MojoException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Count commits ahead of and behind the current branch's upstream
     * tracking branch ({@code @{u}}). Returns empty when the branch
     * has no upstream configured (no {@code branch.<name>.remote}),
     * which is common in fresh clones before {@code git push -u}.
     *
     * @param dir the repository root directory
     * @return {@code [ahead, behind]} commit counts; empty when no
     *         upstream tracking branch is configured or git fails
     */
    public static java.util.Optional<int[]> aheadBehindUpstream(File dir) {
        try {
            // --left-right with HEAD...@{u} returns one line per commit;
            // --count collapses into "ahead\tbehind".
            String output = capture(dir, "git", "rev-list",
                    "--left-right", "--count", "HEAD...@{u}");
            if (output.isEmpty()) return java.util.Optional.empty();
            String[] parts = output.split("\\s+");
            if (parts.length != 2) return java.util.Optional.empty();
            return java.util.Optional.of(new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1])});
        } catch (MojoException | NumberFormatException e) {
            return java.util.Optional.empty();
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
     * <p>Passes {@code -c maintenance.auto=false}: git 2.47+ otherwise
     * spawns a <em>detached</em> {@code maintenance run --auto} after
     * the fetch — a background git process that can still hold object
     * and ref locks while the plugin's next git command runs against
     * the same repository (ike-issues#636). The repository's own
     * maintenance policy is unaffected; only the plugin's fetches opt
     * out of triggering it.
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @throws MojoException if the git command fails
     */
    public static void fetch(File dir, Log log) throws MojoException {
        run(dir, log, null, "git", "-c", "maintenance.auto=false",
                "fetch", "--all", "--quiet");
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
     * Commit the currently-staged changes with the given message via
     * {@code git commit -F -} (message piped on stdin, no shell quoting
     * issues, no argument-length limits). Does not stage — callers
     * stage first via {@link #addAll} or per-path {@code git add}.
     * Sets {@code IKE_VCS_CONTEXT} to bypass the pre-commit hook.
     *
     * @param dir     the repository root directory
     * @param log     Maven logger
     * @param message the commit message; must not be {@code null} or blank
     * @throws MojoException if the git command fails
     */
    public static void commit(File dir, Log log, String message)
            throws MojoException {
        commitWithStdin(dir, log, message, "git", "commit", "-F", "-");
    }

    /**
     * Alias for {@link #commit(File, Log, String)}; retained for callers
     * that emphasize the "files are already staged" reading. Editor-mode
     * (null-message) was removed in #277 — Maven's non-interactive child
     * process cannot open an editor, so the path was never reachable in
     * practice.
     *
     * @param dir     the repository root directory
     * @param log     Maven logger
     * @param message the commit message; must not be {@code null} or blank
     * @throws MojoException if the git command fails
     */
    public static void commitStaged(File dir, Log log, String message)
            throws MojoException {
        commit(dir, log, message);
    }

    /**
     * Commit specific paths in isolation, regardless of what else is in
     * the index. Equivalent to {@code git commit -F - -- <paths>}: the
     * working-tree content of the listed (tracked) paths is committed and
     * nothing else, so a concurrent staged or unstaged change to another
     * file is neither committed nor disturbed.
     *
     * <p>Used by {@link network.ike.plugin.ws.PostMutationSync} to commit
     * the re-derived {@code workspace.yaml} on its own
     * (IKE-Network/ike-issues#774). Sets {@code IKE_VCS_CONTEXT} to bypass
     * the pre-commit hook.
     *
     * @param dir     the repository root directory
     * @param log     Maven logger
     * @param message the commit message; must not be {@code null} or blank
     * @param paths   the paths to commit, relative to {@code dir}
     * @throws MojoException if the git command fails
     */
    public static void commitPaths(File dir, Log log, String message,
                                   String... paths) throws MojoException {
        List<String> command = new ArrayList<>(
                List.of("git", "commit", "-F", "-", "--"));
        command.addAll(List.of(paths));
        commitWithStdin(dir, log, message, command.toArray(new String[0]));
    }

    /**
     * Stage all files.
     *
     * <p>{@code git add -A} is idempotent — a failed attempt leaves no
     * partial state and re-running converges on the same index — so it
     * is routed through {@link #captureIdempotentMutation} and survives
     * a transient {@code index.lock} collision with a concurrent
     * process (ike-issues#636).
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @throws MojoException if the git command fails on both attempts
     */
    public static void addAll(File dir, Log log) throws MojoException {
        log.debug("» git add -A");
        captureIdempotentMutation(dir, "git", "add", "-A");
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
     * Fast-forward-only merge. Used to advance the currently-checked-out
     * branch to a ref that is a strict descendant; fails if a real merge
     * (or rebase) would be required.
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @param ref the ref to fast-forward to
     * @throws MojoException if the merge is not fast-forwardable or git fails
     */
    public static void mergeFfOnly(File dir, Log log, String ref)
            throws MojoException {
        runWithContext(dir, log, "git", "merge", "--ff-only", ref);
    }

    /**
     * Abort an in-progress merge ({@code git merge --abort}). Used as a
     * cleanup safety net after a merge attempt fails unexpectedly. Does
     * not throw if no merge is in progress.
     *
     * @param dir the repository root directory
     * @param log Maven logger
     */
    public static void mergeAbortQuiet(File dir, Log log) {
        try {
            run(dir, log, null, "git", "merge", "--abort");
        } catch (MojoException e) {
            // No merge in progress, or already aborted — non-fatal here.
        }
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
     * Check whether the remote-tracking ref {@code refs/remotes/<remote>/<branch>}
     * exists in the local repository. Distinct from {@link #remoteSha}: this
     * asks what the local clone can <em>see</em>, not what exists on the
     * remote. A clone created with a narrowed fetch refspec (e.g.
     * {@code git clone --single-branch}) can have the branch present on the
     * remote yet unresolvable locally — the silent-wrong-comparison hazard of
     * IKE-Network/ike-issues#947.
     *
     * @param dir    the repository root directory
     * @param remote the remote name (e.g., "origin")
     * @param branch the branch name to test
     * @return true if the remote-tracking ref resolves locally
     */
    public static boolean remoteTrackingExists(File dir, String remote, String branch) {
        try {
            String output = capture(dir, "git", "rev-parse", "--verify",
                    "--quiet", "refs/remotes/" + remote + "/" + branch);
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
    /**
     * Whether {@code branch}'s tip TREE equals the tree of some recent
     * commit on {@code targetRef} — the squash-merge detector of
     * IKE-Network/ike-issues#946. A squash commit is not an ancestor
     * relation (so {@code git branch --merged} never sees it) and its
     * single patch-id rarely matches any individual feature commit (so
     * {@code git cherry} misses it too); but the feature-finish flow
     * lands exactly the feature tip's tree on the target, so tree
     * equivalence identifies content-landed branches reliably.
     *
     * @param dir       the repository root directory
     * @param branch    the feature branch to test
     * @param targetRef the target ref whose recent commits to scan
     *                  (e.g. {@code "origin/main"})
     * @param limit     how many recent target commits to compare against
     * @return true when the branch tip's tree appears on the target
     */
    public static boolean branchTreeLandedOn(File dir, String branch,
                                             String targetRef, int limit) {
        try {
            String branchTree = capture(dir,
                    "git", "rev-parse", branch + "^{tree}");
            String targetTrees = capture(dir,
                    "git", "log", "-" + limit, "--format=%T", targetRef);
            return targetTrees.lines().anyMatch(t -> t.equals(branchTree));
        } catch (MojoException e) {
            return false;
        }
    }

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
     * <p>Passes {@code -c maintenance.auto=false} so the fetch never
     * spawns a detached background {@code maintenance run --auto} that
     * could race the plugin's subsequent git commands — see
     * {@link #fetch(File, Log)} and ike-issues#636.
     *
     * @param dir    the repository root directory
     * @param log    Maven logger
     * @param remote the remote name
     * @param ref    the ref to fetch
     * @throws MojoException if the git command fails
     */
    public static void fetchRef(File dir, Log log, String remote, String ref)
            throws MojoException {
        run(dir, log, null, "git", "-c", "maintenance.auto=false",
                "fetch", remote, ref + ":" + ref);
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
     * Record a branch switch this goal just performed in {@code dir} as a
     * VCS action, exactly as the state-writing goals do, so the VCS-bridge
     * sync in subsequent goals carries the new branch forward instead of
     * restoring the branch the state file remembered
     * (IKE-Network/ike-issues#903, #904). No-op for repositories without an
     * {@code .ike/} directory — the bridge ignores those entirely, so there
     * is nothing to record and no directory to invent.
     *
     * @param dir the repository root directory whose branch was switched
     * @param log Maven logger
     * @throws MojoException if writing the state file fails
     */
    public static void recordSwitch(File dir, Log log) throws MojoException {
        if (!VcsState.isIkeManaged(dir.toPath())) {
            return;
        }
        writeVcsState(dir, VcsState.Action.SWITCH);
        log.info("  Recorded branch alignment in .ike/vcs-state (action=switch).");
    }

    /**
     * Refresh a stale {@code .ike/vcs-state} whose recorded branch disagrees
     * with the current checkout, when a goal has just confirmed the checkout
     * as the aligned branch. Without the refresh, the bridge sync in the next
     * goal would switch the checkout AWAY from the branch the goal just
     * confirmed (IKE-Network/ike-issues#903, #904). A missing state file, or
     * one already naming the current branch, is left untouched — a clean
     * no-op stays a no-op, and this never invents bridge state.
     *
     * @param dir the repository root directory whose checkout was confirmed
     * @param log Maven logger
     * @return {@code true} when the state file was refreshed
     * @throws MojoException if reading git state or writing the file fails
     */
    public static boolean refreshStaleBranchState(File dir, Log log)
            throws MojoException {
        Optional<VcsState> state = VcsState.readFrom(dir.toPath());
        if (state.isEmpty()) {
            return false;
        }
        String current = currentBranch(dir);
        if (state.get().branch().equals(current)) {
            return false;
        }
        log.info("  Refreshing stale .ike/vcs-state (recorded '"
                + state.get().branch() + "', checkout on '" + current + "').");
        writeVcsState(dir, VcsState.Action.SWITCH);
        return true;
    }

    /**
     * Check whether the local HEAD has fallen behind the VCS state
     * file written by a coordinated workspace operation. Returns
     * {@code false} when the state file is simply stale relative to
     * later local commits — this is the common case after
     * {@code git commit --amend} or any subsequent commit that didn't
     * route through a {@code ws:*} goal (ike-issues#232).
     *
     * <p>Decision table:
     * <ul>
     *   <li>No state file → {@code false} (nothing to sync against).</li>
     *   <li>Branch mismatch → {@code true} (state file expects a
     *       different branch).</li>
     *   <li>{@code state.sha == localSha} → {@code false}.</li>
     *   <li>{@code state.sha} is a strict ancestor of {@code localSha}
     *       → {@code false} (state file is stale, but HEAD is just
     *       ahead — no cross-machine pull needed).</li>
     *   <li>Anything else (state.sha unknown, descendant of HEAD, or
     *       diverged) → {@code true} (genuine catch-up required).</li>
     * </ul>
     *
     * @param dir the repository root directory
     * @return {@code true} when {@code sync} should run, {@code false}
     *         when the working tree is already current
     * @throws MojoException if reading basic git state fails
     */
    public static boolean needsSync(File dir) throws MojoException {
        Optional<VcsState> state = VcsState.readFrom(dir.toPath());
        if (state.isEmpty()) {
            return false;
        }
        VcsState s = state.get();
        String localBranch = currentBranch(dir);
        if (!s.branch().equals(localBranch)) {
            return true;
        }
        String localSha = headSha(dir);
        if (s.sha().equals(localSha)) {
            return false;
        }
        // Common case after a routine local commit: state.sha is an
        // ancestor of HEAD, so the state file is just stale relative to
        // post-ws:commit-publish work. No sync needed.
        try {
            if (isAncestor(dir, s.sha(), localSha)) {
                return false;
            }
        } catch (MojoException e) {
            // state.sha unknown locally — could be a commit pushed
            // from another machine that we haven't fetched yet.
            // Treat as needing sync.
        }
        return true;
    }

    /**
     * Result of {@link #sync(File, Log)} / {@link #catchUp(File, Log)}: whether
     * a stale-but-safe {@code .ike/vcs-state} checkpoint was self-healed, and a
     * human-readable note for a goal's markdown report (ike-issues#819).
     *
     * @param selfHealed {@code true} when a stale checkpoint was rewritten to
     *                   the current HEAD because origin-parity was already
     *                   independently confirmed
     * @param note       text worth surfacing in a caller's report, or
     *                   {@code null} when there is nothing to report
     */
    public record SyncOutcome(boolean selfHealed, String note) {
        static final SyncOutcome NONE = new SyncOutcome(false, null);
    }

    /**
     * Synchronize local git state to match the VCS state file.
     * Fetches from all remotes, switches branch if needed, and soft-resets.
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @return the sync outcome — see {@link SyncOutcome}
     * @throws MojoException if a git command or state file read fails
     */
    public static SyncOutcome sync(File dir, Log log) throws MojoException {
        Optional<VcsState> stateOpt = VcsState.readFrom(dir.toPath());
        if (stateOpt.isEmpty()) {
            log.info("  No VCS state file — nothing to sync.");
            return SyncOutcome.NONE;
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
                    return SyncOutcome.NONE;
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
            return SyncOutcome.NONE;
        }

        // Evaluate the relationship between local branch tip and origin
        // before touching HEAD. An unconditional reset-to-origin would
        // silently discard unpushed local commits (#144).
        String localSha = headSha(dir);
        String remoteShaValue = remoteRef.get();

        // Whether origin-parity is proven by the time we reach the
        // state-file comparison below — used to tell a merely-stale
        // bookkeeping checkpoint apart from a genuinely missing push
        // (ike-issues#819).
        boolean originParityConfirmed;
        if (localSha.equals(remoteShaValue)) {
            log.info("  Already at origin/" + state.branch()
                    + " — no reset needed.");
            originParityConfirmed = true;
        } else if (isAncestor(dir, remoteShaValue, localSha)) {
            // Local is strictly ahead of origin. Preserve the unpushed
            // commits — the caller (usually ws:push) will push them.
            // Origin parity is NOT confirmed here: if the state file
            // also disagrees below, that's still a real signal worth
            // a warning (e.g. a missing push from another machine).
            log.info("  Local " + state.branch()
                    + " is ahead of origin — keeping unpushed commits.");
            originParityConfirmed = false;
        } else if (isAncestor(dir, localSha, remoteShaValue)) {
            // Local is strictly behind origin. Fast-forward is safe
            // (equivalent to git pull --ff-only).
            log.info("  Fast-forwarding " + state.branch() + " to origin.");
            resetSoft(dir, log, "origin/" + state.branch());
            originParityConfirmed = true;
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
        if (newSha.equals(state.sha())) {
            log.info("  HEAD now matches state file: " + newSha);
            return SyncOutcome.NONE;
        }
        // After fast-forward / no-op, HEAD may legitimately sit ahead
        // of state.sha — the state file is just stale relative to
        // later local commits, not a sign of a missing push
        // (ike-issues#232). Only warn when state.sha is genuinely
        // unreachable from HEAD.
        try {
            if (isAncestor(dir, state.sha(), newSha)) {
                log.info("  HEAD (" + newSha + ") is ahead of state file ("
                        + state.sha() + ") — state file is stale, no action needed.");
                return SyncOutcome.NONE;
            }
        } catch (MojoException ignored) {
            // state.sha unreachable locally — fall through below.
        }

        if (originParityConfirmed) {
            // The state file disagrees, but we already proved origin/<branch>
            // matches HEAD above — this is stale local bookkeeping (e.g. a
            // rebase/amend rewrote the checkpoint commit, or a prior run
            // found nothing to push and never refreshed it), not a missing
            // push. Say so plainly, and self-heal so it stops recurring.
            String note = "Local bookkeeping checkpoint (.ike/vcs-state) was stale "
                    + "relative to " + state.branch() + "'s history, but origin/"
                    + state.branch() + " already matched HEAD (" + newSha
                    + ") — nothing to push, no action needed.";
            log.info("  " + note);
            try {
                writeVcsState(dir, VcsState.Action.SYNC);
                return new SyncOutcome(true, note);
            } catch (MojoException e) {
                log.debug("  Could not refresh .ike/vcs-state after bridge sync: "
                        + e.getMessage());
                return new SyncOutcome(false, note + " (checkpoint refresh failed — see debug log)");
            }
        }

        log.warn("  HEAD after sync (" + newSha + ") does not match state file ("
                + state.sha() + ").");
        log.warn("  The push from " + state.machine() + " may not have completed.");
        log.warn("  Push from " + state.machine() + " first, then retry sync.");

        return SyncOutcome.NONE;
    }

    /**
     * Catch-up preamble: sync if needed, otherwise report that we're current.
     * Used by all goals that modify state (commit, push, feature-start, etc.).
     *
     * @param dir the repository root directory
     * @param log Maven logger
     * @return the sync outcome — see {@link SyncOutcome}; {@link SyncOutcome#NONE}
     *         when nothing needed syncing
     * @throws MojoException if sync fails
     */
    public static SyncOutcome catchUp(File dir, Log log) throws MojoException {
        if (!VcsState.isIkeManaged(dir.toPath())) {
            return SyncOutcome.NONE;
        }
        if (needsSync(dir)) {
            log.info("  VCS state is behind — catching up...");
            return sync(dir, log);
        }
        return SyncOutcome.NONE;
    }

    // ── Internal helpers ─────────────────────────────────────────

    /**
     * Test seam (ike-issues#691): intercepts each git subprocess before it runs.
     * Throwing {@link MojoException} simulates a command failure WITHOUT executing
     * the real subprocess; returning normally lets the command proceed. Default no-op.
     */
    @FunctionalInterface
    public interface CommandInterceptor {
        void beforeCommand(File workDir, String[] command) throws MojoException;
    }

    private static volatile CommandInterceptor commandInterceptor = (dir, cmd) -> { };

    /** Install a command interceptor (tests only). Pass {@code null} to reset to the default no-op. */
    static void setCommandInterceptor(CommandInterceptor interceptor) {
        commandInterceptor = (interceptor != null) ? interceptor : (dir, cmd) -> { };
    }

    /**
     * Run a command with output routed through the Maven logger.
     * Optionally sets environment variables.
     */
    private static void run(File workDir, Log log, Map<String, String> env,
                            String... command) throws MojoException {
        log.debug("» " + String.join(" ", command));
        commandInterceptor.beforeCommand(workDir, command);
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true);
            if (env != null) {
                pb.environment().putAll(env);
            }
            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(
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
        commandInterceptor.beforeCommand(workDir, command);
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true);
            pb.environment().put(IKE_VCS_CONTEXT, CONTEXT_VALUE);
            Process proc = pb.start();

            // Write message to stdin, then close to signal EOF
            try (OutputStream out = proc.getOutputStream()) {
                out.write(message.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            // Consume stdout/stderr
            String output;
            try (BufferedReader reader = new BufferedReader(
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
     *
     * <p>On non-zero exit the thrown {@link MojoException} carries the
     * full command, the working directory, the exit code, <em>and</em>
     * git's stderr — so a failure is self-diagnosing instead of a bare
     * exit code (ike-issues#602). Both stdout and stderr are drained
     * before {@code waitFor()} so the subprocess never blocks on a full
     * pipe.
     *
     * @param workDir the working directory for the command
     * @param command the command and its arguments
     * @return the captured stdout, trimmed
     * @throws MojoException if the command cannot be run or exits non-zero
     */
    private static String capture(File workDir, String... command)
            throws MojoException {
        try {
            Process proc = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(false)
                    .start();
            String stdout;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(),
                            StandardCharsets.UTF_8))) {
                stdout = reader.lines().collect(Collectors.joining("\n")).trim();
            }
            String stderr = new String(
                    proc.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new MojoException(
                        String.join(" ", command) + " in " + workDir
                                + " failed (exit " + exit + ")"
                                + (stderr.isEmpty() ? "" : ": " + stderr));
            }
            return stdout;
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to execute " + String.join(" ", command)
                            + " in " + workDir + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoException(
                    "Interrupted while executing " + String.join(" ", command)
                            + " in " + workDir, e);
        }
    }

    /**
     * Run a read-only git query through {@link #capture}, retrying once
     * after a short backoff if it fails.
     *
     * <p>The read-only queries that drive {@code ws:push} and
     * {@code ws:sync} — {@code rev-parse}, {@code branch --show-current},
     * {@code ls-remote} — are idempotent, so a transient per-repository
     * collision (a concurrent index or ref read, a momentary lock) can be
     * retried safely rather than failing the whole repository
     * (ike-issues#602).
     *
     * @param workDir the repository directory
     * @param command the git read command and its arguments
     * @return the captured stdout, trimmed
     * @throws MojoException if both attempts fail — carrying the last
     *                       attempt's self-diagnosing message
     */
    private static String captureRead(File workDir, String... command)
            throws MojoException {
        return captureWithRetry(workDir, command);
    }

    /**
     * Run an idempotent git mutation through {@link #capture}, retrying
     * once after a short backoff if it fails.
     *
     * <p>{@code git add -A} is the canonical caller: git's index write
     * is atomic (staged in {@code index.lock}, then renamed into place),
     * so a failed attempt leaves no partial state and re-running
     * converges on the same result. That makes it safe to retry across
     * a transient {@code index.lock} collision with a concurrent
     * process — an IDE refresh, an agent's status query
     * (ike-issues#636).
     *
     * <p>Genuinely non-idempotent mutations ({@code commit}, {@code tag},
     * {@code push}) must never be routed here — after an ambiguous
     * failure a retry could apply them twice. They call {@link #run} or
     * {@link #capture} directly.
     *
     * @param workDir the repository directory
     * @param command the git mutation command and its arguments
     * @return the captured stdout, trimmed
     * @throws MojoException if both attempts fail — carrying the last
     *                       attempt's self-diagnosing message
     */
    private static String captureIdempotentMutation(File workDir, String... command)
            throws MojoException {
        return captureWithRetry(workDir, command);
    }

    /**
     * Retry engine behind {@link #captureRead} and
     * {@link #captureIdempotentMutation}: the original call plus one
     * retry after a {@link #TRANSIENT_RETRY_BACKOFF_MILLIS} backoff.
     *
     * @param workDir the repository directory
     * @param command the git command and its arguments
     * @return the captured stdout, trimmed
     * @throws MojoException if both attempts fail — carrying the last
     *                       attempt's self-diagnosing message
     */
    private static String captureWithRetry(File workDir, String... command)
            throws MojoException {
        MojoException last = null;
        for (int attempt = 1; attempt <= TRANSIENT_RETRY_ATTEMPTS; attempt++) {
            try {
                return capture(workDir, command);
            } catch (MojoException e) {
                last = e;
                if (attempt < TRANSIENT_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(TRANSIENT_RETRY_BACKOFF_MILLIS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }
}
