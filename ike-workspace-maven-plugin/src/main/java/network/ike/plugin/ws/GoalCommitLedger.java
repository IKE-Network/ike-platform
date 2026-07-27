package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The committed-work ledger of a publish goal (IKE-Network/ike-issues#954).
 *
 * <p>A publish goal that owns its commits (#919, #780) ends by showing the
 * operator exactly what it committed — not a working-tree status check,
 * which is empty by design once every goal-authored change is committed.
 * The ledger derives that inventory from git truth: the caller records each
 * repository's {@code HEAD} immediately after the goal's preflight proved
 * the tree free of uncommitted changes ({@link #baselineSha baselineSha}),
 * lets the goal run, then {@link #collect collect}s
 * {@code git log --name-status baseline..HEAD} per repository. Because the
 * preflight guaranteed a committed baseline, every commit in the range is
 * provably goal-authored — including commits made by delegated subprocesses
 * (the per-subproject {@code ike:scaffold-publish} fan-out) that the
 * workspace mojo cannot otherwise observe.
 */
public final class GoalCommitLedger {

    private GoalCommitLedger() {}

    /**
     * One goal-authored commit.
     *
     * @param sha     the 8-character short SHA
     * @param subject the commit subject line
     * @param files   the changed files as {@code "<status> <path>"} entries
     *                (e.g. {@code "M pom.xml"}); renames and copies as
     *                {@code "<status> <old> → <new>"}
     */
    public record Commit(String sha, String subject, List<String> files) {}

    /**
     * One repository's ledger entry.
     *
     * @param label   the repository label shown in reports
     * @param commits the goal-authored commits, newest first ({@code git log}
     *                order)
     * @param residue stripped porcelain status lines left uncommitted after
     *                the run — empty on a healthy publish (#919)
     */
    public record RepoChanges(String label, List<Commit> commits,
                              List<String> residue) {

        /**
         * Whether the goal authored at least one commit in this repository.
         *
         * @return {@code true} when {@link #commits} is non-empty
         */
        public boolean hasCommits() {
            return !commits.isEmpty();
        }

        /**
         * Whether files were left uncommitted in this repository.
         *
         * @return {@code true} when {@link #residue} is non-empty
         */
        public boolean hasResidue() {
            return !residue.isEmpty();
        }
    }

    /**
     * The repository's current {@code HEAD} short SHA, for use as the
     * ledger baseline — or {@code null} for a repository with no commits
     * yet (an unborn {@code HEAD}), in which case the whole history at
     * collect time is goal-authored.
     *
     * @param dir the repository root directory
     * @return the baseline short SHA, or {@code null} when the repository
     *         has no commits yet
     */
    public static String baselineSha(File dir) {
        try {
            return VcsOperations.headSha(dir);
        } catch (MojoException e) {
            return null;
        }
    }

    /**
     * Collect one repository's ledger entry: the goal-authored commits in
     * {@code baseline..HEAD} (each with subject, short SHA, and changed
     * files) plus any uncommitted residue. Collection is best-effort — a
     * repository whose log cannot be read (e.g. {@code HEAD} still unborn)
     * reports no commits rather than failing the goal's report.
     *
     * @param label       the repository label shown in reports
     * @param dir         the repository root directory
     * @param baselineSha the {@code HEAD} recorded by {@link #baselineSha}
     *                    before the goal ran; {@code null} means the
     *                    repository had no commits then, so the whole
     *                    history is goal-authored
     * @return the repository's ledger entry
     */
    public static RepoChanges collect(String label, File dir,
                                      String baselineSha) {
        String range = baselineSha == null ? "HEAD" : baselineSha + "..HEAD";
        List<Commit> commits;
        try {
            commits = parseNameStatusLog(
                    VcsOperations.nameStatusLog(dir, range));
        } catch (MojoException e) {
            commits = List.of();
        }
        List<String> residue = VcsOperations.uncommittedStatus(dir).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        return new RepoChanges(label, commits, residue);
    }

    /**
     * The commit-header marker of
     * {@link VcsOperations#nameStatusLog VcsOperations.nameStatusLog}:
     * {@code commit} followed by {@code U+0001}. No name-status line can
     * collide with it (they open with a status letter and a tab), and —
     * unlike a bare leading {@code U+0001} — it survives the trimming the
     * subprocess capture applies to git's output.
     */
    static final String COMMIT_MARKER = "commit" + (char) 1;

    /**
     * Parse the output of
     * {@link VcsOperations#nameStatusLog VcsOperations.nameStatusLog}: each
     * commit opens with a {@link #COMMIT_MARKER}-prefixed
     * {@code <short-sha><TAB><subject>} header line, followed by its
     * name-status entries ({@code M<TAB>path}, {@code R100<TAB>old<TAB>new},
     * …). Blank lines are ignored. Pure — unit-testable without git.
     *
     * @param raw the raw log output
     * @return the parsed commits, in log order (newest first)
     */
    static List<Commit> parseNameStatusLog(String raw) {
        List<Commit> commits = new ArrayList<>();
        String sha = null;
        String subject = null;
        List<String> files = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            if (line.startsWith(COMMIT_MARKER)) {
                if (sha != null) {
                    commits.add(new Commit(sha, subject, List.copyOf(files)));
                }
                String[] header = line.substring(COMMIT_MARKER.length()).split("\t", 2);
                sha = header[0];
                subject = header.length > 1 ? header[1] : "";
                files = new ArrayList<>();
            } else if (sha != null && !line.isBlank()) {
                files.add(formatNameStatus(line));
            }
        }
        if (sha != null) {
            commits.add(new Commit(sha, subject, List.copyOf(files)));
        }
        return List.copyOf(commits);
    }

    /**
     * Render one {@code git log --name-status} entry for display: the
     * status letter (similarity scores stripped) and the path, renames and
     * copies as {@code old → new}.
     *
     * @param line one raw name-status line
     * @return the display form, e.g. {@code "M pom.xml"} or
     *         {@code "R a.txt → b.txt"}
     */
    private static String formatNameStatus(String line) {
        String[] parts = line.split("\t");
        if (parts.length >= 3) {
            return parts[0].substring(0, 1) + " " + parts[1] + " → "
                    + parts[2];
        }
        if (parts.length == 2) {
            return parts[0].substring(0, 1) + " " + parts[1];
        }
        return line.strip();
    }

    /**
     * Render the repositories' goal-authored commits as nested Markdown
     * bullets — repository, then each commit's short SHA and subject, then
     * its changed files. Repositories without commits are omitted.
     *
     * @param repos the collected ledger entries
     * @return Markdown bullet lines, empty when no repository has commits
     */
    static String commitsToMarkdown(List<RepoChanges> repos) {
        StringBuilder md = new StringBuilder();
        for (RepoChanges repo : repos) {
            if (!repo.hasCommits()) {
                continue;
            }
            md.append("- **").append(repo.label()).append("**\n");
            for (Commit commit : repo.commits()) {
                md.append("  - `").append(commit.sha()).append("` ")
                        .append(commit.subject()).append("\n");
                for (String file : commit.files()) {
                    md.append("    - `").append(file).append("`\n");
                }
            }
        }
        return md.toString();
    }

    /**
     * Render the repositories' uncommitted residue as nested Markdown
     * bullets. Repositories without residue are omitted.
     *
     * @param repos the collected ledger entries
     * @return Markdown bullet lines, empty when no repository has residue
     */
    static String residueToMarkdown(List<RepoChanges> repos) {
        StringBuilder md = new StringBuilder();
        for (RepoChanges repo : repos) {
            if (!repo.hasResidue()) {
                continue;
            }
            md.append("- **").append(repo.label()).append("** — ")
                    .append(repo.residue().size()).append(" file(s)\n");
            for (String line : repo.residue()) {
                md.append("  - `").append(line).append("`\n");
            }
        }
        return md.toString();
    }
}
