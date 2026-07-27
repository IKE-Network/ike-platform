package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GoalCommitLedger} (IKE-Network/ike-issues#954) — the
 * committed-work ledger that replaced the stale post-publish
 * "Uncommitted changes" checklist. Parse tests are pure; collection tests
 * run against real temporary git repositories.
 */
class GoalCommitLedgerTest {

    @TempDir
    Path tempDir;

    // ── parseNameStatusLog (pure) ─────────────────────────────────────

    @Test
    void parse_listsCommitsWithTheirFiles() {
        String raw = GoalCommitLedger.COMMIT_MARKER + "abc12345\tws:scaffold-publish: commit reconciliation edits\n"
                + "\n"
                + "M\tpom.xml\n"
                + "A\tsrc/new.java\n"
                + GoalCommitLedger.COMMIT_MARKER + "def67890\tinitial\n"
                + "\n"
                + "D\told.txt\n";

        List<GoalCommitLedger.Commit> commits =
                GoalCommitLedger.parseNameStatusLog(raw);

        assertThat(commits).hasSize(2);
        assertThat(commits.getFirst().sha()).isEqualTo("abc12345");
        assertThat(commits.getFirst().subject())
                .isEqualTo("ws:scaffold-publish: commit reconciliation edits");
        assertThat(commits.getFirst().files())
                .containsExactly("M pom.xml", "A src/new.java");
        assertThat(commits.getLast().files()).containsExactly("D old.txt");
    }

    @Test
    void parse_rendersRenamesAsOldArrowNew() {
        String raw = GoalCommitLedger.COMMIT_MARKER + "abc12345\trename pass\n"
                + "\n"
                + "R100\told-name.txt\tnew-name.txt\n";

        List<GoalCommitLedger.Commit> commits =
                GoalCommitLedger.parseNameStatusLog(raw);

        assertThat(commits.getFirst().files())
                .containsExactly("R old-name.txt → new-name.txt");
    }

    @Test
    void parse_emptyInput_yieldsNoCommits() {
        assertThat(GoalCommitLedger.parseNameStatusLog("")).isEmpty();
    }

    // ── baselineSha / collect (real git) ──────────────────────────────

    @Test
    void baselineSha_returnsHeadOfRepoWithCommits() throws Exception {
        initRepo();
        commitFile("pom.xml", "<project/>", "initial");

        String sha = GoalCommitLedger.baselineSha(tempDir.toFile());

        assertThat(sha).isNotNull().hasSize(8);
    }

    @Test
    void baselineSha_unbornHead_returnsNull() throws Exception {
        initRepo();

        assertThat(GoalCommitLedger.baselineSha(tempDir.toFile())).isNull();
    }

    @Test
    void collect_listsOnlyCommitsSinceBaseline() throws Exception {
        initRepo();
        commitFile("pom.xml", "<project>1</project>", "initial");
        String baseline = GoalCommitLedger.baselineSha(tempDir.toFile());

        commitFile("pom.xml", "<project>2</project>",
                "ws:scaffold-publish: commit reconciliation edits");
        commitFile("README.adoc", "= Readme", "scaffold output");

        GoalCommitLedger.RepoChanges changes = GoalCommitLedger.collect(
                "example-repo", tempDir.toFile(), baseline);

        assertThat(changes.hasCommits()).isTrue();
        assertThat(changes.commits()).hasSize(2);
        // git log order: newest first
        assertThat(changes.commits().getFirst().subject())
                .isEqualTo("scaffold output");
        assertThat(changes.commits().getFirst().files())
                .containsExactly("A README.adoc");
        assertThat(changes.commits().getLast().subject())
                .isEqualTo("ws:scaffold-publish: commit reconciliation edits");
        assertThat(changes.commits().getLast().files())
                .containsExactly("M pom.xml");
        assertThat(changes.hasResidue()).isFalse();
    }

    @Test
    void collect_nullBaseline_listsWholeHistory() throws Exception {
        initRepo();
        commitFile("pom.xml", "<project/>", "first ever commit");

        GoalCommitLedger.RepoChanges changes = GoalCommitLedger.collect(
                "example-repo", tempDir.toFile(), null);

        assertThat(changes.commits()).hasSize(1);
        assertThat(changes.commits().getFirst().subject())
                .isEqualTo("first ever commit");
    }

    @Test
    void collect_flagsUncommittedResidue() throws Exception {
        initRepo();
        commitFile("pom.xml", "<project>1</project>", "initial");
        String baseline = GoalCommitLedger.baselineSha(tempDir.toFile());

        Files.writeString(tempDir.resolve("pom.xml"), "<project>2</project>",
                StandardCharsets.UTF_8);

        GoalCommitLedger.RepoChanges changes = GoalCommitLedger.collect(
                "example-repo", tempDir.toFile(), baseline);

        assertThat(changes.hasCommits()).isFalse();
        assertThat(changes.hasResidue()).isTrue();
        assertThat(changes.residue()).containsExactly("M pom.xml");
    }

    // ── Markdown rendering ────────────────────────────────────────────

    @Test
    void commitsToMarkdown_nestsRepoCommitAndFiles() {
        GoalCommitLedger.RepoChanges withCommits =
                new GoalCommitLedger.RepoChanges("komet-bom",
                        List.of(new GoalCommitLedger.Commit("abc12345",
                                "ws:scaffold-publish: commit reconciliation edits",
                                List.of("M pom.xml"))),
                        List.of());
        GoalCommitLedger.RepoChanges without =
                new GoalCommitLedger.RepoChanges("komet",
                        List.of(), List.of());

        String md = GoalCommitLedger.commitsToMarkdown(
                List.of(withCommits, without));

        assertThat(md)
                .contains("- **komet-bom**")
                .contains("`abc12345` ws:scaffold-publish: commit reconciliation edits")
                .contains("`M pom.xml`")
                .doesNotContain("komet\n");
    }

    @Test
    void residueToMarkdown_listsLeftoverFilesPerRepo() {
        GoalCommitLedger.RepoChanges withResidue =
                new GoalCommitLedger.RepoChanges("tinkar-core",
                        List.of(), List.of("M pom.xml", "?? notes.txt"));

        String md = GoalCommitLedger.residueToMarkdown(List.of(withResidue));

        assertThat(md)
                .contains("- **tinkar-core** — 2 file(s)")
                .contains("`M pom.xml`")
                .contains("`?? notes.txt`");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void initRepo() throws Exception {
        Path noHooks = tempDir.resolve("no-hooks");
        Files.createDirectories(noHooks);
        exec("git", "init", "-b", "main");
        exec("git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec("git", "config", "user.email", "test@example.com");
        exec("git", "config", "user.name", "Test");
        exec("git", "config", "commit.gpgsign", "false");
    }

    private void commitFile(String name, String content, String message)
            throws Exception {
        Files.writeString(tempDir.resolve(name), content,
                StandardCharsets.UTF_8);
        exec("git", "add", name);
        exec("git", "commit", "-m", message);
    }

    private void exec(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
    }
}
