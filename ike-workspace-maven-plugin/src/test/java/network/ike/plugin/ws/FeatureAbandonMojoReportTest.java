package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report tests for the {@code ws:feature-abandon-*} goal family (#767, child C
 * of epic #764): the {@code ws꞉feature-abandon-*.md} report must use the shared
 * {@link WorkingSetReportTable}, with one row per working-set member — the
 * aggregator (workspace root) included.
 *
 * <p>Because the aggregator is now a first-class table row, the staleness a
 * subproject-only table hid (the workspace root left on its branch-qualified
 * version, #763) is visible: the root's version string appears in the report.
 * The final column is {@code Effect} (a mutating goal): planned for the draft
 * variant, applied for publish.
 */
class FeatureAbandonMojoReportTest {

    private static final String FEATURE_NAME = "test-abandon";
    private static final String BRANCH_NAME = "feature/" + FEATURE_NAME;
    private static final String ROOT_VERSION = "1-test-abandon-SNAPSHOT";

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // Give the workspace root a real git working tree on the feature
        // branch, with a branch-qualified version — the #763 case the shared
        // table makes visible. The aggregator member's directory is tempDir.
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-ws</artifactId>
                    <version>%s</version>
                    <packaging>pom</packaging>
                </project>
                """.formatted(ROOT_VERSION), StandardCharsets.UTF_8);
        configureRootRepoOnFeatureBranch();

        // Put every subproject on the feature branch with a branch-qualified
        // version and a real feature commit, then point workspace.yaml at the
        // feature branch — what ws:feature-start would have produced.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path subDir = tempDir.resolve(name);

            exec(subDir, "git", "checkout", "-b", BRANCH_NAME);

            Path pom = subDir.resolve("pom.xml");
            String pomContent = Files.readString(pom, StandardCharsets.UTF_8);
            String qualified = pomContent.replaceFirst(
                    "<version>(\\d+\\.\\d+\\.\\d+)-SNAPSHOT</version>",
                    "<version>$1-test-abandon-SNAPSHOT</version>");
            Files.writeString(pom, qualified, StandardCharsets.UTF_8);
            exec(subDir, "git", "add", "pom.xml");
            exec(subDir, "git", "commit", "-m",
                    "feature: set branch-qualified version");

            Files.writeString(subDir.resolve("feature-work.txt"),
                    "feature content for " + name, StandardCharsets.UTF_8);
            exec(subDir, "git", "add", "feature-work.txt");
            exec(subDir, "git", "commit", "-m", "feature: add feature work");
        }

        Path wsYaml = helper.workspaceYaml();
        String yaml = Files.readString(wsYaml, StandardCharsets.UTF_8);
        yaml = yaml.replace("branch: main", "branch: " + BRANCH_NAME);
        Files.writeString(wsYaml, yaml, StandardCharsets.UTF_8);

        // Commit the workspace.yaml branch edit on the root repo's feature
        // branch so its working tree is clean — the abandon preflight requires
        // every working tree (the workspace root included) to be clean.
        exec(tempDir, "git", "add", "workspace.yaml");
        exec(tempDir, "git", "commit", "-m",
                "workspace: pin branches to " + BRANCH_NAME);
    }

    @Test
    void draft_report_isWorkingSetTable_withAggregatorRow_andRootVersion()
            throws Exception {
        FeatureAbandonDraftMojo mojo =
                TestLog.createMojo(FeatureAbandonDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.force = true;
        mojo.publish = false; // draft

        mojo.execute();

        String report = readReport("feature-abandon-draft");

        // The shared working-set table shape: Member / Kind / Effect columns.
        assertThat(report)
                .as("report uses the shared working-set table")
                .contains("Member")
                .contains("Kind")
                .contains("Effect");

        // Every subproject is a row.
        assertThat(report)
                .contains("lib-a")
                .contains("lib-b")
                .contains("app-c")
                .contains("subproject");

        // The aggregator (workspace root) is a row, labeled — the #763 fix.
        assertThat(report)
                .as("the aggregator row appears, labeled aggregator")
                .contains(tempDir.getFileName().toString())
                .contains("aggregator");

        // The root's stale branch-qualified version is now visible (#763) —
        // a subproject-only table would have hidden it.
        assertThat(report)
                .as("the workspace root's version is gathered like a subproject")
                .contains(ROOT_VERSION);

        // Draft phrases the Effect as planned, not applied.
        assertThat(report).contains("would abandon");

        // Preview-only: every subproject stays on the feature branch.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD"))
                    .isEqualTo(BRANCH_NAME);
        }
    }

    @Test
    void publish_report_isWorkingSetTable_withAggregatorRow_appliedEffect()
            throws Exception {
        FeatureAbandonPublishMojo mojo =
                TestLog.createMojo(FeatureAbandonPublishMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.force = true; // skip the confirmation prompt
        mojo.publish = true;

        mojo.execute();

        String report = readReport("feature-abandon-publish");

        // Shared working-set table, aggregator included and labeled.
        assertThat(report)
                .contains("Member")
                .contains("Kind")
                .contains("Effect")
                .contains("aggregator")
                .contains(tempDir.getFileName().toString());

        // Subproject rows present.
        assertThat(report)
                .contains("lib-a")
                .contains("lib-b")
                .contains("app-c")
                .contains("subproject");

        // Publish phrases the Effect as applied (past tense), not planned.
        assertThat(report)
                .as("publish reports applied effects")
                .contains("abandoned")
                .doesNotContain("would abandon");

        // Sanity: the abandon really ran — subprojects are back on main.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD"))
                    .isEqualTo("main");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Initialise the workspace root as a git repo on the feature branch,
     * mirroring the hermetic config {@link TestWorkspaceHelper} applies to
     * subprojects so the mojo's real git invocations stay machine-independent.
     */
    private void configureRootRepoOnFeatureBranch() throws Exception {
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(tempDir, "git", "config", "commit.gpgsign", "false");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        // Keep the subproject working trees and report files out of the root
        // repo's index — only the root pom.xml + workspace.yaml are tracked.
        Files.writeString(tempDir.resolve(".gitignore"), """
                lib-a/
                lib-b/
                app-c/
                .upstreams/
                .nohooks/
                *.md
                """, StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "pom.xml", "workspace.yaml", ".gitignore");
        exec(tempDir, "git", "commit", "-m", "workspace: initial root");
        exec(tempDir, "git", "checkout", "-b", BRANCH_NAME);
    }

    private String readReport(String goalStem) throws Exception {
        try (var stream = Files.list(tempDir)) {
            Path reportFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getFileName().toString().contains(goalStem))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No report file matching '" + goalStem
                                    + "' in " + tempDir));
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
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command)
                            + (output.isBlank() ? "" : "\n" + output));
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
