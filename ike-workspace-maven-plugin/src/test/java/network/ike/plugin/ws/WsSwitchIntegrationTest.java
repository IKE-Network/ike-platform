package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link WsSwitchDraftMojo} — switching between
 * active feature branches and main.
 */
class WsSwitchIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // Start first feature branch
        FeatureStartDraftMojo start1 = TestLog.createMojo(FeatureStartDraftMojo.class);
        start1.manifest = helper.workspaceYaml().toFile();
        start1.feature = "alpha";
        start1.skipVersion = true;
        start1.publish = true;
        start1.execute();

        // Switch back to main and start a second feature
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "checkout", "main");
        }

        FeatureStartDraftMojo start2 = TestLog.createMojo(FeatureStartDraftMojo.class);
        start2.manifest = helper.workspaceYaml().toFile();
        start2.feature = "beta";
        start2.skipVersion = true;
        start2.publish = true;
        start2.execute();

        // Now all components are on feature/beta, and feature/alpha exists locally
    }

    @Test
    void switch_switchesToFeatureBranch() throws Exception {
        // Currently on feature/beta — switch to feature/alpha
        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "feature/alpha";
        mojo.publish = true;

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/alpha");
        }
    }

    @Test
    void switch_switchesToMain() throws Exception {
        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "main";
        mojo.publish = true;

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("main");
        }
    }

    @Test
    void switch_draftMode_noChanges() throws Exception {
        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "feature/alpha";
        mojo.publish = false; // draft

        mojo.execute();

        // Should still be on beta (draft = preview only)
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/beta");
        }
    }

    @Test
    void switch_draftMode_previewsStashPlan() throws Exception {
        // Uncommitted work in one subproject; draft-switch to another branch.
        Files.writeString(tempDir.resolve("lib-b").resolve("dirty.txt"),
                "uncommitted", StandardCharsets.UTF_8);

        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "feature/alpha";
        mojo.publish = false; // draft

        WorkspaceReportSpec spec = mojo.runGoal();

        // The draft report previews the stash plan instead of going silent,
        // and uses the future tense rather than "switched" (#568).
        assertThat(spec.content())
                .contains("to switch")
                .contains("Stash plan")
                .contains("would be")
                .contains("lib-b")
                .doesNotContain("switched");

        // Draft is preview-only: the dirty file and branches are untouched.
        assertThat(tempDir.resolve("lib-b").resolve("dirty.txt")).exists();
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/beta");
        }
    }

    @Test
    void switch_dirtyWorktree_withNoStash_fails() throws Exception {
        // With the #153 auto-stash feature, the default behavior is to
        // stash uncommitted work rather than fail. The -DnoStash=true
        // opt-out restores the pre-#153 strict preflight — this test
        // locks in that opt-out path.
        Files.writeString(tempDir.resolve("lib-b").resolve("dirty.txt"),
                "uncommitted", StandardCharsets.UTF_8);

        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "feature/alpha";
        mojo.publish = true;
        mojo.noStash = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("uncommitted changes")
                .hasMessageContaining("ws:commit");
    }

    @Test
    void switch_alreadyOnBranch_noop() throws Exception {
        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "feature/beta"; // already on this branch
        mojo.publish = true;

        // Should complete without error
        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/beta");
        }
    }

    @Test
    void switch_nonexistentBranch_fails() throws Exception {
        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "feature/nonexistent";
        mojo.publish = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void switch_noConsole_noBranch_fails() throws Exception {
        // No -Dbranch and no Prompter wired → should fail with the
        // generic 'specify -D' message from requireParam.
        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("branch is required")
                .hasMessageContaining("-Dbranch");
    }

    // ── #904: per-member reconciliation + VCS-state recording ─────────

    @Test
    void switch_reconcilesSingleDeviatingMemberDespiteMajorityOnTarget()
            throws Exception {
        // All members are on feature/beta; one deviates back to main. The
        // majority vote said "already on feature/beta — nothing to do" while
        // the deviator sat wrong for a week (IKE-Network/ike-issues#904).
        exec(tempDir.resolve("lib-b"), "git", "checkout", "main");

        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "feature/beta";
        mojo.publish = true;

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD"))
                    .as(name)
                    .isEqualTo("feature/beta");
        }
    }

    @Test
    void switch_noopWhenEveryMemberAlreadyOnTarget() throws Exception {
        // The true nothing-to-do case must still early-out.
        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "feature/beta";
        mojo.publish = true;

        WorkspaceReportSpec spec = mojo.runGoal();

        assertThat(spec.content()).contains("nothing to do");
    }

    @Test
    void switch_recordsMemberVcsStateSoBridgeSyncKeepsTheBranch()
            throws Exception {
        // A bridge-managed member carries .ike/vcs-state naming the source
        // branch; without recording the member's switch as a VCS action, the
        // next goal's bridge sync would switch it straight back (#903/#904).
        Path libA = tempDir.resolve("lib-a");
        Files.writeString(libA.resolve(".git/info/exclude"), ".ike/\n",
                StandardCharsets.UTF_8);
        VcsState.writeTo(libA, VcsState.create("feature/beta",
                VcsOperations.headSha(libA.toFile()), VcsState.Action.COMMIT));

        WsSwitchDraftMojo mojo = TestLog.createMojo(WsSwitchDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.branch = "feature/alpha";
        mojo.publish = true;

        mojo.execute();

        java.util.Optional<VcsState> state = VcsState.readFrom(libA);
        assertThat(state).isPresent();
        assertThat(state.get().branch()).isEqualTo("feature/alpha");
        assertThat(state.get().action()).isEqualTo(VcsState.Action.SWITCH);

        VcsOperations.catchUp(libA.toFile(), new TestLog());
        assertThat(execCapture(libA, "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/alpha");
    }

    // ── Helpers ──────────────────────────────────────────────────────

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
