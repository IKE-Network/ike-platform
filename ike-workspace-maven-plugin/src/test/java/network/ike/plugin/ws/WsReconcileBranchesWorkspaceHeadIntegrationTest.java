package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;

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
 * Integration tests for {@link WsReconcileBranchesDraftMojo} with
 * {@code from=workspace-head}.
 *
 * <p>The workspace repo's HEAD branch is treated as authoritative;
 * both the {@code branch:} fields in {@code workspace.yaml} and each
 * subproject's on-disk branch are reconciled to that single value
 * (ike-issues#287). This is the symmetric repair to {@code ws:add}'s
 * branch-coherence rule (ike-issues#286).
 */
class WsReconcileBranchesWorkspaceHeadIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // Workspace dir is itself a git repo so align-publish can commit
        // the workspace.yaml change.
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        Files.writeString(tempDir.resolve(".gitignore"),
                "lib-a/\nlib-b/\napp-c/\n", StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "Initial workspace commit");
    }

    @Test
    void workspaceHead_alignsBothYamlAndRepos() throws Exception {
        // Workspace flips onto a feature branch; subprojects haven't
        // followed yet — exactly the drift this mode is designed to fix.
        exec(tempDir, "git", "checkout", "-b", "feature/X");

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "workspace-head";
        mojo.publish = true;

        mojo.execute();

        // YAML branch: fields should now read feature/X for every component.
        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        assertThat(yaml).contains("branch: feature/X");

        // Every subproject's HEAD should now be on feature/X — the
        // create-from-current-HEAD path handles the case where the
        // feature branch doesn't exist locally or on origin yet.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            String branch = execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            assertThat(branch).as("subproject %s should be on feature/X", name)
                    .isEqualTo("feature/X");
        }
    }

    @Test
    void workspaceHead_dryRun_writesNothing() throws Exception {
        exec(tempDir, "git", "checkout", "-b", "feature/Y");

        String yamlBefore = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "workspace-head";
        mojo.publish = false;

        mojo.execute();

        // Draft mode must leave both YAML and repo state untouched.
        assertThat(Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8))
                .isEqualTo(yamlBefore);
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            assertThat(execCapture(tempDir.resolve(name),
                    "git", "rev-parse", "--abbrev-ref", "HEAD"))
                    .isEqualTo("main");
        }
    }

    @Test
    void workspaceHead_allAgree_noChanges() throws Exception {
        // Workspace stays on main; YAML says main; repos are on main.
        // Nothing to do.
        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "workspace-head";
        mojo.publish = true;

        String yamlBefore = Files.readString(
                helper.workspaceYaml(), StandardCharsets.UTF_8);
        mojo.execute();
        assertThat(Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8))
                .isEqualTo(yamlBefore);
    }

    @Test
    void workspaceHead_workspaceWithoutGit_throws() throws Exception {
        // Strip the workspace's .git so from=workspace-head has no anchor.
        deleteRecursive(tempDir.resolve(".git"));

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "workspace-head";
        mojo.publish = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("from=workspace-head requires the workspace");
    }

    @Test
    void workspaceHead_invalidFrom_throws() throws Exception {
        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "garbage-value";
        mojo.publish = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Invalid from")
                .hasMessageContaining("repos|manifest|workspace-head");
    }

    @Test
    void workspaceHead_reportIncludesAggregatorRowWithHeadEffect()
            throws Exception {
        // Workspace flips onto a feature branch — the from=workspace-head
        // aggregator-effect path (child C #767) must surface the workspace
        // root as the authority row in the working-set table, not omit it
        // the way a subproject-only list did (#768 review gap).
        exec(tempDir, "git", "checkout", "-b", "feature/Z");

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "workspace-head";
        mojo.publish = true;

        mojo.execute();

        String report = readReport("reconcile-branches-publish");

        // The aggregator is the workspace-root directory; its row carries
        // the "aggregator" kind label and its name is that dir's name.
        String aggregatorName = tempDir.getFileName().toString();
        assertThat(report)
                .as("the working-set table must carry the aggregator row")
                .contains("aggregator")
                .contains(aggregatorName);

        // The aggregator's Effect cell states it is the from=workspace-head
        // branch authority — "authority — HEAD `feature/Z`".
        assertThat(report)
                .as("the aggregator's from=workspace-head effect must read "
                        + "as the HEAD branch authority")
                .contains("authority")
                .contains("HEAD")
                .contains("feature/Z");
    }

    @Test
    void workspaceHead_recordsMemberVcsStateOnCheckout() throws Exception {
        // A bridge-managed member: recording the checkout as a VCS action is
        // what keeps the bridge from undoing this reconcile (#903/#904).
        Files.writeString(tempDir.resolve("lib-a/.git/info/exclude"),
                ".ike/\n", StandardCharsets.UTF_8);
        VcsState.writeTo(tempDir.resolve("lib-a"), VcsState.create("main",
                VcsOperations.headSha(tempDir.resolve("lib-a").toFile()),
                VcsState.Action.COMMIT));

        exec(tempDir, "git", "checkout", "-b", "feature/X");

        WsReconcileBranchesDraftMojo mojo = TestLog.createMojo(WsReconcileBranchesDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "branches";
        mojo.from = "workspace-head";
        mojo.publish = true;

        mojo.execute();

        java.util.Optional<VcsState> state =
                VcsState.readFrom(tempDir.resolve("lib-a"));
        assertThat(state).isPresent();
        assertThat(state.get().branch()).isEqualTo("feature/X");
        assertThat(state.get().action()).isEqualTo(VcsState.Action.SWITCH);

        VcsOperations.catchUp(tempDir.resolve("lib-a").toFile(), new TestLog());
        assertThat(execCapture(tempDir.resolve("lib-a"),
                "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("feature/X");
    }

    // ── Helpers ──────────────────────────────────────────────────────

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

    private void exec(Path workDir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Command failed (" + exit + "): "
                    + String.join(" ", command));
        }
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Command failed (" + exit + "): "
                    + String.join(" ", command) + "\n" + out);
        }
        return out;
    }

    private static void deleteRecursive(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a))  // children first
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    });
        }
    }
}
