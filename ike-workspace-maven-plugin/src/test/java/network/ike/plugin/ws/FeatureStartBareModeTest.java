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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link FeatureStartDraftMojo} bare-mode (no workspace.yaml).
 *
 * <p>Each test creates a standalone git repo with a pom.xml and exercises
 * the bare-mode path where no workspace manifest is present. Uses
 * {@code System.setProperty("user.dir", ...)} to simulate running Maven
 * from within the test repo.
 */
class FeatureStartBareModeTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");

        // Create a standalone git repo with a pom.xml
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>bare-app</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Initial commit");

        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void bareMode_createsBranchInCurrentRepo() throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.feature = "test-bare";
        mojo.publish = true;

        mojo.execute();

        // Verify branch was created and is current
        String branch = execCapture(tempDir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("feature/test-bare");

        // Verify version was branch-qualified
        String pomContent = Files.readString(
                tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).contains("test-bare");
        assertThat(pomContent).contains("SNAPSHOT");
    }

    @Test
    void bareMode_dryRun_noChanges() throws Exception {
        String originalBranch = execCapture(tempDir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        String originalPom = Files.readString(
                tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.feature = "dry-test";
        mojo.publish = false;

        mojo.execute();

        // Branch should NOT be created
        String branch = execCapture(tempDir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo(originalBranch);

        // No feature branch should exist
        String branches = execCapture(tempDir, "git", "branch");
        assertThat(branches).doesNotContain("feature/dry-test");

        // POM should be unchanged
        String pomContent = Files.readString(
                tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).isEqualTo(originalPom);
    }

    @Test
    void bareMode_dirtyWorktree_fails() throws Exception {
        // Create an uncommitted file
        Files.writeString(tempDir.resolve("dirty.txt"),
                "uncommitted", StandardCharsets.UTF_8);

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.feature = "dirty-test";
        mojo.publish = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Uncommitted changes");
    }

    @Test
    void bareMode_skipVersion_branchCreatedVersionUnchanged() throws Exception {
        String originalPom = Files.readString(
                tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.feature = "skip-ver";
        mojo.skipVersion = true;
        mojo.publish = true;

        mojo.execute();

        // Branch should be created
        String branch = execCapture(tempDir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("feature/skip-ver");

        // POM version should be unchanged
        String pomContent = Files.readString(
                tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).isEqualTo(originalPom);
    }

    @Test
    void bareMode_multiModuleProject() throws Exception {
        // Add a submodule directory with its own pom.xml
        Path subDir = tempDir.resolve("sub-module");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>bare-app</artifactId>
                        <version>2.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>sub-module</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Add submodule");

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.feature = "multi-mod";
        mojo.publish = true;

        mojo.execute();

        // Root POM should have branch-qualified version
        String rootPom = Files.readString(
                tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(rootPom).contains("multi-mod");
        assertThat(rootPom).contains("SNAPSHOT");

        // Submodule POM parent version should also be updated
        String subPom = Files.readString(
                subDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(subPom).contains("multi-mod");
        assertThat(subPom).contains("SNAPSHOT");
    }

    @Test
    void bareMode_dryRun_skipVersion_noVersionInfo() throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.feature = "skip-dry";
        mojo.publish = false;
        mojo.skipVersion = true;

        mojo.execute();

        // Branch should NOT be created
        String branch = execCapture(tempDir,
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("main");

        // POM unchanged
        String pomContent = Files.readString(
                tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).contains("2.0.0-SNAPSHOT");
        assertThat(pomContent).doesNotContain("skip-dry");
    }

    @Test
    void bareMode_noPomFile_skipVersionImplied() throws Exception {
        // Create a repo without a pom.xml
        Path noPomDir = tempDir.resolve("no-pom-repo");
        Files.createDirectories(noPomDir);
        Files.writeString(noPomDir.resolve("README.md"), "# Docs",
                StandardCharsets.UTF_8);
        exec(noPomDir, "git", "init", "-b", "main");
        exec(noPomDir, "git", "config", "user.email", "test@example.com");
        exec(noPomDir, "git", "config", "user.name", "Test");
        exec(noPomDir, "git", "add", ".");
        exec(noPomDir, "git", "commit", "-m", "Initial commit");

        String savedDir = System.getProperty("user.dir");
        System.setProperty("user.dir", noPomDir.toAbsolutePath().toString());
        try {
            FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
            mojo.feature = "no-pom-test";
            mojo.skipVersion = true;
            mojo.publish = true;

            mojo.execute();

            // Branch should be created
            String branch = execCapture(noPomDir,
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/no-pom-test");
        } finally {
            System.setProperty("user.dir", savedDir);
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
