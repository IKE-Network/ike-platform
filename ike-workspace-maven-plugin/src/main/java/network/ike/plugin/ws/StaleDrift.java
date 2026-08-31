package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Detects <em>time-reversed synced drift</em> in a repository's pending
 * changes (IKE-Network/ike-issues#1082).
 *
 * <p>In a Syncthing-synchronized working set, ref alignment moves a
 * repository's refs without touching the synced tree — by design, the
 * tree is the medium of exchange. When the tree lags the refs, the
 * resulting status delta is time-reversed: <em>old</em> content presents
 * as pending changes. Such a delta reads exactly like WIP to every
 * commit surface, and committing it re-commits history backwards — the
 * 2026-08-31 incident deleted a teammate's day-old work from origin.
 *
 * <p>The discriminator is mechanical: a pending change whose content
 * byte-matches an older committed state of the same path carries no
 * authored content. Per change kind:
 *
 * <ul>
 *   <li><b>Modified</b> — the pending blob equals the path's blob at
 *       some earlier commit that touched the path (a revert to old
 *       content);</li>
 *   <li><b>Deleted</b> — the path's most recent add is itself a recent
 *       commit (the deletion undoes a recent add);</li>
 *   <li><b>Added</b> — the pending blob equals a historical blob of the
 *       same, currently absent, path (an old file re-appearing).</li>
 * </ul>
 *
 * <p>A delta whose <em>every</em> path is stale-shaped is drift, not
 * WIP: {@code ws:commit-publish} refuses it (escapable with
 * {@code -Dallow-stale-drift=true} for a deliberate hand-authored
 * revert). A mixed delta is warned about per path and committed —
 * deliberate reverts alongside novel work are legitimate.
 *
 * <p>All probes are failure-tolerant: a path whose history cannot be
 * read classifies as novel, and callers treat an analysis failure as
 * "no finding" — the guard fails open, like the lease fence, rather
 * than wedging commits.
 */
final class StaleDrift {

    /**
     * How many recent commits of the repository bound the "recent add"
     * test for deletions. Calibrated against the motivating incident,
     * where the drift spanned 16 and 28 commits in the two affected
     * repositories; 200 leaves generous headroom without scanning whole
     * histories.
     */
    static final int REPO_WINDOW = 200;

    /**
     * How many path-touching commits are probed when matching a
     * modified or added path's pending blob against history. A path's
     * blob only changes at commits that touch it, so this bounds the
     * per-path probe count, not a time window; the incident file had 15
     * touching commits in its whole history.
     */
    static final int PATH_HISTORY_LIMIT = 32;

    private StaleDrift() {
    }

    /** How a pending change alters its path. */
    enum ChangeKind {
        /** Content change to a tracked path. */
        MODIFIED,
        /** Path absent at HEAD, present in the pending change. */
        ADDED,
        /** Path present at HEAD, removed by the pending change. */
        DELETED
    }

    /**
     * One pending change, normalized from either the index or the
     * working tree.
     *
     * @param kind how the change alters the path
     * @param path the repo-relative path
     * @param blob the pending content's blob SHA, or {@code null} for
     *             {@link ChangeKind#DELETED}
     */
    record Change(ChangeKind kind, String path, String blob) {}

    /**
     * A stale-shaped change and the historical state it matches.
     *
     * @param change         the pending change
     * @param matchedCommit  the commit whose state the change restores
     *                       (for deletions: the recent add the deletion
     *                       undoes)
     * @param matchedSubject that commit's subject line, best-effort
     *                       ({@code ""} when unreadable)
     */
    record Finding(Change change, String matchedCommit, String matchedSubject) {}

    /**
     * The classification of a repository's pending changes.
     *
     * @param stale changes that byte-match an older committed state
     * @param novel changes carrying content history has never held
     */
    record Analysis(List<Finding> stale, List<Change> novel) {

        /** An analysis with no pending changes at all. */
        static final Analysis EMPTY = new Analysis(List.of(), List.of());

        /**
         * Whether every pending change is stale-shaped — the refusal
         * condition: such a delta holds no authored content.
         *
         * @return {@code true} when there is at least one stale change
         *         and no novel one
         */
        boolean whollyStale() {
            return !stale.isEmpty() && novel.isEmpty();
        }

        /**
         * Whether any pending change is stale-shaped — the warning
         * condition for mixed deltas.
         *
         * @return {@code true} when at least one change is stale
         */
        boolean hasStale() {
            return !stale.isEmpty();
        }
    }

    /**
     * Classifies a repository's <em>staged</em> changes — what
     * {@code git commit} would record right now.
     *
     * @param dir the repository root directory
     * @return the classification; {@link Analysis#EMPTY} when nothing
     *         is staged
     * @throws MojoException when the staged state itself cannot be read
     *         (per-path history probe failures classify as novel
     *         instead)
     */
    static Analysis analyzeStaged(File dir) throws MojoException {
        List<Change> changes = new ArrayList<>();
        // --no-abbrev: --raw output abbreviates blob ids by default, and
        // the classification compares them for equality with rev-parse
        // output, which is always full-length.
        String raw = capture(dir, "git", "diff", "--cached", "--raw",
                "--no-abbrev", "--no-renames")
                .orElseThrow(() -> new MojoException(
                        "git diff --cached --raw failed in " + dir));
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            // :oldmode newmode oldsha newsha S\tpath
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String path = line.substring(tab + 1);
            String[] meta = line.substring(0, tab).trim().split("\\s+");
            if (meta.length < 5) {
                continue;
            }
            String newSha = meta[3];
            char status = meta[4].charAt(0);
            switch (status) {
                case 'D' -> changes.add(
                        new Change(ChangeKind.DELETED, path, null));
                case 'A' -> changes.add(
                        new Change(ChangeKind.ADDED, path, newSha));
                // M (content) and T (typechange) both carry new content.
                default -> changes.add(
                        new Change(ChangeKind.MODIFIED, path, newSha));
            }
        }
        return analyze(dir, changes);
    }

    /**
     * Classifies a repository's <em>working-tree</em> changes — what
     * {@code ws:commit-publish}'s default sweep would stage — for the
     * read-only draft preview. Untracked files classify as added,
     * deletions on either side of the index as deleted, everything
     * else as modified; pending content is hashed from the working
     * tree.
     *
     * @param dir the repository root directory
     * @return the classification; {@link Analysis#EMPTY} when the tree
     *         is clean
     * @throws MojoException when the working-tree status itself cannot
     *         be read
     */
    static Analysis analyzeWorktree(File dir) throws MojoException {
        // Untrimmed: porcelain's two status columns are positional, and
        // the first line's leading space (e.g. " M path") is data.
        String porcelain = captureRaw(dir, "git", "status", "--porcelain")
                .orElseThrow(() -> new MojoException(
                        "git status --porcelain failed in " + dir));
        List<Change> changes = new ArrayList<>();
        for (String line : porcelain.split("\n")) {
            if (line.length() < 4) {
                continue;
            }
            char index = line.charAt(0);
            char worktree = line.charAt(1);
            String rawPath = line.substring(3);
            int arrow = rawPath.indexOf(" -> ");
            String path = arrow >= 0 ? rawPath.substring(arrow + 4) : rawPath;
            if (index == 'D' || worktree == 'D') {
                changes.add(new Change(ChangeKind.DELETED, path, null));
            } else if (index == '?' || index == 'A') {
                hashOf(dir, path).ifPresent(blob -> changes.add(
                        new Change(ChangeKind.ADDED, path, blob)));
            } else {
                hashOf(dir, path).ifPresent(blob -> changes.add(
                        new Change(ChangeKind.MODIFIED, path, blob)));
            }
        }
        return analyze(dir, changes);
    }

    /**
     * Hashes a working-tree file as git would, tolerantly: a path that
     * cannot be hashed (vanished mid-scan, permission) is simply left
     * out of the analysis.
     *
     * @param dir  the repository root directory
     * @param path the repo-relative path
     * @return the blob SHA, or empty when unhashable
     */
    private static Optional<String> hashOf(File dir, String path) {
        return capture(dir, "git", "hash-object", "--", path)
                .filter(sha -> !sha.isBlank());
    }

    /**
     * Classifies pending changes against the repository's history.
     *
     * @param dir     the repository root directory
     * @param changes the pending changes to classify
     * @return the classification; {@link Analysis#EMPTY} when
     *         {@code changes} is empty
     */
    static Analysis analyze(File dir, List<Change> changes) {
        if (changes.isEmpty()) {
            return Analysis.EMPTY;
        }
        // The recent-commit window for the deletion test. HEAD itself is
        // included deliberately: deleting a path HEAD just added undoes
        // HEAD, the most sharply time-reversed shape there is.
        Set<String> recentCommits = new HashSet<>(
                captureLines(dir, "git", "rev-list",
                        "-n", String.valueOf(REPO_WINDOW), "HEAD"));

        List<Finding> stale = new ArrayList<>();
        List<Change> novel = new ArrayList<>();
        for (Change change : changes) {
            Optional<String> matched = switch (change.kind()) {
                case MODIFIED, ADDED -> matchHistoricalBlob(dir, change);
                case DELETED -> matchRecentAdd(dir, change, recentCommits);
            };
            if (matched.isPresent()) {
                stale.add(new Finding(change, matched.get(),
                        subjectOf(dir, matched.get())));
            } else {
                novel.add(change);
            }
        }
        return new Analysis(List.copyOf(stale), List.copyOf(novel));
    }

    /**
     * Finds a historical commit at which the path held exactly the
     * pending blob. Only commits that touched the path can change its
     * blob, so the probe walks the path's own history; the commit at
     * the head of that walk is skipped for modifications (its blob is
     * the current content the pending change differs from).
     *
     * @param dir    the repository root directory
     * @param change the modified or added change
     * @return the matching commit SHA, or empty when the content is
     *         novel (or history is unreadable)
     */
    private static Optional<String> matchHistoricalBlob(File dir, Change change) {
        List<String> touching = captureLines(dir, "git", "log",
                "-n", String.valueOf(PATH_HISTORY_LIMIT),
                "--format=%H", "HEAD", "--", change.path());
        boolean skipFirst = change.kind() == ChangeKind.MODIFIED;
        for (String commit : touching) {
            if (skipFirst) {
                skipFirst = false;
                continue;
            }
            Optional<String> blobAt = capture(dir, "git", "rev-parse",
                    commit + ":" + change.path());
            if (blobAt.isPresent() && blobAt.get().equals(change.blob())) {
                return Optional.of(commit);
            }
        }
        return Optional.empty();
    }

    /**
     * Tests whether a deletion undoes a recent add: the path's most
     * recent add commit lies inside the repository's recent-commit
     * window. A deletion of a long-established path is an ordinary
     * deletion, not drift.
     *
     * @param dir           the repository root directory
     * @param change        the deleted change
     * @param recentCommits the repository's last {@link #REPO_WINDOW}
     *                      commit SHAs
     * @return the add commit the deletion undoes, or empty
     */
    private static Optional<String> matchRecentAdd(File dir, Change change,
                                                   Set<String> recentCommits) {
        List<String> added = captureLines(dir, "git", "log", "-n", "1",
                "--diff-filter=A", "--format=%H", "HEAD", "--", change.path());
        if (added.isEmpty() || !recentCommits.contains(added.get(0))) {
            return Optional.empty();
        }
        return Optional.of(added.get(0));
    }

    /**
     * Best-effort subject line of a commit, for the finding report.
     *
     * @param dir    the repository root directory
     * @param commit the commit SHA
     * @return the subject, or {@code ""} when unreadable
     */
    private static String subjectOf(File dir, String commit) {
        return capture(dir, "git", "show", "-s", "--format=%s", commit)
                .orElse("");
    }

    /**
     * Formats an analysis's stale findings as report lines, one per
     * finding, naming the historical state each change restores.
     *
     * @param analysis the analysis to format
     * @return one line per stale finding
     */
    static List<String> describeStale(Analysis analysis) {
        List<String> lines = new ArrayList<>();
        for (Finding finding : analysis.stale()) {
            String shortSha = finding.matchedCommit().length() >= 8
                    ? finding.matchedCommit().substring(0, 8)
                    : finding.matchedCommit();
            String verb = switch (finding.change().kind()) {
                case MODIFIED -> "reverts to its state at";
                case ADDED -> "re-adds its content from";
                case DELETED -> "undoes its addition in";
            };
            lines.add(finding.change().path() + " — " + verb + " "
                    + shortSha
                    + (finding.matchedSubject().isEmpty()
                            ? "" : " (" + finding.matchedSubject() + ")"));
        }
        return lines;
    }

    /**
     * Runs a command and captures trimmed stdout, empty on any failure
     * — every history probe is tolerant by construction.
     *
     * @param dir     the working directory
     * @param command the command and arguments
     * @return trimmed stdout, or empty on non-zero exit or I/O failure
     */
    private static Optional<String> capture(File dir, String... command) {
        return captureRaw(dir, command).map(String::trim);
    }

    /**
     * Runs a command and captures stdout verbatim — for output whose
     * leading whitespace is data, like {@code git status --porcelain}
     * status columns. Empty on any failure.
     *
     * @param dir     the working directory
     * @param command the command and arguments
     * @return stdout untrimmed, or empty on non-zero exit or I/O failure
     */
    private static Optional<String> captureRaw(File dir, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(false)
                    .start();
            String stdout = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            // Drain stderr so the subprocess can never block on a full pipe.
            process.getErrorStream().readAllBytes();
            if (process.waitFor() != 0) {
                return Optional.empty();
            }
            return Optional.of(stdout);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Runs a command and captures stdout as lines, empty list on any
     * failure.
     *
     * @param dir     the working directory
     * @param command the command and arguments
     * @return the non-blank stdout lines, or an empty list on failure
     */
    private static List<String> captureLines(File dir, String... command) {
        Optional<String> out = capture(dir, command);
        if (out.isEmpty() || out.get().isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : out.get().split("\n")) {
            if (!line.isBlank()) {
                lines.add(line.trim());
            }
        }
        return lines;
    }
}
