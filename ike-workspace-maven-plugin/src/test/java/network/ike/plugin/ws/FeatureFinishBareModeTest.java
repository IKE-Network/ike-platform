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
 * Integration tests for feature-finish bare-mode (no workspace.yaml).
 *
 * <p>Tests all three strategies: squash, merge, and rebase.
 */
class FeatureFinishBareModeTest {

    private static final String FEATURE_NAME = "test-finish";
    private static final String BRANCH_NAME = "feature/" + FEATURE_NAME;

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");

        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>bare-finish</artifactId>
                    <version>3.0.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Initial commit");

        exec(tempDir, "git", "checkout", "-b", BRANCH_NAME);

        Path pom = tempDir.resolve("pom.xml");
        String pomContent = Files.readString(pom, StandardCharsets.UTF_8);
        String qualified = pomContent.replace(
                "<version>3.0.0-SNAPSHOT</version>",
                "<version>3.0.0-test-finish-SNAPSHOT</version>");
        Files.writeString(pom, qualified, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", "pom.xml");
        exec(tempDir, "git", "commit", "-m", "feature: set branch-qualified version");

        // Add a real code change on the feature branch (beyond just version)
        Files.writeString(tempDir.resolve("feature-work.txt"),
                "actual feature content", StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "feature-work.txt");
        exec(tempDir, "git", "commit", "-m", "feature: add feature work");

        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    // ── Squash strategy tests ────────────────────────────────────

    @Test
    void squash_mergesAndStripsVersion() throws Exception {
        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash: add test feature";
        mojo.publish = true;

        mojo.execute();

        assertThat(currentBranch()).isEqualTo("main");

        // Version stripped back to plain SNAPSHOT
        String pomContent = Files.readString(tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).doesNotContain("test-finish");
        assertThat(pomContent).contains("3.0.0-SNAPSHOT");

        // Squash commit message present (not a merge commit)
        String log = execCapture(tempDir, "git", "log", "--oneline", "-3");
        assertThat(log).contains("Squash: add test feature");
        assertThat(log).doesNotContain("Merge ");
    }

    @Test
    void squash_deletesBranchByDefault() throws Exception {
        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash merge";
        mojo.publish = true;

        mojo.execute();

        // Feature branch should be deleted
        String branches = execCapture(tempDir, "git", "branch");
        assertThat(branches).doesNotContain("feature/test-finish");
    }

    @Test
    void squash_keepBranch_preservesBranch() throws Exception {
        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash merge";
        mojo.keepBranch = true;
        mojo.publish = true;

        mojo.execute();

        // Feature branch should still exist
        String branches = execCapture(tempDir, "git", "branch");
        assertThat(branches).contains("feature/test-finish");
    }

    @Test
    void squash_multiModule_stripsSubmoduleVersions() throws Exception {
        Path subDir = tempDir.resolve("sub-module");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>bare-finish</artifactId>
                        <version>3.0.0-test-finish-SNAPSHOT</version>
                    </parent>
                    <artifactId>sub-module</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Add submodule");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash with submodules";
        mojo.publish = true;

        mojo.execute();

        assertThat(currentBranch()).isEqualTo("main");

        // Root POM stripped
        String rootPom = Files.readString(tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(rootPom).doesNotContain("test-finish");
        assertThat(rootPom).contains("3.0.0-SNAPSHOT");

        // Submodule parent version stripped
        String subPom = Files.readString(subDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(subPom).doesNotContain("test-finish");
        assertThat(subPom).contains("3.0.0-SNAPSHOT");
    }

    @Test
    void squash_stripsVersionProperties() throws Exception {
        // Replace root POM with one that has branch-qualified properties
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>bare-finish</artifactId>
                    <version>3.0.0-test-finish-SNAPSHOT</version>
                    <properties>
                        <tinkar-core.version>1.127.2-test-finish-SNAPSHOT</tinkar-core.version>
                        <komet.version>1.59.0-test-finish-SNAPSHOT</komet.version>
                        <unrelated.prop>hello</unrelated.prop>
                    </properties>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", "pom.xml");
        exec(tempDir, "git", "commit", "-m", "Add version properties");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash with properties";
        mojo.publish = true;

        mojo.execute();

        String pomContent = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pomContent).doesNotContain("test-finish");
        assertThat(pomContent).contains(
                "<tinkar-core.version>1.127.2-SNAPSHOT</tinkar-core.version>");
        assertThat(pomContent).contains(
                "<komet.version>1.59.0-SNAPSHOT</komet.version>");
        assertThat(pomContent).contains(
                "<unrelated.prop>hello</unrelated.prop>");
    }

    @Test
    void squash_stripsBomImportVersions() throws Exception {
        // Replace root POM with one that has a branch-qualified BOM import
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>bare-finish</artifactId>
                    <version>3.0.0-test-finish-SNAPSHOT</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>dev.ikm.komet</groupId>
                                <artifactId>komet-bom</artifactId>
                                <version>3.0.7-test-finish-SNAPSHOT</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", "pom.xml");
        exec(tempDir, "git", "commit", "-m", "Add BOM import");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash with BOM import";
        mojo.publish = true;

        mojo.execute();

        String pomContent = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pomContent).doesNotContain("test-finish");
        assertThat(pomContent).contains(
                "<version>3.0.7-SNAPSHOT</version>");
    }

    @Test
    void squash_stripsNonSemverVersions() throws Exception {
        // Replace root POM with single-segment version (like ike-parent: 92)
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>bare-finish</artifactId>
                    <version>92-test-finish-SNAPSHOT</version>
                    <properties>
                        <ike-parent.version>92-test-finish-SNAPSHOT</ike-parent.version>
                    </properties>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", "pom.xml");
        exec(tempDir, "git", "commit", "-m", "Non-semver version");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash non-semver";
        mojo.publish = true;

        mojo.execute();

        String pomContent = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pomContent).doesNotContain("test-finish");
        assertThat(pomContent).contains("<version>92-SNAPSHOT</version>");
        assertThat(pomContent).contains(
                "<ike-parent.version>92-SNAPSHOT</ike-parent.version>");
    }

    @Test
    void squash_stripsDateBasedVersions() throws Exception {
        // Replace root POM with date-based version (SNOMED-style YYYYMMDD)
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>bare-finish</artifactId>
                    <version>20240315-test-finish-SNAPSHOT</version>
                    <properties>
                        <snomed.version>20240315-test-finish-SNAPSHOT</snomed.version>
                    </properties>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "add", "pom.xml");
        exec(tempDir, "git", "commit", "-m", "Date-based version");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash date-based";
        mojo.publish = true;

        mojo.execute();

        String pomContent = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pomContent).doesNotContain("test-finish");
        assertThat(pomContent).contains("<version>20240315-SNAPSHOT</version>");
        assertThat(pomContent).contains(
                "<snomed.version>20240315-SNAPSHOT</snomed.version>");
    }

    // ── Merge strategy tests ─────────────────────────────────────

    @Test
    void merge_createsMergeCommit() throws Exception {
        FeatureFinishMergeDraftMojo mojo = TestLog.createMojo(FeatureFinishMergeDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.publish = true;

        mojo.execute();

        assertThat(currentBranch()).isEqualTo("main");

        // Merge commit present
        String log = execCapture(tempDir, "git", "log", "--oneline", "-5");
        assertThat(log).contains("Merge " + BRANCH_NAME);
    }

    @Test
    void merge_keepsBranchByDefault() throws Exception {
        FeatureFinishMergeDraftMojo mojo = TestLog.createMojo(FeatureFinishMergeDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.publish = true;

        mojo.execute();

        // Branch kept by default for merge strategy
        String branches = execCapture(tempDir, "git", "branch");
        assertThat(branches).contains("feature/test-finish");
    }

    // ── Common tests ─────────────────────────────────────────────

    @Test
    void dryRun_staysOnFeatureBranch() throws Exception {
        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Should not happen";
        mojo.publish = false;

        mojo.execute();

        assertThat(currentBranch()).isEqualTo(BRANCH_NAME);
        String pomContent = Files.readString(tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pomContent).contains("test-finish");
    }

    @Test
    void squash_emptyResult_clearsSquashMsgAndMergeMsg() throws Exception {
        // Issue #162: when the squash produces no diff (version-only
        // feature branch), the mojo skips the commit — but must clean
        // up .git/SQUASH_MSG and .git/MERGE_MSG left behind by
        // git merge --squash, or a subsequent git commit will land an
        // empty "Squashed commit of the following:" on main.
        //
        // Reduce the fixture's feature branch to a version-only history
        // by removing the substantive feature-work commit.
        exec(tempDir, "git", "rm", "feature-work.txt");
        exec(tempDir, "git", "commit", "-m", "feature: revert feature work");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Should not be used — squash is empty";
        mojo.publish = true;

        mojo.execute();

        // Ended up on main, as expected when the finish ran to completion.
        assertThat(currentBranch()).isEqualTo("main");

        // Critical: no in-progress merge state dangling for a later
        // git commit to pick up.
        assertThat(tempDir.resolve(".git/SQUASH_MSG")).doesNotExist();
        assertThat(tempDir.resolve(".git/MERGE_MSG")).doesNotExist();

        // And no empty "Squashed commit of the following:" actually landed.
        String log = execCapture(tempDir, "git", "log", "--oneline", "-5");
        assertThat(log).doesNotContain("Squashed commit of the following");
    }

    @Test
    void squash_publishRequiresMessage_throwsBeforeAnyMutation() throws Exception {
        // Issue #160: publish without -Dmessage previously NPE'd mid-operation,
        // leaving partial state. The preflight now hard-fails before any
        // VCS operation touches the working tree.
        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.publish = true;
        // No mojo.message — deliberately

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("-Dmessage=\"...\"");

        // Working tree unchanged: still on feature branch with branch-qualified version.
        assertThat(currentBranch()).isEqualTo(BRANCH_NAME);
        String pomContent = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pomContent).contains("test-finish");
    }

    @Test
    void squash_draftWithoutMessage_warnsButDoesNotThrow() throws Exception {
        // Draft is a preview; a missing -Dmessage produces a warning that
        // publish would fail, but draft itself still runs and returns.
        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.publish = false;
        // No mojo.message

        mojo.execute(); // must not throw

        assertThat(currentBranch()).isEqualTo(BRANCH_NAME);
    }

    @Test
    void wrongBranch_fails() throws Exception {
        exec(tempDir, "git", "checkout", "main");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Should fail";
        mojo.publish = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Not on " + BRANCH_NAME);
    }

    // ── VCS state file tests ─────────────────────────────────────

    @Test
    void squash_writesVcsState() throws Exception {
        // Create .ike directory to enable VCS state
        Files.createDirectories(tempDir.resolve(".ike"));
        Files.writeString(tempDir.resolve(".ike/.gitkeep"), "", StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", ".ike");
        exec(tempDir, "git", "commit", "-m", "Add .ike marker");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash with state";
        mojo.publish = true;

        mojo.execute();

        // VCS state file should exist with feature-finish action
        Path stateFile = tempDir.resolve(".ike/vcs-state");
        assertThat(stateFile).exists();
        String content = Files.readString(stateFile, StandardCharsets.UTF_8);
        assertThat(content).contains("action=feature-finish");
        assertThat(content).contains("branch=main");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String currentBranch() throws Exception {
        return execCapture(tempDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
    }

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
