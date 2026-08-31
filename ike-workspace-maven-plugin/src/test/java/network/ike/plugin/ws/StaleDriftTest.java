package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StaleDrift} (IKE-Network/ike-issues#1082): the
 * classification of pending changes as time-reversed synced drift
 * (content byte-matching an older committed state) versus novel work.
 *
 * <p>The fixture reproduces the 2026-08-31 incident shapes: a tracked
 * file reverted to an earlier commit's content, a recently added file
 * deleted, and an old blob re-appearing as an untracked file — each of
 * which must classify as stale — alongside the novel shapes that must
 * never be flagged.
 */
class StaleDriftTest {

    @TempDir
    Path repo;

    @BeforeEach
    void initRepo() throws Exception {
        exec("git", "init", "-b", "main");
        // Outside the repo — an in-repo hooks dir would surface as an
        // untracked path in the very porcelain output under test.
        Path noHooks = Files.createTempDirectory("stale-drift-nohooks");
        exec("git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec("git", "config", "user.email", "test@ike.test");
        exec("git", "config", "user.name", "Test");
        exec("git", "config", "commit.gpgsign", "false");
        commitFile("a.txt", "a version 1", "c1: add a");
        commitFile("a.txt", "a version 2", "c2: evolve a");
        commitFile("b.txt", "b version 1", "c3: add b");
    }

    // ── Modified ────────────────────────────────────────────────

    @Test
    void modifiedRevertingToOlderBlob_isStale_namingTheMatchedCommit()
            throws Exception {
        // The incident's M shape: the tree carries c1's content while
        // HEAD holds c2's.
        Files.writeString(repo.resolve("a.txt"), "a version 1",
                StandardCharsets.UTF_8);
        exec("git", "add", "a.txt");

        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(repo.toFile());

        assertThat(analysis.whollyStale()).isTrue();
        assertThat(analysis.stale()).hasSize(1);
        StaleDrift.Finding finding = analysis.stale().get(0);
        assertThat(finding.change().kind())
                .isEqualTo(StaleDrift.ChangeKind.MODIFIED);
        assertThat(finding.change().path()).isEqualTo("a.txt");
        assertThat(finding.matchedSubject()).isEqualTo("c1: add a");
    }

    @Test
    void modifiedWithNovelContent_isNovel() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "a version 3 — brand new",
                StandardCharsets.UTF_8);
        exec("git", "add", "a.txt");

        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(repo.toFile());

        assertThat(analysis.hasStale()).isFalse();
        assertThat(analysis.novel()).hasSize(1);
    }

    // ── Deleted ─────────────────────────────────────────────────

    @Test
    void deletionOfRecentlyAddedFile_isStale_namingTheAddCommit()
            throws Exception {
        // The incident's D shape: SearchQueryFactory, added days
        // earlier, "deleted" by a tree that predates the add.
        exec("git", "rm", "-q", "b.txt");

        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(repo.toFile());

        assertThat(analysis.whollyStale()).isTrue();
        StaleDrift.Finding finding = analysis.stale().get(0);
        assertThat(finding.change().kind())
                .isEqualTo(StaleDrift.ChangeKind.DELETED);
        assertThat(finding.matchedSubject()).isEqualTo("c3: add b");
    }

    @Test
    void deletionOfFileAddedOutsideWindow_isNovel() throws Exception {
        // Push the add of b.txt beyond the recent-commit window with
        // filler commits, then delete it: an ordinary deletion of a
        // long-established file, not drift.
        for (int i = 0; i < StaleDrift.REPO_WINDOW; i++) {
            commitFile("filler.txt", "filler " + i, "filler " + i);
        }
        exec("git", "rm", "-q", "b.txt");

        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(repo.toFile());

        assertThat(analysis.hasStale()).isFalse();
        assertThat(analysis.novel()).hasSize(1);
    }

    // ── Added ───────────────────────────────────────────────────

    @Test
    void reAddedOldContentAfterDeletion_isStale() throws Exception {
        // The A shape: refs deleted the file, the lagging tree still
        // holds the old blob, which the sweep would stage as an add.
        exec("git", "rm", "-q", "b.txt");
        exec("git", "commit", "-m", "c4: remove b");
        Files.writeString(repo.resolve("b.txt"), "b version 1",
                StandardCharsets.UTF_8);
        exec("git", "add", "b.txt");

        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(repo.toFile());

        assertThat(analysis.whollyStale()).isTrue();
        assertThat(analysis.stale().get(0).change().kind())
                .isEqualTo(StaleDrift.ChangeKind.ADDED);
    }

    @Test
    void brandNewPath_isNovel() throws Exception {
        Files.writeString(repo.resolve("fresh.txt"), "never seen before",
                StandardCharsets.UTF_8);
        exec("git", "add", "fresh.txt");

        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(repo.toFile());

        assertThat(analysis.hasStale()).isFalse();
        assertThat(analysis.novel()).hasSize(1);
    }

    // ── Mixtures and envelopes ──────────────────────────────────

    @Test
    void mixedStaleAndNovel_hasStale_butNotWhollyStale() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "a version 1",
                StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("fresh.txt"), "novel work",
                StandardCharsets.UTF_8);
        exec("git", "add", "a.txt", "fresh.txt");

        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(repo.toFile());

        assertThat(analysis.hasStale()).isTrue();
        assertThat(analysis.whollyStale()).isFalse();
        assertThat(analysis.stale()).hasSize(1);
        assertThat(analysis.novel()).hasSize(1);
    }

    @Test
    void nothingStaged_isEmptyAnalysis() throws Exception {
        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(repo.toFile());

        assertThat(analysis.hasStale()).isFalse();
        assertThat(analysis.novel()).isEmpty();
        assertThat(analysis.whollyStale()).isFalse();
    }

    @Test
    void singleCommitRepo_treatsChangesAsNovel(@TempDir Path fresh)
            throws Exception {
        execIn(fresh, "git", "init", "-b", "main");
        execIn(fresh, "git", "config", "user.email", "test@ike.test");
        execIn(fresh, "git", "config", "user.name", "Test");
        execIn(fresh, "git", "config", "commit.gpgsign", "false");
        Files.writeString(fresh.resolve("only.txt"), "v1",
                StandardCharsets.UTF_8);
        execIn(fresh, "git", "add", "only.txt");
        execIn(fresh, "git", "commit", "-m", "init");
        Files.writeString(fresh.resolve("only.txt"), "v2",
                StandardCharsets.UTF_8);
        execIn(fresh, "git", "add", "only.txt");

        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(fresh.toFile());

        assertThat(analysis.hasStale()).isFalse();
        assertThat(analysis.novel()).hasSize(1);
    }

    // ── Worktree analysis (the draft's view) ────────────────────

    @Test
    void worktreeAnalysis_flagsUnstagedRevert_andUntrackedOldBlob()
            throws Exception {
        // Unstaged revert of a.txt plus an untracked file carrying a
        // historical blob of a deleted path — the exact drift shapes as
        // the draft preview sees them, before any staging.
        exec("git", "rm", "-q", "b.txt");
        exec("git", "commit", "-m", "c4: remove b");
        Files.writeString(repo.resolve("a.txt"), "a version 1",
                StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("b.txt"), "b version 1",
                StandardCharsets.UTF_8);

        StaleDrift.Analysis analysis =
                StaleDrift.analyzeWorktree(repo.toFile());

        assertThat(analysis.whollyStale()).isTrue();
        assertThat(analysis.stale()).hasSize(2);
    }

    @Test
    void describeStale_namesPathVerbAndCommit() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "a version 1",
                StandardCharsets.UTF_8);
        exec("git", "add", "a.txt");

        StaleDrift.Analysis analysis = StaleDrift.analyzeStaged(repo.toFile());
        String line = StaleDrift.describeStale(analysis).get(0);

        assertThat(line).startsWith("a.txt — reverts to its state at ");
        assertThat(line).endsWith("(c1: add a)");
    }

    // ── Fixture helpers ─────────────────────────────────────────

    private void commitFile(String name, String content, String message)
            throws Exception {
        Files.writeString(repo.resolve(name), content,
                StandardCharsets.UTF_8);
        exec("git", "add", name);
        exec("git", "commit", "-m", message);
    }

    private void exec(String... command) throws Exception {
        execIn(repo, command);
    }

    private static void execIn(Path dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
    }
}
