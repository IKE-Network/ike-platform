package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link FeatureStartSiblingPublishMojo}
 * (IKE-Network/ike-issues#207, reshaped in #770) using real temp workspaces
 * with bare {@code file://} upstreams.
 *
 * <p>{@link TestWorkspaceHelper#buildSiblingScenario()} lays out a primary
 * workspace at {@code <tempDir>/primary} (cloned root + components); the goal
 * produces the sibling at {@code <tempDir>/primary-<feature>}.
 */
class FeatureStartSiblingPublishIntegrationTest {

    private static final List<String> COMPONENTS = List.of("lib-a", "lib-b", "app-c");

    @TempDir
    Path tempDir;

    private Path primary;

    @BeforeEach
    void setUp() throws Exception {
        primary = new TestWorkspaceHelper(tempDir).buildSiblingScenario();
    }

    @Test
    void siblingCreate_producesIsolatedCloneOnFeatureBranch() throws Exception {
        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-456";

        mojo.execute();

        Path sibling = tempDir.resolve("primary꞉jira-456");

        // 1. Expected directory layout: a self-contained clone of the root
        //    plus every component.
        assertThat(sibling).isDirectory();
        assertThat(sibling.resolve("workspace.yaml")).exists();
        assertThat(sibling.resolve("pom.xml")).exists();
        assertThat(sibling.resolve(".git")).isDirectory();
        for (String name : COMPONENTS) {
            assertThat(sibling.resolve(name).resolve(".git")).isDirectory();
        }

        // 2. Root + every component on the feature branch in the sibling.
        assertThat(branch(sibling)).isEqualTo("feature/jira-456");
        for (String name : COMPONENTS) {
            assertThat(branch(sibling.resolve(name))).isEqualTo("feature/jira-456");
        }

        // 3. POM versions are branch-qualified in the sibling.
        for (String name : COMPONENTS) {
            String pom = Files.readString(
                    sibling.resolve(name).resolve("pom.xml"), StandardCharsets.UTF_8);
            assertThat(pom).contains("jira-456").contains("SNAPSHOT");
        }
        // 3a. The aggregator's OWN pom is qualified too (#777) — not just the
        //     subprojects. This is the gap the sibling path used to miss.
        assertThat(Files.readString(sibling.resolve("pom.xml"), StandardCharsets.UTF_8))
                .as("aggregator root pom is branch-qualified")
                .contains("jira-456").contains("SNAPSHOT");

        // 4. The sibling's workspace.yaml carries the feature branch.
        String yaml = Files.readString(
                sibling.resolve("workspace.yaml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("branch: feature/jira-456");

        // 5. Each sibling component's origin points at the real upstream — not
        //    the primary's local path (proving --reference did not leak into
        //    the remote, which would break ws:push).
        String originLibA = execCapture(sibling.resolve("lib-a"),
                "git", "remote", "get-url", "origin");
        assertThat(originLibA).contains("upstream-lib-a.git");
        assertThat(originLibA).doesNotContain(File.separator + "primary" + File.separator);

        // 6. The primary is untouched: still on main, versions not qualified
        //    — including the primary's own aggregator root pom.
        assertThat(branch(primary)).isEqualTo("main");
        assertThat(Files.readString(primary.resolve("pom.xml"), StandardCharsets.UTF_8))
                .as("primary aggregator root pom is left untouched")
                .doesNotContain("jira-456");
        for (String name : COMPONENTS) {
            assertThat(branch(primary.resolve(name))).isEqualTo("main");
            String pom = Files.readString(
                    primary.resolve(name).resolve("pom.xml"), StandardCharsets.UTF_8);
            assertThat(pom).doesNotContain("jira-456");
        }
    }

    /**
     * Every repo the goal creates carries a repo-local
     * {@code core.fsmonitor=false}: on macOS the {@code --dissociate}
     * repack queries the fresh clone's just-spawned fsmonitor daemon, which
     * under a file watcher such as Syncthing reliably never answers,
     * deadlocking the goal (IKE-Network/ike-issues#1052).
     */
    @Test
    void siblingCreate_optsEveryCloneOutOfFsmonitor() throws Exception {
        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-456";

        mojo.execute();

        Path sibling = tempDir.resolve("primary꞉jira-456");
        assertThat(execCapture(sibling, "git", "config", "--local", "core.fsmonitor"))
                .as("sibling root clone opts out of fsmonitor (#1052)")
                .isEqualTo("false");
        for (String name : COMPONENTS) {
            assertThat(execCapture(sibling.resolve(name),
                    "git", "config", "--local", "core.fsmonitor"))
                    .as(name + " clone opts out of fsmonitor (#1052)")
                    .isEqualTo("false");
        }
    }

    @Test
    void siblingCreate_refusesWhenSiblingDirectoryAlreadyExists() throws Exception {
        Files.createDirectories(tempDir.resolve("primary꞉dup"));

        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "dup";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("already exists");

        // The pre-existing directory is left untouched — nothing was cloned.
        assertThat(tempDir.resolve("primary꞉dup").resolve("workspace.yaml"))
                .doesNotExist();
    }

    @Test
    void siblingCreate_rejectsFilesystemUnsafeFeatureName() throws Exception {
        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "bad/name";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("filesystem-safe");

        // No sibling materialized for the rejected name.
        assertThat(tempDir.resolve("primary꞉bad")).doesNotExist();
    }

    @Test
    void siblingCreate_report_includesAggregatorRowWithRootVersion()
            throws Exception {
        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-456";

        mojo.execute();

        // The report lands as ws꞉feature-start-sibling-publish.md at the root.
        String report = readReport("feature-start-sibling-publish");

        // Migrated to the shared working-set table: a Member · Kind grid with
        // an Effect final column (mutating goal).
        assertThat(report)
                .as("working-set table headers")
                .contains("Member").contains("Kind").contains("Effect");

        // Every subproject is a row, labeled subproject.
        for (String name : COMPONENTS) {
            assertThat(report)
                    .as("subproject row for " + name)
                    .contains(name);
        }
        assertThat(report).contains("subproject");

        // The aggregator (workspace root) is a row — the #763 gap closed: the
        // sibling root's clone is no longer invisible. The root dir is named
        // "primary"; its base version 1-SNAPSHOT is now branch-qualified to
        // 1-jira-456-SNAPSHOT (the #777 fix — the aggregator pom is qualified
        // like every subproject, and the report surfaces the qualified value).
        assertThat(report)
                .as("aggregator row present and labeled")
                .contains("primary").contains("aggregator");
        assertThat(report)
                .as("the aggregator (root) version is qualified — the #777 fix")
                .contains("1-jira-456-SNAPSHOT");

        // The effect states what was applied to each cloned member; the
        // aggregator's effect now carries its qualified version too (#777).
        assertThat(report)
                .contains("cloned + branched feature/jira-456 → 1-jira-456-SNAPSHOT");

        // The base branch the sibling was cut from is surfaced.
        assertThat(report).as("base branch in report").contains("main");
    }

    @Test
    void siblingCreate_skipVersion_branchesWithoutQualifyingVersions()
            throws Exception {
        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "docs";
        mojo.skipVersion = true;

        mojo.execute();

        Path sibling = tempDir.resolve("primary꞉docs");
        for (String name : COMPONENTS) {
            assertThat(branch(sibling.resolve(name))).isEqualTo("feature/docs");
        }
        // Version left at its base value — no branch qualifier applied.
        String libAPom = Files.readString(
                sibling.resolve("lib-a").resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(libAPom).contains("1.0.0-SNAPSHOT").doesNotContain("docs");
        // The aggregator root pom is likewise left unqualified under skipVersion
        // (the #777 root-pom qualification is gated on !skipVersion).
        assertThat(Files.readString(sibling.resolve("pom.xml"), StandardCharsets.UTF_8))
                .doesNotContain("docs");
    }

    @Test
    void siblingCreate_onFeatureBranch_withoutFrom_refusesWithGuard()
            throws Exception {
        // Put the primary workspace root on a feature branch, off the
        // manifest base (main). Without -Dfrom the guard must refuse.
        execCapture(primary, "git", "checkout", "-b", "feature/already-here");

        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-789";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("not the base branch")
                .hasMessageContaining("-Dfrom=feature/already-here");

        // Nothing was cloned.
        assertThat(tempDir.resolve("primary꞉jira-789")).doesNotExist();
    }

    @Test
    void siblingCreate_onFeatureBranch_withFrom_proceeds() throws Exception {
        // Primary on a feature branch; -Dfrom opts in to that base. The base
        // branch must exist upstream for the sibling clone (`git clone -b`) to
        // resolve it, so push it on the root and every component first.
        execCapture(primary, "git", "checkout", "-b", "feature/already-here");
        execCapture(primary, "git", "push", "-u", "origin", "feature/already-here");
        for (String name : COMPONENTS) {
            execCapture(primary.resolve(name),
                    "git", "checkout", "-b", "feature/already-here");
            execCapture(primary.resolve(name),
                    "git", "push", "-u", "origin", "feature/already-here");
        }

        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-789";
        mojo.from = "feature/already-here";

        mojo.execute();

        Path sibling = tempDir.resolve("primary꞉jira-789");
        assertThat(sibling.resolve(".git")).isDirectory();
        assertThat(branch(sibling)).isEqualTo("feature/jira-789");
        for (String name : COMPONENTS) {
            assertThat(branch(sibling.resolve(name))).isEqualTo("feature/jira-789");
        }
    }

    @Test
    void siblingCreate_namesSiblingFromWorkspaceRootArtifactId_notDirName()
            throws Exception {
        // The on-disk workspace dir is "primary", but the manifest's
        // workspace-root: artifactId is "renamed-aggregator". WorkingSet
        // .baseName() prefers the workspace-root artifactId over the dir
        // name, so the sibling directory must be derived from the artifactId
        // (<artifactId>-<feature>), NOT from the dir name (<dirname>-<feature>).
        String yaml = Files.readString(
                primary.resolve("workspace.yaml"), StandardCharsets.UTF_8);
        // Prepend a workspace-root: block whose artifactId diverges from the
        // "primary" on-disk directory name. Other top-level keys are untouched.
        String withRoot = """
                workspace-root:
                  groupId: com.test
                  artifactId: renamed-aggregator
                  version: 1-SNAPSHOT

                """ + yaml;
        Files.writeString(primary.resolve("workspace.yaml"), withRoot,
                StandardCharsets.UTF_8);

        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-456";

        mojo.execute();

        // The sibling is named from the baseName (workspace-root artifactId).
        Path baseNameSibling = tempDir.resolve("renamed-aggregator꞉jira-456");
        assertThat(baseNameSibling)
                .as("sibling named from workspace-root artifactId (baseName)")
                .isDirectory();
        assertThat(baseNameSibling.resolve(".git")).isDirectory();
        assertThat(branch(baseNameSibling)).isEqualTo("feature/jira-456");

        // NOT named from the on-disk directory name.
        assertThat(tempDir.resolve("primary꞉jira-456"))
                .as("sibling NOT named from the on-disk dir name")
                .doesNotExist();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String branch(Path repo) throws Exception {
        return execCapture(repo, "git", "rev-parse", "--abbrev-ref", "HEAD");
    }

    /**
     * Read the {@code ws꞉<goalStem>.md} report the goal wrote at the primary
     * workspace root. Matches on the goal stem so the test tolerates the
     * colon-substitution char without hard-coding it.
     */
    private String readReport(String goalStem) throws Exception {
        try (Stream<Path> stream = Files.list(primary)) {
            Path reportFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getFileName().toString().contains(goalStem))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No report file matching '" + goalStem
                                    + "' in " + primary));
            return Files.readString(reportFile, StandardCharsets.UTF_8);
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
                            + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
