package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.AfterEach;
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
 * End-to-end integration tests for the VCS bridge workflow.
 *
 * <p>Simulates the multi-machine scenario: machine A commits (writing a
 * state file), then machine B needs to sync before it can commit.
 * Uses two local repos sharing a "remote" bare repo.
 */
class VcsBridgeIntegrationTest {

    @TempDir
    Path tempDir;

    private Path remoteDir;
    private Path machineADir;
    private Path machineBDir;
    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");

        // Create a bare "remote" repo
        remoteDir = tempDir.resolve("remote.git");
        Files.createDirectories(remoteDir);
        exec(remoteDir, "git", "init", "--bare", "-b", "main");

        // Clone to machine A
        machineADir = tempDir.resolve("machine-a");
        exec(tempDir, "git", "clone", remoteDir.toString(), machineADir.getFileName().toString());
        exec(machineADir, "git", "config", "user.email", "a@test.com");
        exec(machineADir, "git", "config", "user.name", "Machine A");
        Files.writeString(machineADir.resolve("file.txt"), "initial", StandardCharsets.UTF_8);
        Files.createDirectories(machineADir.resolve(".ike"));
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Initial commit");
        exec(machineADir, "git", "push", "-u", "origin", "main");

        // Clone to machine B
        machineBDir = tempDir.resolve("machine-b");
        exec(tempDir, "git", "clone", remoteDir.toString(), machineBDir.getFileName().toString());
        exec(machineBDir, "git", "config", "user.email", "b@test.com");
        exec(machineBDir, "git", "config", "user.name", "Machine B");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void stateFile_writtenAfterCommit() throws Exception {
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.COMMIT);

        Optional<VcsState> state = VcsState.readFrom(machineADir);
        assertThat(state).isPresent();
        assertThat(state.get().action()).isEqualTo(VcsState.Action.COMMIT);
        assertThat(state.get().branch()).isEqualTo("main");
        assertThat(state.get().sha()).hasSize(8);
        assertThat(state.get().machine()).isNotEmpty();
        assertThat(state.get().timestamp()).contains("T");
    }

    @Test
    void needsSync_detectsStaleMachine() throws Exception {
        // Machine A commits and pushes
        Files.writeString(machineADir.resolve("file.txt"), "updated by A", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Update from A");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.COMMIT);

        // Simulate Syncthing delivering state file to machine B
        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        // Machine B should detect it needs sync
        assertThat(VcsOperations.needsSync(machineBDir.toFile())).isTrue();
    }

    @Test
    void sync_reconcilesAfterRemoteCommit() throws Exception {
        // Machine A commits and pushes
        Files.writeString(machineADir.resolve("file.txt"), "updated by A", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Update from A");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.COMMIT);

        String expectedSha = VcsOperations.headSha(machineADir.toFile());

        // Simulate Syncthing delivering state file to machine B
        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        // Machine B syncs
        org.apache.maven.api.plugin.Log log = new TestLog();
        VcsOperations.sync(machineBDir.toFile(), log);

        // Machine B HEAD should now match
        assertThat(VcsOperations.headSha(machineBDir.toFile())).isEqualTo(expectedSha);
        assertThat(VcsOperations.needsSync(machineBDir.toFile())).isFalse();
    }

    @Test
    void syncMojo_bareModeSync() throws Exception {
        // Machine A commits and pushes
        Files.writeString(machineADir.resolve("file.txt"), "A update", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "A update");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.COMMIT);

        String expectedSha = VcsOperations.headSha(machineADir.toFile());

        // Deliver state file
        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        // Run VCS bridge sync on machine B (direct API — ws:vcs-sync removed)
        VcsOperations.sync(machineBDir.toFile(), new TestLog());

        assertThat(VcsOperations.headSha(machineBDir.toFile())).isEqualTo(expectedSha);
    }

    @Test
    void verifyMojo_reportsInSync() throws Exception {
        // Both machines on same commit
        Files.createDirectories(machineBDir.resolve(".ike"));
        VcsOperations.writeVcsState(machineBDir.toFile(), VcsState.Action.COMMIT);

        System.setProperty("user.dir", machineBDir.toAbsolutePath().toString());
        VerifyWorkspaceMojo mojo = TestLog.createMojo(VerifyWorkspaceMojo.class);
        // Should not throw — just reports state
        mojo.execute();
    }

    @Test
    void verifyMojo_reportsBehind() throws Exception {
        // Machine A advances, state file delivered to B
        Files.writeString(machineADir.resolve("file.txt"), "ahead", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Ahead");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.COMMIT);

        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        System.setProperty("user.dir", machineBDir.toAbsolutePath().toString());
        VerifyWorkspaceMojo mojo = TestLog.createMojo(VerifyWorkspaceMojo.class);
        // Should not throw — reports "behind" state
        mojo.execute();
    }

    @Test
    void sync_preservesLocalCommitsAheadOfOrigin() throws Exception {
        // Regression for #144: sync() used to unconditionally reset local
        // HEAD to origin/<state.branch>, silently discarding unpushed
        // commits. The fix: when local is strictly ahead of origin,
        // sync() must leave local alone.
        //
        // Scenario: Machine A made a COMMIT (writing vcs-state) but did
        // not push. Then ran a goal that calls catchUp → sync.
        Files.writeString(machineADir.resolve("file.txt"), "local-only work",
                StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Local-only commit");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.COMMIT);

        String localSha = VcsOperations.headSha(machineADir.toFile());
        Optional<String> remoteShaBefore = VcsOperations.remoteSha(
                machineADir.toFile(), "origin", "main");
        assertThat(remoteShaBefore).isPresent();
        assertThat(remoteShaBefore.get()).isNotEqualTo(localSha);  // ahead

        // Sync should NOT reset local to origin — that would lose the commit.
        VcsOperations.sync(machineADir.toFile(), new TestLog());

        assertThat(VcsOperations.headSha(machineADir.toFile()))
                .as("local commit must be preserved; #144 regression")
                .isEqualTo(localSha);
    }

    @Test
    void sync_fastForwardsWhenLocalIsBehind() throws Exception {
        // Origin advances; local is strictly behind. sync() should
        // fast-forward local to origin safely.
        Files.writeString(machineADir.resolve("file.txt"), "A update",
                StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "A update");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.COMMIT);

        String expectedSha = VcsOperations.headSha(machineADir.toFile());

        // Deliver state file to B (which is behind)
        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        VcsOperations.sync(machineBDir.toFile(), new TestLog());

        assertThat(VcsOperations.headSha(machineBDir.toFile())).isEqualTo(expectedSha);
    }

    @Test
    void sync_abortsOnDivergedBranch() throws Exception {
        // Both local and origin have unique commits (diverged).
        // sync() must refuse rather than silently pick a side.
        //
        // Machine A commits locally without pushing.
        Files.writeString(machineADir.resolve("a-only.txt"), "A local work",
                StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "A local commit");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.COMMIT);
        String aLocalSha = VcsOperations.headSha(machineADir.toFile());

        // Machine B commits and pushes — origin/main advances to B's commit.
        Files.writeString(machineBDir.resolve("b-only.txt"), "B pushed work",
                StandardCharsets.UTF_8);
        exec(machineBDir, "git", "add", ".");
        exec(machineBDir, "git", "commit", "-m", "B pushed commit");
        exec(machineBDir, "git", "push", "origin", "main");

        // A fetches — now A sees origin has B's commit, and A has its own
        // unpushed commit. Neither is ancestor of the other → diverged.
        exec(machineADir, "git", "fetch", "origin");

        assertThatThrownBy(() ->
                VcsOperations.sync(machineADir.toFile(), new TestLog()))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("diverged");

        // Critically: local state untouched.
        assertThat(VcsOperations.headSha(machineADir.toFile())).isEqualTo(aLocalSha);
    }

    @Test
    void sync_noOpWhenAlreadyAtOrigin() throws Exception {
        // Local and origin are at the same commit — sync should be
        // idempotent (no reset, no error).
        Files.writeString(machineADir.resolve("file.txt"), "update",
                StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Update");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.PUSH);

        String before = VcsOperations.headSha(machineADir.toFile());

        VcsOperations.sync(machineADir.toFile(), new TestLog());

        assertThat(VcsOperations.headSha(machineADir.toFile())).isEqualTo(before);
    }

    @Test
    void isAncestor_detectsAncestry() throws Exception {
        // Initial commit is ancestor of any later commit.
        String initialSha = VcsOperations.headSha(machineADir.toFile());

        Files.writeString(machineADir.resolve("later.txt"), "later",
                StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Later commit");
        String laterSha = VcsOperations.headSha(machineADir.toFile());

        assertThat(VcsOperations.isAncestor(machineADir.toFile(), initialSha, laterSha))
                .as("initial is ancestor of later").isTrue();
        assertThat(VcsOperations.isAncestor(machineADir.toFile(), laterSha, initialSha))
                .as("later is not ancestor of initial").isFalse();
        // Self-ancestry: a commit is its own ancestor per git's semantics
        assertThat(VcsOperations.isAncestor(machineADir.toFile(), initialSha, initialSha))
                .isTrue();
    }

    @Test
    void sync_handlesBranchSwitch() throws Exception {
        // Machine A creates a feature branch
        exec(machineADir, "git", "checkout", "-b", "feature/test");
        Files.writeString(machineADir.resolve("feature.txt"), "feature work", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Feature work");
        exec(machineADir, "git", "push", "-u", "origin", "feature/test");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.FEATURE_START);

        // Deliver state file to machine B (still on main)
        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        assertThat(VcsOperations.currentBranch(machineBDir.toFile())).isEqualTo("main");

        // Sync should switch B to the feature branch
        org.apache.maven.api.plugin.Log log = new TestLog();
        VcsOperations.sync(machineBDir.toFile(), log);

        assertThat(VcsOperations.currentBranch(machineBDir.toFile())).isEqualTo("feature/test");
    }

    @Test
    void commitMojo_setsIkeVcsContext() throws Exception {
        // Create .ike directory
        Files.createDirectories(machineADir.resolve(".ike"));
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.Action.COMMIT);

        // Make a change
        Files.writeString(machineADir.resolve("new.txt"), "new file", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");

        System.setProperty("user.dir", machineADir.toAbsolutePath().toString());
        CommitMojo mojo = TestLog.createMojo(CommitMojo.class);
        mojo.message = "Test commit via plugin";

        mojo.execute();

        // Verify commit happened
        String log = execCapture(machineADir, "git", "log", "--oneline", "-1");
        assertThat(log).contains("Test commit via plugin");

        // State file updated
        Optional<VcsState> state = VcsState.readFrom(machineADir);
        assertThat(state).isPresent();
        assertThat(state.get().action()).isEqualTo(VcsState.Action.COMMIT);
    }

    @Test
    void isIkeManaged_correctDetection() throws Exception {
        assertThat(VcsState.isIkeManaged(machineADir)).isTrue();  // .ike/ created in setUp
        assertThat(VcsState.isIkeManaged(tempDir.resolve("nonexistent"))).isFalse();
    }

    @Test
    void stateFile_actionLabels() {
        assertThat(VcsState.Action.COMMIT.label()).isEqualTo("commit");
        assertThat(VcsState.Action.PUSH.label()).isEqualTo("push");
        assertThat(VcsState.Action.FEATURE_START.label()).isEqualTo("feature-start");
        assertThat(VcsState.Action.FEATURE_FINISH.label()).isEqualTo("feature-finish");
        assertThat(VcsState.Action.SWITCH.label()).isEqualTo("switch");
        assertThat(VcsState.Action.RELEASE.label()).isEqualTo("release");
        assertThat(VcsState.Action.CHECKPOINT.label()).isEqualTo("checkpoint");
    }

    @Test
    void stateFile_actionFromString_caseInsensitive() {
        assertThat(VcsState.Action.fromString("commit")).isEqualTo(VcsState.Action.COMMIT);
        assertThat(VcsState.Action.fromString("COMMIT")).isEqualTo(VcsState.Action.COMMIT);
        assertThat(VcsState.Action.fromString("Commit")).isEqualTo(VcsState.Action.COMMIT);
        assertThat(VcsState.Action.fromString("Feature-Start")).isEqualTo(VcsState.Action.FEATURE_START);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
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

    private String execCapture(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
        return output;
    }
}
