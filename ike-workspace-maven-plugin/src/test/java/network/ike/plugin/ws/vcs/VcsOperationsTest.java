package network.ike.plugin.ws.vcs;

import network.ike.plugin.ws.TestLog;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link VcsOperations} using real temp git repos.
 */
class VcsOperationsTest {

    @TempDir
    Path tempDir;

    private File repoDir;
    private final Log log = new TestLog();

    @BeforeEach
    void setUp() throws Exception {
        repoDir = tempDir.toFile();
        exec("git", "init", "-b", "main");
        exec("git", "config", "user.email", "test@example.com");
        exec("git", "config", "user.name", "Test");
        Files.writeString(tempDir.resolve("file.txt"), "hello", StandardCharsets.UTF_8);
        exec("git", "add", ".");
        exec("git", "commit", "-m", "Initial commit");
    }

    @Test
    void headSha_returns8chars() throws MojoException {
        String sha = VcsOperations.headSha(repoDir);
        assertThat(sha).hasSize(8);
        assertThat(sha).matches("[0-9a-f]{8}");
    }

    @Test
    void currentBranch_returnsMain() throws MojoException {
        assertThat(VcsOperations.currentBranch(repoDir)).isEqualTo("main");
    }

    @Test
    void isClean_trueOnCleanRepo() {
        assertThat(VcsOperations.isClean(repoDir)).isTrue();
    }

    @Test
    void isClean_falseWithUncommittedChanges() throws Exception {
        Files.writeString(tempDir.resolve("dirty.txt"), "change", StandardCharsets.UTF_8);
        assertThat(VcsOperations.isClean(repoDir)).isFalse();
    }

    @Test
    void checkout_switchesBranch() throws Exception {
        exec("git", "checkout", "-b", "feature/test");
        exec("git", "checkout", "main");

        VcsOperations.checkout(repoDir, log, "feature/test");
        assertThat(VcsOperations.currentBranch(repoDir)).isEqualTo("feature/test");
    }

    @Test
    void checkoutNew_createsAndSwitches() throws MojoException {
        VcsOperations.checkoutNew(repoDir, log, "feature/new");
        assertThat(VcsOperations.currentBranch(repoDir)).isEqualTo("feature/new");
    }

    @Test
    void writeVcsState_createsFile() throws Exception {
        Files.createDirectories(tempDir.resolve(".ike"));
        VcsOperations.writeVcsState(repoDir, VcsState.Action.COMMIT);

        Optional<VcsState> state = VcsState.readFrom(tempDir);
        assertThat(state).isPresent();
        assertThat(state.get().action()).isEqualTo(VcsState.Action.COMMIT);
        assertThat(state.get().branch()).isEqualTo("main");
        assertThat(state.get().sha()).hasSize(8);
    }

    @Test
    void needsSync_falseWhenInSync() throws Exception {
        Files.createDirectories(tempDir.resolve(".ike"));
        VcsOperations.writeVcsState(repoDir, VcsState.Action.COMMIT);

        assertThat(VcsOperations.needsSync(repoDir)).isFalse();
    }

    @Test
    void needsSync_trueWhenShaMismatch() throws Exception {
        Files.createDirectories(tempDir.resolve(".ike"));
        VcsState stale = new VcsState("2026-01-01T00:00:00Z", "other",
                "main", "deadbeef", VcsState.Action.COMMIT);
        VcsState.writeTo(tempDir, stale);

        assertThat(VcsOperations.needsSync(repoDir)).isTrue();
    }

    @Test
    void needsSync_trueWhenBranchMismatch() throws Exception {
        Files.createDirectories(tempDir.resolve(".ike"));
        String sha = VcsOperations.headSha(repoDir);
        VcsState state = new VcsState("2026-01-01T00:00:00Z", "other",
                "feature/x", sha, VcsState.Action.FEATURE_START);
        VcsState.writeTo(tempDir, state);

        assertThat(VcsOperations.needsSync(repoDir)).isTrue();
    }

    @Test
    void needsSync_falseWhenNoStateFile() throws MojoException {
        assertThat(VcsOperations.needsSync(repoDir)).isFalse();
    }

    @Test
    void catchUp_noOpWhenNotIkeManaged() throws MojoException {
        // No .ike/ directory — should do nothing
        VcsOperations.catchUp(repoDir, log);
        assertThat(VcsOperations.currentBranch(repoDir)).isEqualTo("main");
    }

    @Test
    void remoteSha_emptyForLocalRepo() throws MojoException {
        Optional<String> sha = VcsOperations.remoteSha(repoDir, "origin", "main");
        assertThat(sha).isEmpty();
    }

    @Test
    void mergeSquash_squashesIntoCurrentBranch() throws Exception {
        // Create a feature branch with a commit
        exec("git", "checkout", "-b", "feature/test");
        Files.writeString(tempDir.resolve("feature.txt"), "feature work", StandardCharsets.UTF_8);
        exec("git", "add", ".");
        exec("git", "commit", "-m", "Feature commit");

        // Switch back to main and squash
        exec("git", "checkout", "main");
        VcsOperations.mergeSquash(repoDir, log, "feature/test");

        // Working tree has the feature file
        assertThat(tempDir.resolve("feature.txt")).exists();

        // But it's not committed yet (squash stages but doesn't commit)
        String status = execCapture("git", "status", "--porcelain");
        assertThat(status).contains("feature.txt");
    }

    @Test
    void mergeNoFf_createsMergeCommit() throws Exception {
        exec("git", "checkout", "-b", "feature/test");
        Files.writeString(tempDir.resolve("feature.txt"), "work", StandardCharsets.UTF_8);
        exec("git", "add", ".");
        exec("git", "commit", "-m", "Feature");

        exec("git", "checkout", "main");
        VcsOperations.mergeNoFf(repoDir, log, "feature/test", "Merge feature");

        String logOutput = execCapture("git", "log", "--oneline", "-3");
        assertThat(logOutput).contains("Merge feature");
    }

    @Test
    void deleteBranch_removesBranch() throws Exception {
        exec("git", "checkout", "-b", "feature/temp");
        exec("git", "checkout", "main");

        VcsOperations.deleteBranch(repoDir, log, "feature/temp");

        String branches = execCapture("git", "branch", "--list", "feature/temp");
        assertThat(branches.trim()).isEmpty();
    }

    @Test
    void pushSafe_doesNotThrowOnFailure() {
        // No remote — should warn but not throw
        VcsOperations.pushSafe(repoDir, log, "origin", "main");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void exec(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(repoDir)
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
    }

    private String execCapture(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(repoDir)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        process.waitFor();
        return output;
    }
}
