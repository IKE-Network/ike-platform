package network.ike.plugin.ws;

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
 * Integration tests for {@link WsReconcileBranchesDraftMojo} with {@code scope=branches},
 * exercising real temp workspaces.
 *
 * <p>Each test creates a fresh workspace via {@link TestWorkspaceHelper},
 * modifies branch state in repos or workspace.yaml, then exercises the
 * branch-alignment logic in both directions ({@code from=repos} updates
 * the manifest from on-disk state; {@code from=manifest} switches repos
 * to match the manifest).
 */
class WsReconcileBranchesIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // Make the workspace directory itself a git repo so align can commit
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        // Ignore component subdirectories (they have their own repos)
        Files.writeString(tempDir.resolve(".gitignore"),
                "lib-a/\nlib-b/\napp-c/\n", StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Initial workspace commit");
    }

    // ── from=repos (default: repos → manifest) ───────────────────────

    @Test
    void fromRepos_updatesYaml() throws Exception {
        // Switch lib-a to a feature branch
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/test");

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "repos";
        mojo.publish = true;

        mojo.execute();

        // Verify workspace.yaml now reflects the actual branch
        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        // lib-a's branch field should be updated
        assertThat(yaml).contains("feature/test");
    }

    @Test
    void fromRepos_allMatch_noChanges() throws Exception {
        // All components are on main, which matches workspace.yaml
        String yamlBefore = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "repos";
        mojo.publish = true;

        mojo.execute();

        // Verify workspace.yaml is unchanged
        String yamlAfter = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);
        assertThat(yamlAfter).isEqualTo(yamlBefore);
    }

    @Test
    void fromRepos_dryRun_yamlUnchanged() throws Exception {
        // Switch lib-a to a feature branch
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/test");

        String yamlBefore = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "repos";
        mojo.publish = false;

        mojo.execute();

        // Verify workspace.yaml is NOT updated in draft mode
        String yamlAfter = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);
        assertThat(yamlAfter).isEqualTo(yamlBefore);
    }

    @Test
    void fromRepos_commitsYamlChange() throws Exception {
        // Switch lib-a to a feature branch
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/sync-commit");

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "repos";
        mojo.publish = true;

        mojo.execute();

        // Verify git log shows the align commit
        String log = execCapture(tempDir,
                "git", "log", "--oneline", "-3");
        assertThat(log).contains("workspace: align branch fields from repos");
    }

    @Test
    void fromRepos_dirtySubproject_doesNotRefuse() throws Exception {
        // from=repos (the default) only READS each subproject's branch; in-flight
        // WIP in a subproject must NOT block the manifest sync (#780): the
        // COORDINATING refuse is scoped to the checkout modes only.
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/test");
        Files.writeString(tempDir.resolve("lib-b").resolve("wip.txt"),
                "in flight", StandardCharsets.UTF_8);

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "repos";
        mojo.publish = true;

        mojo.execute(); // must NOT throw despite lib-b's WIP

        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        assertThat(yaml).contains("feature/test");
        assertThat(tempDir.resolve("lib-b").resolve("wip.txt")).exists();
    }

    @Test
    void fromRepos_commitsYamlInIsolation() throws Exception {
        // The workspace.yaml self-commit must commit ONLY workspace.yaml and
        // never sweep a concurrently-staged sibling out of the workspace-root
        // index (#780 IN_ISOLATION, via VcsOperations.commitPaths).
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/iso");
        Files.writeString(tempDir.resolve("sibling.txt"), "unrelated",
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "sibling.txt");

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "repos";
        mojo.publish = true;

        mojo.execute();

        // The reconcile commit touched workspace.yaml...
        String yamlLog = execCapture(tempDir,
                "git", "log", "--oneline", "--", "workspace.yaml");
        assertThat(yamlLog).contains("align branch fields from repos");
        // ...but never committed the concurrently-staged sibling.
        String siblingLog = execCapture(tempDir,
                "git", "log", "--oneline", "--", "sibling.txt");
        assertThat(siblingLog).isBlank();
    }

    @Test
    void fromRepos_draft_reportListsIdentitiesAndPublishCommand()
            throws Exception {
        // Switch lib-a to a feature branch so there is a change to report.
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/test");

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(
                WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "repos";
        mojo.publish = false; // draft

        WorkspaceReportSpec spec = mojo.runGoal();

        // The report body now carries the per-branch identity and a
        // copy-pasteable publish command, not just a bare count (#569).
        assertThat(spec.content())
                .contains("lib-a")
                .contains("feature/test")
                .contains("would be applied")
                .contains("mvn " + WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified());

        // The shared working-set table is rendered with the aggregator
        // (workspace root) as its own row, labeled "aggregator" — the
        // staleness a subproject-only list hid (#763, epic #764). lib-a's
        // planned branch-field update shows in the Effect column.
        assertThat(spec.content())
                .contains("Working set")
                .contains("Member").contains("Kind").contains("Effect")
                .contains("aggregator")
                .contains(tempDir.getFileName().toString())
                .contains("yaml branch");
    }

    @Test
    void fromRepos_draft_workingSetTable_showsAggregatorRowAndRootVersion()
            throws Exception {
        // Give the workspace root (aggregator) its own POM so the #763
        // fix — gathering the root's version the same way as a subproject
        // — has a version string to surface in the table.
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>%s</artifactId>
                    <version>9-aggregator-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """.formatted(tempDir.getFileName().toString()),
                StandardCharsets.UTF_8);

        // A change to report: switch lib-a to a feature branch.
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "feature/ws");

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(
                WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "repos";
        mojo.publish = false; // draft

        WorkspaceReportSpec spec = mojo.runGoal();

        // The aggregator is a first-class working-set row, labeled, so
        // its directory name and POM version are both visible (#763).
        assertThat(spec.content())
                .contains("aggregator")
                .contains(tempDir.getFileName().toString())
                .contains("9-aggregator-SNAPSHOT");
        // Every subproject also appears, alongside the aggregator.
        assertThat(spec.content())
                .contains("lib-a").contains("lib-b").contains("app-c")
                .contains("subproject");
    }

    // ── from=manifest (manifest → repos) ─────────────────────────────

    @Test
    void fromManifest_switchesRepos() throws Exception {
        // Create a "develop" branch in lib-a
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "develop");
        exec(tempDir.resolve("lib-a"), "git", "checkout", "main");

        // Update workspace.yaml to say lib-a should be on "develop"
        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        // Replace the first occurrence of "branch: main" (which is lib-a's)
        // We need to be targeted — update the lib-a block specifically
        yaml = yaml.replaceFirst(
                "(lib-a:\\s+type: software\\s+description: [^\\n]+\\s+repo: [^\\n]+\\s+branch: )main",
                "$1develop");
        Files.writeString(helper.workspaceYaml(), yaml, StandardCharsets.UTF_8);

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "manifest";
        mojo.publish = true;

        mojo.execute();

        // Verify lib-a is now on "develop"
        String branch = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("develop");

        // lib-b and app-c should still be on main
        assertThat(execCapture(tempDir.resolve("lib-b"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("main");
        assertThat(execCapture(tempDir.resolve("app-c"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("main");
    }

    @Test
    void fromManifest_dirtyWorktree_refuses() throws Exception {
        // Create a "develop" branch in lib-a and switch back
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "develop");
        exec(tempDir.resolve("lib-a"), "git", "checkout", "main");

        // Add an uncommitted file to lib-a
        Files.writeString(tempDir.resolve("lib-a").resolve("dirty.txt"),
                "uncommitted", StandardCharsets.UTF_8);

        // Update workspace.yaml to say lib-a should be on "develop"
        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        yaml = yaml.replaceFirst(
                "(lib-a:\\s+type: software\\s+description: [^\\n]+\\s+repo: [^\\n]+\\s+branch: )main",
                "$1develop");
        Files.writeString(helper.workspaceYaml(), yaml, StandardCharsets.UTF_8);

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "manifest";
        mojo.publish = true;

        // COORDINATING (#780): publish refuses on an uncommitted subproject
        // rather than silently skipping it.
        assertThatThrownBy(mojo::execute)
                .hasMessageContaining("uncommitted");

        // lib-a is left untouched — the refuse happens before any checkout.
        String branch = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("main");
    }

    @Test
    void fromManifest_dirtyWorktree_allowUncommitted_skips() throws Exception {
        // Same setup as the refuses test, but -Dallow-uncommitted downgrades the
        // COORDINATING preflight to the per-subproject skip (#780): the dirty
        // lib-a is skipped (left on main), no refusal.
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "develop");
        exec(tempDir.resolve("lib-a"), "git", "checkout", "main");

        Files.writeString(tempDir.resolve("lib-a").resolve("dirty.txt"),
                "uncommitted", StandardCharsets.UTF_8);

        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        yaml = yaml.replaceFirst(
                "(lib-a:\\s+type: software\\s+description: [^\\n]+\\s+repo: [^\\n]+\\s+branch: )main",
                "$1develop");
        Files.writeString(helper.workspaceYaml(), yaml, StandardCharsets.UTF_8);

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "manifest";
        mojo.publish = true;
        mojo.allowUncommitted = true;

        mojo.execute(); // no throw — preflight bypassed, dirty lib-a skipped

        String branch = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("main");
    }

    @Test
    void fromManifest_force_checksOutOverUncommitted() throws Exception {
        // -Dforce bypasses the COORDINATING refuse and checks out the coherent
        // branch over a modified subproject tree — the documented override (#780).
        exec(tempDir.resolve("lib-a"), "git", "checkout", "-b", "develop");
        exec(tempDir.resolve("lib-a"), "git", "checkout", "main");
        Files.writeString(tempDir.resolve("lib-a").resolve("dirty.txt"),
                "uncommitted", StandardCharsets.UTF_8);

        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        yaml = yaml.replaceFirst(
                "(lib-a:\\s+type: software\\s+description: [^\\n]+\\s+repo: [^\\n]+\\s+branch: )main",
                "$1develop");
        Files.writeString(helper.workspaceYaml(), yaml, StandardCharsets.UTF_8);

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "manifest";
        mojo.publish = true;
        mojo.force = true;

        mojo.execute(); // no throw — force bypasses the refuse

        String branch = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD");
        assertThat(branch).isEqualTo("develop");
    }

    @Test
    void fromManifest_allMatch_noChanges() throws Exception {
        // All components are on main, workspace.yaml says main
        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "manifest";
        mojo.publish = true;

        // Should complete without exception — nothing to switch
        assertThatCode(mojo::execute).doesNotThrowAnyException();

        // All still on main
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).isEqualTo("main");
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
