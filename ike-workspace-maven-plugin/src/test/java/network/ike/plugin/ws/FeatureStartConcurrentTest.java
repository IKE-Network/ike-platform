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
 * Integration tests for starting a second feature branch while on an
 * existing feature branch (concurrent feature support).
 */
class FeatureStartConcurrentTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // Start first feature branch on all components
        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "first-feature";
        mojo.publish = true;
        mojo.execute();

        // Verify we're on the first feature
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD"))
                    .isEqualTo("feature/first-feature");
        }
    }

    @Test
    void featureStart_whileOnDifferentFeature_switchesToMainFirst() throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "second-feature";
        mojo.publish = true;

        mojo.execute();

        // All components should now be on the second feature
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/second-feature");
        }

        // The first feature branch should still exist in each component
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branches = execCapture(tempDir.resolve(name), "git", "branch");
            assertThat(branches).contains("feature/first-feature");
        }
    }

    @Test
    void featureStart_whileOnDifferentFeature_draftMode_noChanges() throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "second-feature";
        mojo.publish = false; // draft

        mojo.execute();

        // All components should still be on the first feature (draft = no changes)
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/first-feature");
        }
    }

    @Test
    void featureStart_whileOnDifferentFeature_uncommittedChanges_fails() throws Exception {
        // Create an uncommitted file in lib-a
        Files.writeString(tempDir.resolve("lib-a").resolve("dirty.txt"),
                "uncommitted", StandardCharsets.UTF_8);

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "second-feature";
        mojo.publish = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("uncommitted changes")
                .hasMessageContaining("commit or stash");
    }

    @Test
    void featureStart_secondFeature_derivesFromMain() throws Exception {
        // Record main's HEAD SHA in lib-a before switching
        String mainSha = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "main");

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "second-feature";
        mojo.skipVersion = true; // skip version to simplify — only 1 commit on feature
        mojo.publish = true;

        mojo.execute();

        // The second feature's parent commit should be main's HEAD
        // (since skipVersion=true, no version commit, so HEAD~0 is the branch point)
        String parentSha = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "HEAD");
        // The branch was created from main, so main should be an ancestor
        int exitCode = new ProcessBuilder("git", "merge-base", "--is-ancestor", mainSha, "HEAD")
                .directory(tempDir.resolve("lib-a").toFile())
                .start().waitFor();
        assertThat(exitCode).isZero(); // main is ancestor of second-feature
    }

    // ── Helpers ──────────────────────────────────────────────────────

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
