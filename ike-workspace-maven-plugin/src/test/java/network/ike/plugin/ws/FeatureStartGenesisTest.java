package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IKE-Network/ike-issues#948: {@code ws:feature-start} must treat the
 * workspace root as a first-class working-set member. A working set with
 * ZERO declared subprojects is the genesis state of every new workspace —
 * feature-start must still branch the root (and qualify the aggregator
 * version), or the canonical bootstrap `feature-start → ws:add -Dbranch`
 * deadlocks on the #286 branch-coherence check.
 */
class FeatureStartGenesisTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects: {}
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>genesis-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".gitignore"),
                ".ike/vcs-state\n", StandardCharsets.UTF_8);

        exec(tempDir, "git", "init", "-b", "main");
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec(tempDir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(tempDir, "git", "config", "commit.gpgsign", "false");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "genesis workspace");
    }

    @Test
    void publish_zeroSubprojects_branchesTheRoot_andQualifiesAggregator()
            throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.feature = "genesis-feature";
        mojo.publish = true;

        mojo.execute();

        assertThat(capture(tempDir, "git", "branch", "--show-current"))
                .as("genesis feature-start must branch the workspace root (#948)")
                .isEqualTo("feature/genesis-feature");
        assertThat(Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .as("the aggregator version gets the branch qualifier (#721)")
                .contains("<version>1-genesis-feature-SNAPSHOT</version>");
    }

    @Test
    void draft_zeroSubprojects_previewsOnly_rootStaysOnMain() throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.feature = "genesis-feature";
        mojo.publish = false;

        mojo.execute();

        assertThat(capture(tempDir, "git", "branch", "--show-current"))
                .as("a draft must not branch the root")
                .isEqualTo("main");
        assertThat(Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .contains("<version>1-SNAPSHOT</version>");
    }

    // ── helpers ──────────────────────────────────────────────────

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + output);
        }
    }

    private static String capture(Path workDir, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(false)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command));
        }
        return output.trim();
    }
}
