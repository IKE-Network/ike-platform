package network.ike.plugin.ws;

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
 * Integration tests for {@link UpdateFeatureDraftMojo} — updating
 * a feature branch with changes from main via merge.
 */
class UpdateFeatureIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // Start a feature branch
        FeatureStartDraftMojo start = TestLog.createMojo(FeatureStartDraftMojo.class);
        start.manifest = helper.workspaceYaml().toFile();
        start.feature = "long-lived";
        start.skipVersion = true;
        start.publish = true;
        start.execute();

        // Switch back to main and make a change
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "checkout", "main");
        }

        // Add a commit to main on lib-a
        Files.writeString(tempDir.resolve("lib-a").resolve("CHANGELOG.md"),
                "## v2.0\n- New feature\n", StandardCharsets.UTF_8);
        exec(tempDir.resolve("lib-a"), "git", "add", "CHANGELOG.md");
        exec(tempDir.resolve("lib-a"), "git", "commit", "-m", "docs: add changelog");

        // Switch back to feature branch
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "checkout", "feature/long-lived");
        }
    }

    @Test
    void updateFeature_draftMode_noChanges() throws Exception {
        String libASha = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "HEAD");

        UpdateFeatureDraftMojo mojo = TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "long-lived";
        mojo.targetBranch = "main";
        mojo.publish = false; // draft

        mojo.execute();

        // SHA should not change in draft mode
        assertThat(execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "HEAD")).isEqualTo(libASha);
    }

    @Test
    void updateFeature_incorporatesMainChanges() throws Exception {
        UpdateFeatureDraftMojo mojo = TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "long-lived";
        mojo.targetBranch = "main";
        mojo.publish = true;

        mojo.execute();

        // lib-a should now have the changelog from main
        assertThat(tempDir.resolve("lib-a").resolve("CHANGELOG.md")).exists();
        String content = Files.readString(
                tempDir.resolve("lib-a").resolve("CHANGELOG.md"),
                StandardCharsets.UTF_8);
        assertThat(content).contains("New feature");

        // Should still be on the feature branch
        assertThat(execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/long-lived");

        // Should have a merge commit
        String log = execCapture(tempDir.resolve("lib-a"),
                "git", "log", "--oneline", "-1");
        assertThat(log.toLowerCase()).contains("merge");
    }

    @Test
    void updateFeature_alreadyUpToDate() throws Exception {
        // First, merge to get up to date
        UpdateFeatureDraftMojo mojo1 = TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo1.manifest = helper.workspaceYaml().toFile();
        mojo1.feature = "long-lived";
        mojo1.targetBranch = "main";
        mojo1.publish = true;
        mojo1.execute();

        String sha = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "HEAD");

        // Run again — should be a no-op
        UpdateFeatureDraftMojo mojo2 = TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo2.manifest = helper.workspaceYaml().toFile();
        mojo2.feature = "long-lived";
        mojo2.targetBranch = "main";
        mojo2.publish = true;
        mojo2.execute();

        // SHA should not change
        assertThat(execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "HEAD")).isEqualTo(sha);
    }

    @Test
    void updateFeature_dirtyWorktree_fails() throws Exception {
        Files.writeString(tempDir.resolve("lib-a").resolve("dirty.txt"),
                "uncommitted", StandardCharsets.UTF_8);

        UpdateFeatureDraftMojo mojo = TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "long-lived";
        mojo.targetBranch = "main";
        mojo.publish = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("uncommitted changes");
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
