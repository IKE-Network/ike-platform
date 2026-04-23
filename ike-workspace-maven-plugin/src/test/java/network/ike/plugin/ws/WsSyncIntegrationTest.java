package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for {@link WsSyncMojo} using real temp workspaces.
 *
 * <p>Each test creates a fresh workspace via {@link TestWorkspaceHelper},
 * modifies branch state in repos or workspace.yaml, then exercises
 * the sync logic in both directions (repos-to-manifest and
 * manifest-to-repos).
 */
class WsSyncIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // Make the workspace directory itself a git repo so sync can commit
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        // Ignore component subdirectories (they have their own repos)
        Files.writeString(tempDir.resolve(".gitignore"),
                "lib-a/\nlib-b/\napp-c/\n", StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Initial workspace commit");
    }

    // ── syncFromRepos (default: repos → manifest) ────────────────────

    @Test
    void syncFromRepos_updatesYaml() throws Exception {
        // Switch lib-a to a feature branch
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/test");

        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.from = "repos";
        mojo.publish = true;

        mojo.execute();

        // Verify workspace.yaml now reflects the actual branch
        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        // lib-a's branch field should be updated
        assertThat(yaml).contains("feature/test");
    }

    @Test
    void syncFromRepos_allMatch_noChanges() throws Exception {
        // All components are on main, which matches workspace.yaml
        String yamlBefore = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);

        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.from = "repos";
        mojo.publish = true;

        mojo.execute();

        // Verify workspace.yaml is unchanged
        String yamlAfter = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);
        assertThat(yamlAfter).isEqualTo(yamlBefore);
    }

    @Test
    void syncFromRepos_dryRun_yamlUnchanged() throws Exception {
        // Switch lib-a to a feature branch
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/test");

        String yamlBefore = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);

        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.from = "repos";
        mojo.publish = false;

        mojo.execute();

        // Verify workspace.yaml is NOT updated in draft mode
        String yamlAfter = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);
        assertThat(yamlAfter).isEqualTo(yamlBefore);
    }

    @Test
    void syncFromRepos_commitsYamlChange() throws Exception {
        // Switch lib-a to a feature branch
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/sync-commit");

        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.from = "repos";
        mojo.publish = true;

        mojo.execute();

        // Verify git log shows the sync commit
        String log = execCapture(tempDir,
                "git", "log", "--oneline", "-3");
        assertThat(log).contains("workspace: align branch fields from repos");
    }

    // ── syncFromManifest (manifest → repos) ──────────────────────────

    @Test
    void syncFromManifest_switchesRepos() throws Exception {
        // Create a "develop" branch in lib-a
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "develop");
        exec(tempDir.resolve("lib-a"), "git", "checkout", "main");

        // Update workspace.yaml to say lib-a should be on "develop"
        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        // Replace the first occurrence of "branch: main" (which is lib-a's)
        // We need to be targeted — update the lib-a block specifically
        yaml = yaml.replaceFirst(
                "(lib-a:\\s+type: software\\s+description: [^\\n]+\\s+repo: [^\\n]+\\s+branch: )main",
                "$1develop");
        Files.writeString(helper.workspaceYaml(), yaml, StandardCharsets.UTF_8);

        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.from = "manifest";
        mojo.publish = true;

        mojo.execute();

        // Verify lib-a is now on "develop"
        String branch = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("develop");

        // lib-b and app-c should still be on main
        assertThat(execCapture(tempDir.resolve("lib-b"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("main");
        assertThat(execCapture(tempDir.resolve("app-c"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("main");
    }

    @Test
    void syncFromManifest_dirtyWorktree_skips() throws Exception {
        // Create a "develop" branch in lib-a and switch back
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "develop");
        exec(tempDir.resolve("lib-a"), "git", "checkout", "main");

        // Add an uncommitted file to lib-a
        Files.writeString(tempDir.resolve("lib-a").resolve("dirty.txt"),
                "uncommitted", StandardCharsets.UTF_8);

        // Update workspace.yaml to say lib-a should be on "develop"
        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        yaml = yaml.replaceFirst(
                "(lib-a:\\s+type: software\\s+description: [^\\n]+\\s+repo: [^\\n]+\\s+branch: )main",
                "$1develop");
        Files.writeString(helper.workspaceYaml(), yaml, StandardCharsets.UTF_8);

        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.from = "manifest";
        mojo.publish = true;

        mojo.execute();

        // lib-a should NOT be switched — still on main due to uncommitted changes
        String branch = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("main");
    }

    @Test
    void syncFromManifest_allMatch_noChanges() throws Exception {
        // All components are on main, workspace.yaml says main
        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.from = "manifest";
        mojo.publish = true;

        // Should complete without exception — nothing to switch
        assertThatCode(mojo::execute).doesNotThrowAnyException();

        // All still on main
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("main");
        }
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
