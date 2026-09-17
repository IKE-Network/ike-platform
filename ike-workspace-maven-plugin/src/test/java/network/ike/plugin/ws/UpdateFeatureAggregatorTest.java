package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ws:update-feature} and the working-set root
 * (IKE-Network/ike-issues#1099): when the aggregator is on the feature branch
 * it is merged from main like a subproject, and the manifest conflict a
 * checkpoint creates by adjacency — feature-owned {@code branch:} fields and
 * version qualifiers next to target-owned {@code sha:} pins — is resolved by
 * construction. Any other conflict in the root is reported after the merge
 * is aborted, so the root is left exactly as found.
 */
class UpdateFeatureAggregatorTest {

    private static final String FEATURE = "long-lived";
    private static final String BRANCH = "feature/" + FEATURE;
    private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String SHA_C = "cccccccccccccccccccccccccccccccccccccccc";

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;
    private Path manifest;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
        manifest = helper.workspaceYaml();

        // The subprojects sit on the feature branch, level with main: the root
        // is the subject here.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "checkout", "-b", BRANCH);
        }

        // The aggregator as feature-start leaves it: main holds the base
        // manifest, the feature branch holds branch fields on the feature and
        // feature-qualified versions. Subproject directories and goal reports
        // are ignored, as in a real working set.
        String baseYaml = Files.readString(manifest, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), rootPom("1-SNAPSHOT", "176"),
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".gitignore"),
                "/lib-a/\n/lib-b/\n/app-c/\n*.md\nws\uA789*.md\n.ike/\n",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("NOTES.txt"), "base notes\n",
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "init", "-b", "main");
        hermetic(tempDir);
        exec(tempDir, "git", "add", "pom.xml", "workspace.yaml", ".gitignore", "NOTES.txt");
        exec(tempDir, "git", "commit", "-m", "base workspace root");

        exec(tempDir, "git", "checkout", "-b", BRANCH);
        String featureYaml = baseYaml
                .replace("    branch: main", "    branch: " + BRANCH)
                .replace("-SNAPSHOT\"", "-" + FEATURE + "-SNAPSHOT\"");
        Files.writeString(manifest, featureYaml, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"),
                rootPom("1-" + FEATURE + "-SNAPSHOT", "176"), StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "pom.xml", "workspace.yaml");
        exec(tempDir, "git", "commit", "-m",
                "feature: qualify workspace root + branches for " + BRANCH);
    }

    @Test
    void publish_rootOnFeature_absorbsCheckpointResolvingTheManifest() throws Exception {
        checkpointOnMain();

        runUpdate(true);

        assertThat(execCapture(tempDir, "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .as("the root stays on the feature branch").isEqualTo(BRANCH);
        assertThat(execCapture(tempDir, "git", "rev-list", "--count", BRANCH + "..main"))
                .as("the root's feature branch is level with main").isEqualTo("0");
        assertThat(execCapture(tempDir, "git", "status", "--porcelain"))
                .as("no uncommitted changes left in the root").isEmpty();
        assertThat(tempDir.resolve(".git").resolve("MERGE_HEAD")).doesNotExist();
        assertThat(execCapture(tempDir, "git", "rev-list", "--parents", "-1", "HEAD").split(" "))
                .as("HEAD is a merge commit with both parents").hasSize(3);

        String yaml = Files.readString(manifest, StandardCharsets.UTF_8);
        assertThat(yaml).doesNotContain("<<<<<<<").doesNotContain(">>>>>>>");
        assertThat(occurrences(yaml, "    branch: " + BRANCH))
                .as("every subproject keeps its feature branch field").isEqualTo(3);
        assertThat(yaml)
                .as("the checkpoint's pins are taken from main")
                .contains("sha: \"" + SHA_A + "\"")
                .contains("sha: \"" + SHA_B + "\"")
                .contains("sha: \"" + SHA_C + "\"");
        assertThat(yaml)
                .as("the feature qualifier rides main's moved numeric base")
                .contains("1.1.0-" + FEATURE + "-SNAPSHOT")
                .contains("2.0.0-" + FEATURE + "-SNAPSHOT")
                .contains("3.0.0-" + FEATURE + "-SNAPSHOT")
                .doesNotContain("1.0.0-");
        assertThat(yaml)
                .as("the defaults block is main's, untouched")
                .contains("defaults:\n  branch: main");
        assertThat(Files.readString(tempDir.resolve("pom.xml"), StandardCharsets.UTF_8))
                .as("the pom auto-merged: parent repin taken, feature version kept")
                .contains("<version>177</version>")
                .contains("<version>1-" + FEATURE + "-SNAPSHOT</version>");

        String row = aggregatorRow(readReport("update-feature"));
        assertThat(row).contains("merged `main`").contains("resolved by construction");
        assertThat(readReport("update-feature")).doesNotContain("skipped (aggregator)");
    }

    @Test
    void draft_rootOnFeature_predictsTheManifestResolutionWithoutTouchingTheRoot()
            throws Exception {
        checkpointOnMain();
        String head = execCapture(tempDir, "git", "rev-parse", "HEAD");
        String featureYaml = Files.readString(manifest, StandardCharsets.UTF_8);

        runUpdate(false);

        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(tempDir.resolve(".git").resolve("MERGE_HEAD")).doesNotExist();
        assertThat(Files.readString(manifest, StandardCharsets.UTF_8)).isEqualTo(featureYaml);
        String row = aggregatorRow(readReport("update-feature"));
        assertThat(row).contains("update expected").contains("resolved by construction")
                .contains("1 behind");
    }

    @Test
    void publish_conflictBeyondTheManifest_leavesTheRootAsFoundAndNamesTheFile()
            throws Exception {
        // Both sides edit NOTES.txt differently: a real conflict, not the
        // manifest's structural one.
        Files.writeString(tempDir.resolve("NOTES.txt"), "feature notes\n",
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "NOTES.txt");
        exec(tempDir, "git", "commit", "-m", "feature: notes");
        checkpointOnMain("main notes\n");
        String head = execCapture(tempDir, "git", "rev-parse", "HEAD");
        String featureYaml = Files.readString(manifest, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> runUpdate(true))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("workspace root");

        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD"))
                .as("the root's feature branch did not move").isEqualTo(head);
        assertThat(tempDir.resolve(".git").resolve("MERGE_HEAD"))
                .as("the merge was aborted, not left in progress").doesNotExist();
        assertThat(execCapture(tempDir, "git", "status", "--porcelain")).isEmpty();
        assertThat(Files.readString(manifest, StandardCharsets.UTF_8))
                .as("the manifest is the feature side, marker-free").isEqualTo(featureYaml);
    }

    @Test
    void publish_rootNotOnFeature_isSkippedAndReported() throws Exception {
        exec(tempDir, "git", "checkout", "main");
        String head = execCapture(tempDir, "git", "rev-parse", "HEAD");

        runUpdate(true);

        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(aggregatorRow(readReport("update-feature")))
                .contains("skipped (not on `" + BRANCH + "`)");
    }

    @Test
    void publish_rootAlreadyLevelWithMain_isUpToDate() throws Exception {
        String head = execCapture(tempDir, "git", "rev-parse", "HEAD");

        runUpdate(true);

        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(aggregatorRow(readReport("update-feature")))
                .contains("up to date with `main`");
    }

    // ── Scenario ─────────────────────────────────────────────────────

    /**
     * A checkpoint on main after the branch point: a {@code sha:} pin
     * inserted under every subproject's {@code branch:} line — adjacent to
     * the lines the feature branch rewrote — and lib-a's numeric base moved.
     */
    private void checkpointOnMain() throws Exception {
        checkpointOnMain(null);
    }

    private void checkpointOnMain(String notes) throws Exception {
        exec(tempDir, "git", "checkout", "main");
        String yaml = Files.readString(manifest, StandardCharsets.UTF_8);
        yaml = yaml.replace("    branch: main\n    version: \"1.0.0-SNAPSHOT\"",
                "    branch: main\n    sha: \"" + SHA_A + "\"\n    version: \"1.1.0-SNAPSHOT\"");
        yaml = yaml.replace("    branch: main\n    version: \"2.0.0-SNAPSHOT\"",
                "    branch: main\n    sha: \"" + SHA_B + "\"\n    version: \"2.0.0-SNAPSHOT\"");
        yaml = yaml.replace("    branch: main\n    version: \"3.0.0-SNAPSHOT\"",
                "    branch: main\n    sha: \"" + SHA_C + "\"\n    version: \"3.0.0-SNAPSHOT\"");
        Files.writeString(manifest, yaml, StandardCharsets.UTF_8);
        // The parent repin that rides along with a checkpoint (176 -> 177).
        Files.writeString(tempDir.resolve("pom.xml"), rootPom("1-SNAPSHOT", "177"),
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "workspace.yaml", "pom.xml");
        if (notes != null) {
            Files.writeString(tempDir.resolve("NOTES.txt"), notes, StandardCharsets.UTF_8);
            exec(tempDir, "git", "add", "NOTES.txt");
        }
        exec(tempDir, "git", "commit", "-m", "checkpoint: main-20260916-142630");
        exec(tempDir, "git", "checkout", BRANCH);
    }

    private void runUpdate(boolean publish) throws Exception {
        UpdateFeatureDraftMojo mojo = TestLog.createMojo(UpdateFeatureDraftMojo.class);
        mojo.manifest = manifest.toFile();
        mojo.feature = FEATURE;
        mojo.targetBranch = "main";
        mojo.publish = publish;
        mojo.execute();
    }

    private static String rootPom(String version, String parentVersion) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>test-parent</artifactId>
                        <version>%s</version>
                        <relativePath/>
                    </parent>
                    <groupId>com.test</groupId>
                    <artifactId>aggregator-root</artifactId>
                    <version>%s</version>
                    <packaging>pom</packaging>
                </project>
                """.formatted(parentVersion, version);
    }

    private void hermetic(Path dir) throws Exception {
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks-root"));
        exec(dir, "git", "config", "core.hooksPath", noHooks.toAbsolutePath().toString());
        exec(dir, "git", "config", "commit.gpgsign", "false");
        exec(dir, "git", "config", "user.email", "root@example.com");
        exec(dir, "git", "config", "user.name", "Root");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    /** The working-set report's row for the aggregator. */
    private static String aggregatorRow(String report) {
        return report.lines()
                .filter(line -> line.contains("| aggregator |"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no aggregator row in report:\n" + report));
    }

    private String readReport(String goalStem) throws Exception {
        try (Stream<Path> stream = Files.list(tempDir)) {
            Path reportFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getFileName().toString().contains(goalStem))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No report file matching '" + goalStem + "' in " + tempDir));
            return Files.readString(reportFile, StandardCharsets.UTF_8);
        }
    }

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit " + exitCode + "): "
                    + String.join(" ", command) + (output.isBlank() ? "" : "\n" + output));
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
            throw new RuntimeException("Command failed (exit " + exitCode + "): "
                    + String.join(" ", command));
        }
        return output;
    }
}
