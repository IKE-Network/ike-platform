package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bare-mode (no {@code workspace.yaml}) tests for {@code ws:update-feature}
 * — IKE-Network/ike-issues#703. A single repo on a feature branch merges
 * {@code main} in, exactly as the workspace path does per subproject; with
 * no {@code -Dfeature}, the feature is taken from the repo's own branch.
 */
class UpdateFeatureBareModeTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");

        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>bare-update</artifactId>
                    <version>1-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Initial commit");

        // Feature branch, then advance main behind its back.
        exec(tempDir, "git", "checkout", "-b", "feature/long-lived");
        exec(tempDir, "git", "checkout", "main");
        Files.writeString(tempDir.resolve("CHANGELOG.md"),
                "## v2.0\n- New feature\n", StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "CHANGELOG.md");
        exec(tempDir, "git", "commit", "-m", "docs: add changelog");
        exec(tempDir, "git", "checkout", "feature/long-lived");

        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void bareUpdateFeature_incorporatesMainChanges_featureFromBranch()
            throws Exception {
        UpdateFeatureDraftMojo mojo =
                TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo.targetBranch = "main";
        mojo.publish = true;
        // No -Dfeature: derived from the repo's current branch.
        mojo.execute();

        assertThat(tempDir.resolve("CHANGELOG.md"))
                .as("main's commit is merged into the feature branch")
                .exists();
        assertThat(execCapture(tempDir, "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/long-lived");
        assertThat(execCapture(tempDir, "git", "log", "--oneline", "-1")
                .toLowerCase())
                .contains("merge");
    }

    @Test
    void bareUpdateFeature_draftMode_makesNoChange() throws Exception {
        String sha = execCapture(tempDir, "git", "rev-parse", "HEAD");

        UpdateFeatureDraftMojo mojo =
                TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo.targetBranch = "main";
        mojo.publish = false; // draft
        mojo.execute();

        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD"))
                .as("draft must not mutate the feature branch")
                .isEqualTo(sha);
    }

    @Test
    void bareUpdateFeature_notOnFeatureBranch_failsClearly() throws Exception {
        exec(tempDir, "git", "checkout", "main");

        UpdateFeatureDraftMojo mojo =
                TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo.targetBranch = "main";
        mojo.publish = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("not a feature/* branch");
    }

    @Test
    void bareUpdateFeature_dirtyWorktree_fails() throws Exception {
        Files.writeString(tempDir.resolve("dirty.txt"), "uncommitted",
                StandardCharsets.UTF_8);

        UpdateFeatureDraftMojo mojo =
                TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo.feature = "long-lived";
        mojo.targetBranch = "main";
        mojo.publish = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("uncommitted changes");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static void exec(Path workDir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
    }

    private static String execCapture(Path workDir, String... command)
            throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
        return out;
    }
}
