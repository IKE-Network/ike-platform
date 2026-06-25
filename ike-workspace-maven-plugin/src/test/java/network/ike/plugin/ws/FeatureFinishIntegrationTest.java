package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

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
    void merge_dequalifiesAggregator_rootPomAndWorkspaceYaml()
            throws Exception {
        // The default setUp qualifies the subprojects + workspace.yaml but
        // leaves the workspace ROOT git-less. Build a coherent aggregator
        // timeline — main holds the base state, feature/test-finish holds the
        // qualified state, exactly what ws:feature-start produces — so finish
        // must de-qualify the root pom AND the manifest version fields
        // (ike-issues#768/#763), not just the subprojects.
        Path wsYaml = helper.workspaceYaml();
        // setUp left this qualified + branches=feature.
        String qualifiedYaml = Files.readString(wsYaml, StandardCharsets.UTF_8);
        String baseYaml = qualifiedYaml
                .replace("-test-finish-SNAPSHOT", "-SNAPSHOT")
                .replace("branch: " + BRANCH_NAME, "branch: main");
        String basePom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>aggregator-root</artifactId>
                    <version>9.9.9-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """;

        // main = base aggregator state. The subproject dirs are their own git
        // repos, so the aggregator repo .gitignores them (as a real workspace
        // does) — otherwise the finish precondition sees them as uncommitted
        // changes.
        Files.writeString(wsYaml, baseYaml, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), basePom, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".gitignore"),
                "/lib-a/\n/lib-b/\n/app-c/\n*.md\n", StandardCharsets.UTF_8);
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "commit.gpgsign", "false");
        exec(tempDir, "git", "config", "user.email", "root@example.com");
        exec(tempDir, "git", "config", "user.name", "Root");
        exec(tempDir, "git", "add", "pom.xml", "workspace.yaml", ".gitignore");
        exec(tempDir, "git", "commit", "-m", "base workspace root");

        // feature/test-finish = qualified aggregator state.
        exec(tempDir, "git", "checkout", "-b", BRANCH_NAME);
        Files.writeString(wsYaml, qualifiedYaml, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"),
                basePom.replace("9.9.9-SNAPSHOT", "9.9.9-test-finish-SNAPSHOT"),
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "pom.xml", "workspace.yaml");
        exec(tempDir, "git", "commit", "-m", "feature: qualify aggregator");

        FeatureFinishMergeDraftMojo mojo =
                TestLog.createMojo(FeatureFinishMergeDraftMojo.class);
        mojo.manifest = wsYaml.toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.publish = true;
        mojo.execute();

        // The aggregator is back on main, fully de-qualified — the #763 fix.
        assertThat(execCapture(tempDir, "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("main");
        String rootPom = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(rootPom).contains("<version>9.9.9-SNAPSHOT</version>");
        assertThat(rootPom).doesNotContain("test-finish");
        assertThat(Files.readString(wsYaml, StandardCharsets.UTF_8))
                .as("workspace.yaml version fields de-qualified (#763)")
                .doesNotContain("-test-finish-SNAPSHOT");
        // #768 acceptance: no qualifier anywhere in the working set.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(Files.readString(tempDir.resolve(name).resolve("pom.xml"),
                    StandardCharsets.UTF_8)).doesNotContain("test-finish");
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

    // ── #544: per-subproject squash kind + target HEAD in report ──

    @Test
    void squash_report_includesTargetHeadShaAndVersionOnlyMarker()
            throws Exception {
        // Roll lib-a's feature branch back so it has only the
        // version-qualifier commit — i.e. nothing the squash would
        // commit on main. lib-b and app-c keep their feature-work
        // commits as the helper set them up.
        Path libA = tempDir.resolve("lib-a");
        exec(libA, "git", "rm", "feature-work.txt");
        exec(libA, "git", "commit", "-m", "feature: revert feature work");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(
                FeatureFinishSquashDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "#544 report shape";
        mojo.publish = true;
        mojo.execute();

        String report = readReport("feature-finish-squash-publish");

        // #764: the report now renders through the shared
        // WorkingSetReportTable — uniform Member/Kind/Version/Branch/SHA/
        // Effect columns rather than the old Subproject/Status/<target> HEAD.
        assertThat(report)
                .as("table must use the shared working-set columns")
                .contains("Member")
                .contains("Kind")
                .contains("Version")
                .contains("Effect");

        // lib-a's row is the version-only no-op (after we removed
        // its feature-work commit). The Effect text now exposes this
        // explicitly rather than collapsing it into the count.
        assertThat(report)
                .as("version-only row must be marked, not silently absorbed")
                .contains("lib-a")
                .contains("version-only");

        // lib-b and app-c shipped real content, so their row's
        // target-HEAD SHA differs from main's pre-squash state.
        // Read the actual SHAs and assert they appear in the report
        // (shortened to 7 chars).
        String libBHead = execCapture(tempDir.resolve("lib-b"),
                "git", "rev-parse", "--short=7", "HEAD").trim();
        String appCHead = execCapture(tempDir.resolve("app-c"),
                "git", "rev-parse", "--short=7", "HEAD").trim();
        assertThat(report)
                .as("real-content subprojects expose their target SHA")
                .contains(libBHead)
                .contains(appCHead);

        // #763/#764: the workspace-root aggregator now has its own row,
        // labelled "aggregator" — it was previously absent from the table
        // even though the workspace repo is squash-merged like a subproject.
        assertThat(report)
                .as("the workspace-root aggregator must appear as a row")
                .contains("aggregator")
                .contains(tempDir.getFileName().toString());
    }

    // ── #763/#764: aggregator row carries the root POM version ──

    @Test
    void squash_report_includesAggregatorRowWithRootVersion()
            throws Exception {
        // Give the workspace root a real git repo + aggregator POM so the
        // aggregator row can carry a concrete version (the #763 fix: the
        // root's readPomVersion is now gathered like a subproject's). The
        // default helper scaffold leaves the root pom-less and git-less.
        String rootVersion = "9.9.9-aggregator-SNAPSHOT";
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>aggregator-root</artifactId>
                    <version>%s</version>
                    <packaging>pom</packaging>
                </project>
                """.formatted(rootVersion), StandardCharsets.UTF_8);
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "commit.gpgsign", "false");
        exec(tempDir, "git", "config", "user.email", "root@example.com");
        exec(tempDir, "git", "config", "user.name", "Root");
        exec(tempDir, "git", "add", "pom.xml", "workspace.yaml");
        exec(tempDir, "git", "commit", "-m", "Initial workspace root");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(
                FeatureFinishSquashDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "aggregator-row report";
        mojo.publish = false; // draft — read-only, leaves the tree intact
        mojo.execute();

        String report = readReport("feature-finish-squash-draft");

        // The aggregator row is present, labelled "aggregator", named for
        // the workspace-root directory, and — the #763 fix — carries the
        // root POM's version that a subproject-only table would have hidden.
        assertThat(report)
                .as("aggregator row must appear with its kind label")
                .contains("aggregator");
        assertThat(report)
                .as("aggregator row must be named for the workspace-root dir")
                .contains(tempDir.getFileName().toString());
        assertThat(report)
                .as("#763 fix: the root POM version must surface in the table")
                .contains(rootVersion);
    }

    // ── #569: draft report quality (preview effects + remediation) ──

    @Test
    void merge_draft_previewsEffectsAndPublishCommand() throws Exception {
        FeatureFinishMergeDraftMojo mojo = TestLog.createMojo(
                FeatureFinishMergeDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.publish = false; // draft

        mojo.execute();

        // Preview-only: every subproject stays on the feature branch.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD"))
                    .isEqualTo(BRANCH_NAME);
        }

        // The draft report now previews the consequential effects and
        // emits a copy-pasteable publish command (#569), reaching parity
        // with the squash variant.
        String report = readReport("feature-finish-merge-draft");
        assertThat(report)
                .contains("would merge")
                .contains("What publish will do")
                .contains("To publish")
                .contains("mvn " + WsGoal.FEATURE_FINISH_MERGE_PUBLISH.qualified());
    }

    @Test
    void squash_draft_predictsVersionOnlyNoop() throws Exception {
        // Revert lib-a's feature work so its branch carries only the
        // version-qualifier commit — a version-only branch the squash
        // would not commit on main.
        Path libA = tempDir.resolve("lib-a");
        exec(libA, "git", "rm", "feature-work.txt");
        exec(libA, "git", "commit", "-m", "feature: revert feature work");

        FeatureFinishSquashDraftMojo mojo = TestLog.createMojo(
                FeatureFinishSquashDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "draft prediction";
        mojo.publish = false; // draft

        mojo.execute();

        // Draft predicts lib-a as version-only while lib-b / app-c are
        // real content squashes — no longer a blanket "would squash".
        String report = readReport("feature-finish-squash-draft");
        assertThat(report)
                .contains("version-only — no commit expected")
                .contains("would squash");

        // Preview-only: lib-a stays on the feature branch.
        assertThat(execCapture(libA, "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo(BRANCH_NAME);
    }

    private String readReport(String goalStem) throws Exception {
        try (Stream<Path> stream = Files.list(tempDir)) {
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
