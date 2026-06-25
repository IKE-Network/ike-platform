package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

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
    void featureStart_draft_reportUsesFutureTense() throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "tense-check";
        mojo.publish = false; // draft

        WorkspaceReportSpec spec = mojo.runGoal();

        // Draft describes the action in the future tense, never as done
        // (#569): both the count paragraph and the per-row status read
        // "would …", not "branched"/"created".
        assertThat(spec.content())
                .contains("would be branched")
                .contains("would create");
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
    void featureStart_qualifiesVersionWhenAlreadyOnBranch() throws Exception {
        // Reproduce the pre-existing-branch scenario (ike-issues#720): a subproject
        // is already on the feature branch but its POM still carries the base
        // version (the branch was created outside feature-start). feature-start
        // must self-heal — qualify the version rather than skip it.
        Path libA = tempDir.resolve("lib-a");
        String before = Files.readString(
                libA.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(before).doesNotContain("repair-target");
        execCapture(libA, "git", "checkout", "-b", "feature/repair-target");

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "repair-target";
        mojo.publish = true;
        mojo.execute();

        // Still on the branch, and its version is now branch-qualified.
        assertThat(execCapture(libA, "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/repair-target");
        String after = Files.readString(
                libA.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(after).contains("repair-target");
        assertThat(after).contains("SNAPSHOT");
    }

    @Test
    void featureStart_draftReport_includesAggregatorRow_withRootVersion()
            throws Exception {
        // The working-set report table lists one row per member — the
        // aggregator (workspace root) included (#766/#767, epic #764). The
        // sibling fixture is the one with a workspace-root pom (1-SNAPSHOT) for
        // the aggregator row to read, so the staleness a subproject-only table
        // hid (#763) is visible in the report.
        Path primary = new TestWorkspaceHelper(tempDir).buildSiblingScenario();

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "agg-report";
        mojo.publish = false; // draft

        WorkspaceReportSpec spec = mojo.runGoal();
        String report = spec.content();

        // The working-set table headers are present.
        assertThat(report)
                .contains("Member").contains("Kind").contains("Effect");
        // The aggregator is a row, labeled — not just the subprojects.
        assertThat(report)
                .contains("primary").contains("aggregator");
        // Its version is read the same way as a subproject's — the #763 fix
        // surfaces the root version (here still 1-SNAPSHOT) and a planned
        // qualify effect.
        assertThat(report)
                .contains("1-SNAPSHOT")
                .contains("would qualify")
                .contains("1-agg-report-SNAPSHOT");
        // Subprojects remain rows too.
        assertThat(report)
                .contains("lib-a").contains("subproject");
    }

    @Test
    void featureStart_publishReport_showsQualifiedAggregatorRootVersion()
            throws Exception {
        // After a publish run the aggregator row reports the APPLIED effect:
        // the workspace root's pom is branch-qualified, and the report shows
        // the qualified version on the aggregator row (the #763 fix, applied).
        Path primary = new TestWorkspaceHelper(tempDir).buildSiblingScenario();

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "agg-applied";
        mojo.publish = true;
        mojo.execute();

        String report = readReport(primary, "feature-start");

        assertThat(report)
                .contains("Member").contains("aggregator").contains("Effect");
        // The aggregator row carries the qualified root version, proving the
        // root was branch-qualified and reported alongside the subprojects.
        assertThat(report).contains("1-agg-applied-SNAPSHOT");
    }

    @Test
    void featureStart_qualifiesAggregatorPomVersion() throws Exception {
        // feature-start branches the workspace ROOT, so it must branch-qualify the
        // aggregator's own pom too (ike-issues#721). buildSiblingScenario is the
        // fixture with a workspace-root pom+git for branchWorkspaceRepo to act on.
        Path primary = new TestWorkspaceHelper(tempDir).buildSiblingScenario();

        FeatureStartDraftMojo mojo = TestLog.createMojo(FeatureStartDraftMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "agg-test";
        mojo.publish = true;
        mojo.execute();

        // The workspace root is on the branch AND its aggregator pom is qualified.
        assertThat(execCapture(primary, "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/agg-test");
        String rootPom = Files.readString(
                primary.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(rootPom).contains("1-agg-test-SNAPSHOT");
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

    // ── --affected subset (IKE-Network/ike-issues#499) ───────────────

    @Test
    void affected_subset_branches_only_the_listed_components()
            throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(
                FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "scoped-feature";
        mojo.publish = true;
        mojo.affected = "lib-a,lib-b";

        mojo.execute();

        // lib-a and lib-b on the feature branch.
        assertThat(execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/scoped-feature");
        assertThat(execCapture(tempDir.resolve("lib-b"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/scoped-feature");

        // app-c stays on its original branch — not in the affected set.
        assertThat(execCapture(tempDir.resolve("app-c"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isNotEqualTo("feature/scoped-feature");

        // app-c's POM is also untouched: no branch-qualified version.
        String appCPom = Files.readString(
                tempDir.resolve("app-c").resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(appCPom).doesNotContain("scoped-feature");
    }

    @Test
    void affected_subset_with_single_component_branches_only_that_one()
            throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(
                FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "single";
        mojo.publish = true;
        mojo.affected = "lib-a";

        mojo.execute();

        assertThat(execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/single");
        assertThat(execCapture(tempDir.resolve("lib-b"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isNotEqualTo("feature/single");
        assertThat(execCapture(tempDir.resolve("app-c"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isNotEqualTo("feature/single");
    }

    @Test
    void affected_subset_handles_whitespace_and_trailing_commas()
            throws Exception {
        // Operators may pass the parameter via a comma-separated build
        // arg that has stray whitespace or an accidental trailing
        // separator; tolerating those keeps shell-style invocations
        // ergonomic.
        FeatureStartDraftMojo mojo = TestLog.createMojo(
                FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "punctuation";
        mojo.publish = true;
        mojo.affected = " lib-a , lib-b ,";

        mojo.execute();

        assertThat(execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/punctuation");
        assertThat(execCapture(tempDir.resolve("lib-b"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/punctuation");
    }

    @Test
    void affected_subset_rejects_unknown_subproject_names()
            throws Exception {
        FeatureStartDraftMojo mojo = TestLog.createMojo(
                FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "should-fail";
        mojo.publish = true;
        mojo.affected = "lib-a,no-such-thing";

        assertThatCode(mojo::execute)
                .hasMessageContaining("no-such-thing")
                .hasMessageContaining("Unknown subproject");

        // And nothing got branched — the goal failed before touching
        // any working tree.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD"))
                    .isNotEqualTo("feature/should-fail");
        }
    }

    @Test
    void affected_subset_blank_falls_back_to_full_workspace()
            throws Exception {
        // Empty / blank --affected is treated the same as omitting it
        // — full-workspace branching, matching the historical behaviour.
        FeatureStartDraftMojo mojo = TestLog.createMojo(
                FeatureStartDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = "full";
        mojo.publish = true;
        mojo.affected = "   ";

        mojo.execute();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD"))
                    .isEqualTo("feature/full");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Read the goal's markdown report from a workspace root. The report file
     * is named {@code ws꞉<goal>.md}; matching by substring tolerates the
     * colon-substitution character without hard-coding it.
     */
    private String readReport(Path workspaceRoot, String goalStem)
            throws Exception {
        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            Path reportFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getFileName().toString().contains(goalStem))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No report file matching '" + goalStem
                                    + "' in " + workspaceRoot));
            return Files.readString(reportFile, StandardCharsets.UTF_8);
        }
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
