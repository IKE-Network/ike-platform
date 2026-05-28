package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for feature-finish goals using real temp workspaces.
 *
 * <p>Tests squash-merge (default strategy) and no-ff merge across
 * workspace components.
 */
class FeatureFinishIntegrationTest {

    private static final String FEATURE_NAME = "test-finish";
    private static final String BRANCH_NAME = "feature/" + FEATURE_NAME;

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path subDir = tempDir.resolve(name);

            exec(subDir, "git", "checkout", "-b", BRANCH_NAME);

            Path pom = subDir.resolve("pom.xml");
            String pomContent = Files.readString(pom, StandardCharsets.UTF_8);
            String qualified = pomContent.replaceFirst(
                    "<version>(\\d+\\.\\d+\\.\\d+)-SNAPSHOT</version>",
                    "<version>$1-test-finish-SNAPSHOT</version>");
            Files.writeString(pom, qualified, StandardCharsets.UTF_8);

            exec(subDir, "git", "add", "pom.xml");
            exec(subDir, "git", "commit", "-m",
                    "feature: set branch-qualified version");

            // Add actual feature work (beyond just version change)
            Files.writeString(subDir.resolve("feature-work.txt"),
                    "feature content for " + name, StandardCharsets.UTF_8);
            exec(subDir, "git", "add", "feature-work.txt");
            exec(subDir, "git", "commit", "-m", "feature: add feature work");
        }

        Path wsYaml = helper.workspaceYaml();
        String yaml = Files.readString(wsYaml, StandardCharsets.UTF_8);
        yaml = yaml.replace("version: \"1.0.0-SNAPSHOT\"",
                            "version: \"1.0.0-test-finish-SNAPSHOT\"");
        yaml = yaml.replace("version: \"2.0.0-SNAPSHOT\"",
                            "version: \"2.0.0-test-finish-SNAPSHOT\"");
        yaml = yaml.replace("version: \"3.0.0-SNAPSHOT\"",
                            "version: \"3.0.0-test-finish-SNAPSHOT\"");
        // Update branch fields to match git — simulating what ws:feature-start does
        yaml = yaml.replace("branch: main", "branch: " + BRANCH_NAME);
        Files.writeString(wsYaml, yaml, StandardCharsets.UTF_8);
    }

    @Test
    void squash_dryRun_noMerge() throws Exception {
        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Should not happen";
        mojo.publish = false;

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo(BRANCH_NAME);
        }
    }

    @Test
    void squash_mergesAndStripsVersions() throws Exception {
        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash feature";
        mojo.publish = true;

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("main");
        }

        // Versions stripped
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String pomContent = Files.readString(
                    tempDir.resolve(name).resolve("pom.xml"), StandardCharsets.UTF_8);
            assertThat(pomContent).doesNotContain("test-finish");
            assertThat(pomContent).contains("SNAPSHOT");
        }

        // Squash commits present (no "Merge" commits)
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String log = execCapture(tempDir.resolve(name),
                    "git", "log", "--oneline", "-3");
            assertThat(log).contains("Squash feature");
        }
    }

    @Test
    void merge_preservesHistory() throws Exception {
        FeatureFinishMergeDraftMojo mojo = TestLog.createMojo(FeatureFinishMergeDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.publish = true;

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("main");
        }

        // Merge commits present
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String log = execCapture(tempDir.resolve(name),
                    "git", "log", "--oneline", "-5");
            assertThat(log).contains(BRANCH_NAME);
        }
    }

    @Test
    void inconsistentYamlBranch_skipsComponent() throws Exception {
        // Set workspace.yaml branches back to "main" while git is on feature
        Path wsYaml = helper.workspaceYaml();
        String yaml = Files.readString(wsYaml, StandardCharsets.UTF_8);
        yaml = yaml.replace("branch: " + BRANCH_NAME, "branch: main");
        Files.writeString(wsYaml, yaml, StandardCharsets.UTF_8);

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Should skip all";
        mojo.publish = true;

        mojo.execute();

        // All components should still be on the feature branch — the
        // consistency check should have skipped them all.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo(BRANCH_NAME);
        }
    }

    // ── #535: workspace.yaml reconciliation across partial runs ──

    @Test
    void squash_priorRunLeftYamlStale_reconcilesAlongsideFreshSquashes()
            throws Exception {
        // Simulate the user's two-step recovery from #532's pre-fix
        // abort: lib-a was processed by a prior run (local now on main,
        // local feature branch deleted) but workspace.yaml still pins
        // it to feature/test-finish. The current invocation should
        // squash lib-b and app-c AND reconcile lib-a's yaml entry, not
        // leave it stale.
        Path libA = tempDir.resolve("lib-a");
        exec(libA, "git", "checkout", "main");
        // Roll lib-a's main forward to where the squash would have left
        // it: just take the feature branch's final state as a single
        // commit on main.
        exec(libA, "git", "merge", "--squash", BRANCH_NAME);
        exec(libA, "git", "commit", "-m", "Prior-run squash of "
                + BRANCH_NAME);
        // Mirror the prior-run cleanup — local feature branch gone.
        exec(libA, "git", "branch", "-D", BRANCH_NAME);

        // Sanity-check the precondition: git on main, yaml still on
        // feature.
        String yaml = Files.readString(helper.workspaceYaml(),
                StandardCharsets.UTF_8);
        assertThat(yaml)
                .as("precondition: yaml still pins lib-a to feature branch")
                .contains("lib-a:")
                .contains("branch: " + BRANCH_NAME);

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(
                FeatureFinishSquashDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Finish across partial-run boundary";
        mojo.publish = true;

        mojo.execute();

        // Every subproject ends up on main.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD"))
                    .as("subproject " + name + " should end on main")
                    .isEqualTo("main");
        }

        // workspace.yaml's lib-a branch is now main, not the stale
        // feature branch. Check the lib-a section specifically — the
        // workspace-level defaults block can still reference the
        // feature branch without affecting subproject pinning.
        String yamlAfter = Files.readString(helper.workspaceYaml(),
                StandardCharsets.UTF_8);
        int libAStart = yamlAfter.indexOf("  lib-a:");
        int libAEnd = yamlAfter.indexOf("  lib-b:");
        assertThat(libAStart).as("lib-a section must exist").isGreaterThan(0);
        assertThat(libAEnd).as("lib-b section must exist").isGreaterThan(libAStart);
        String libASection = yamlAfter.substring(libAStart, libAEnd);
        assertThat(libASection)
                .as("lib-a's workspace.yaml branch must be reconciled (#535)")
                .contains("branch: main")
                .doesNotContain("branch: " + BRANCH_NAME);
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
