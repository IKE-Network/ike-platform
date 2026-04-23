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
 * Integration tests for {@link FeatureStartDraftMojo} using real temp workspaces.
 *
 * <p>Each test creates a fresh workspace via {@link TestWorkspaceHelper},
 * configures the Mojo fields directly (package-private access), and
 * verifies git branch and POM version state after execution.
 */
class FeatureStartIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
    }

    @Test
    void featureStart_dryRun_noChanges() throws Exception {
        // Record initial state
        String libABranch = execCapture(tempDir.resolve("lib-a"), "git", "rev-parse", "--abbrev-ref", "HEAD");
        String libBBranch = execCapture(tempDir.resolve("lib-b"), "git", "rev-parse", "--abbrev-ref", "HEAD");
        String appCBranch = execCapture(tempDir.resolve("app-c"), "git", "rev-parse", "--abbrev-ref", "HEAD");

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "test-feature";
        mojo.publish = false;

        mojo.execute();

        // Verify no branches were created — all still on original branch
        assertThat(execCapture(tempDir.resolve("lib-a"), "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo(libABranch);
        assertThat(execCapture(tempDir.resolve("lib-b"), "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo(libBBranch);
        assertThat(execCapture(tempDir.resolve("app-c"), "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo(appCBranch);

        // Verify no feature branch exists in any component
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branches = execCapture(tempDir.resolve(name), "git", "branch");
            assertThat(branches).doesNotContain("feature/test-feature");
        }
    }

    @Test
    void featureStart_createsBranchesAndQualifiesVersion() throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "my-feature";
        mojo.publish = true;

        mojo.execute();

        // Verify each component is on the feature branch
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/my-feature");
        }

        // Verify POM versions contain the feature qualifier
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String pomContent = Files.readString(
                    tempDir.resolve(name).resolve("pom.xml"), StandardCharsets.UTF_8);
            assertThat(pomContent).contains("my-feature");
            assertThat(pomContent).contains("SNAPSHOT");
        }
    }

    @Test
    void featureStart_skipVersion_branchOnlyNoVersionChange() throws Exception {
        // Record original POM versions
        String libAPom = Files.readString(
                tempDir.resolve("lib-a").resolve("pom.xml"), StandardCharsets.UTF_8);
        String libBPom = Files.readString(
                tempDir.resolve("lib-b").resolve("pom.xml"), StandardCharsets.UTF_8);
        String appCPom = Files.readString(
                tempDir.resolve("app-c").resolve("pom.xml"), StandardCharsets.UTF_8);

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "skip-test";
        mojo.skipVersion = true;
        mojo.publish = true;

        mojo.execute();

        // Verify branches created
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("feature/skip-test");
        }

        // Verify POM versions unchanged
        assertThat(Files.readString(tempDir.resolve("lib-a").resolve("pom.xml"),
                StandardCharsets.UTF_8)).isEqualTo(libAPom);
        assertThat(Files.readString(tempDir.resolve("lib-b").resolve("pom.xml"),
                StandardCharsets.UTF_8)).isEqualTo(libBPom);
        assertThat(Files.readString(tempDir.resolve("app-c").resolve("pom.xml"),
                StandardCharsets.UTF_8)).isEqualTo(appCPom);
    }

    @Test
    void featureStart_removesIntraReactorPins() throws Exception {
        // Add submodules to lib-a to create an intra-reactor scenario:
        // lib-a (reactor root)
        //   ├── sub-core (leaf)
        //   └── sub-integration (depends on sub-core with explicit version pin)
        Path libA = tempDir.resolve("lib-a");

        // Rewrite lib-a as an aggregator with submodules
        Files.writeString(libA.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>lib-a</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <subprojects>
                        <subproject>sub-core</subproject>
                        <subproject>sub-integration</subproject>
                    </subprojects>
                </project>
                """, StandardCharsets.UTF_8);

        Path subCore = libA.resolve("sub-core");
        Files.createDirectories(subCore);
        Files.writeString(subCore.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>sub-core</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        Path subInteg = libA.resolve("sub-integration");
        Files.createDirectories(subInteg);
        Files.writeString(subInteg.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>sub-integration</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.test</groupId>
                            <artifactId>sub-core</artifactId>
                            <version>1.0.0-SNAPSHOT</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);

        exec(libA, "git", "add", ".");
        exec(libA, "git", "commit", "-m", "Add submodules with intra-reactor pin");

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "pin-test";
        mojo.publish = true;

        mojo.execute();

        // The intra-reactor pin should be removed:
        // sub-integration should no longer have <version> on sub-core
        String integPom = Files.readString(
                subInteg.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(integPom).contains("<artifactId>sub-core</artifactId>");
        assertThat(integPom).doesNotContain(
                "<version>1.0.0-SNAPSHOT</version>\n            <scope>test</scope>");
        // The dependency should still exist, just without explicit version
        assertThat(integPom).contains("<scope>test</scope>");
    }

    @Test
    void featureStart_removesPropertyBasedIntraReactorPins() throws Exception {
        // Same scenario but pin uses ${project.version} instead of literal
        Path libA = tempDir.resolve("lib-a");

        Files.writeString(libA.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>lib-a</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <subprojects>
                        <subproject>sub-core</subproject>
                        <subproject>sub-integration</subproject>
                    </subprojects>
                </project>
                """, StandardCharsets.UTF_8);

        Path subCore = libA.resolve("sub-core");
        Files.createDirectories(subCore);
        Files.writeString(subCore.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>sub-core</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        Path subInteg = libA.resolve("sub-integration");
        Files.createDirectories(subInteg);
        Files.writeString(subInteg.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>sub-integration</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.test</groupId>
                            <artifactId>sub-core</artifactId>
                            <version>${project.version}</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);

        exec(libA, "git", "add", ".");
        exec(libA, "git", "commit", "-m", "Add submodules with property pin");

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "prop-pin-test";
        mojo.publish = true;

        mojo.execute();

        // ${project.version} pin should also be removed from the dependency,
        // but the parent block's <version> is unrelated and stays.
        String integPom = Files.readString(
                subInteg.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(integPom).contains("<artifactId>sub-core</artifactId>");
        assertThat(integPom).doesNotContain("${project.version}");
        // dependency itself preserved
        assertThat(integPom).contains("<scope>test</scope>");
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
