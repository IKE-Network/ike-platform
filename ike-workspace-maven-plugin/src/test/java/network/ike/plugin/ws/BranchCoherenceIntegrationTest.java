package network.ike.plugin.ws;

import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the three-way branch-coherence surfacing
 * (IKE-Network/ike-issues#904): the {@link PreflightCondition#BRANCH_COHERENCE}
 * check across workspace.yaml / checkout / {@code .ike/vcs-state}, the
 * warn-only advisory {@link AbstractWorkspaceMojo} emits before every goal,
 * and the vcs-state axis in {@code ws:check-branch -Dscope=workspace}. The
 * incident this guards against: ike-starter-set's manifest declared a feature
 * branch while checkout and state file sat on {@code main} for a week of
 * commit/push cycles, with the bridge sync actively enforcing the state file
 * OVER the manifest.
 */
class BranchCoherenceIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
    }

    // ── PreflightCondition.BRANCH_COHERENCE ──────────────────────────

    @Test
    void condition_emptyWhenAllAxesAgree() throws Exception {
        assertThat(PreflightCondition.BRANCH_COHERENCE.check(context()))
                .isEmpty();
    }

    @Test
    void condition_emptyWhenStateFileAgrees() throws Exception {
        writeState(tempDir.resolve("lib-a"), "main");

        assertThat(PreflightCondition.BRANCH_COHERENCE.check(context()))
                .isEmpty();
    }

    @Test
    void condition_flagsCheckoutDriftWithCopyPasteableFix() throws Exception {
        // The incident shape: manifest declares a feature branch, checkout
        // (and state file) never left main.
        declareBranch("lib-a", "feature/chronology-builder");
        writeState(tempDir.resolve("lib-a"), "main");

        Optional<String> failure =
                PreflightCondition.BRANCH_COHERENCE.check(context());

        assertThat(failure).isPresent();
        assertThat(failure.get())
                .contains("lib-a")
                .contains("yaml=feature/chronology-builder")
                .contains("checkout=main")
                .contains("vcs-state=main")
                .contains("mvn " + WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified()
                        + " -Dfrom=manifest")
                .contains("mvn " + WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified());
    }

    @Test
    void condition_flagsStateOnlyDrift() throws Exception {
        // Manifest and checkout agree; only the bridge state disagrees —
        // exactly the axis the next goal's sync would enforce.
        writeState(tempDir.resolve("lib-a"), "feature/old");

        Optional<String> failure =
                PreflightCondition.BRANCH_COHERENCE.check(context());

        assertThat(failure).isPresent();
        assertThat(failure.get())
                .contains("lib-a")
                .contains("vcs-state=feature/old");
    }

    @Test
    void condition_ignoresUnclonedAndStatelessMembers() throws Exception {
        // A member without a state file and on its declared branch is
        // coherent; removing a clone entirely removes it from the check.
        deleteRecursively(tempDir.resolve("app-c"));

        assertThat(PreflightCondition.BRANCH_COHERENCE.check(context()))
                .isEmpty();
    }

    // ── The warn-only advisory on every goal ─────────────────────────

    @Test
    void everyGoal_surfacesIncoherenceLoudly() throws Exception {
        declareBranch("lib-a", "feature/chronology-builder");
        writeState(tempDir.resolve("lib-a"), "main");

        WsLintMojo mojo = TestLog.createMojo(WsLintMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        CapturingLog log = new CapturingLog();
        mojo.setLog(log);

        mojo.execute();

        String warnings = String.join("\n", log.warns);
        assertThat(warnings)
                .contains("Branch coherence")
                .contains("lib-a")
                .contains("yaml=feature/chronology-builder")
                .contains("-Dfrom=manifest");
    }

    @Test
    void everyGoal_silentWhenCoherent() throws Exception {
        WsLintMojo mojo = TestLog.createMojo(WsLintMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        CapturingLog log = new CapturingLog();
        mojo.setLog(log);

        mojo.execute();

        assertThat(String.join("\n", log.warns))
                .doesNotContain("Branch coherence");
    }

    // ── ws:check-branch -Dscope=workspace: the vcs-state axis ────────

    @Test
    void checkBranch_workspaceScope_reportsVcsStateAxis() throws Exception {
        // Workspace root repo so check-branch has an authoritative HEAD.
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "commit", "--allow-empty", "-m", "seed");

        writeState(tempDir.resolve("lib-a"), "feature/old");

        CheckBranchMojo mojo = TestLog.createMojo(CheckBranchMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.scope = "workspace";
        CapturingLog log = new CapturingLog();
        mojo.setLog(log);

        WorkspaceReportSpec spec = mojo.runGoal();

        assertThat(spec.content())
                .contains("vcs-state branch")
                .contains("feature/old")
                .contains("vcs-state drift");
        assertThat(String.join("\n", log.warns))
                .contains("lib-a")
                .contains("vcs-state=feature/old");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Captures warn lines while keeping TestLog's other behavior. */
    private static final class CapturingLog extends TestLog {
        final List<String> warns = new ArrayList<>();

        @Override
        public void warn(CharSequence c) {
            warns.add(String.valueOf(c));
        }
    }

    /** The preflight context for the helper-built workspace. */
    private PreflightContext context() throws Exception {
        WorkspaceGraph graph = new WorkspaceGraph(
                ManifestReader.read(helper.workspaceYaml()));
        return PreflightContext.of(tempDir.toFile(), graph,
                List.copyOf(graph.manifest().subprojects().keySet()));
    }

    /** Rewrites the named subproject's declared branch in workspace.yaml. */
    private void declareBranch(String subproject, String branch) throws Exception {
        String yaml = Files.readString(helper.workspaceYaml(), StandardCharsets.UTF_8);
        yaml = yaml.replaceFirst(
                "(" + subproject + ":\\s+type: software\\s+description: [^\\n]+"
                        + "\\s+repo: [^\\n]+\\s+branch: )main",
                "$1" + branch);
        Files.writeString(helper.workspaceYaml(), yaml, StandardCharsets.UTF_8);
    }

    /**
     * Writes a hook-style {@code .ike/vcs-state} recording the given branch
     * at the repo's current HEAD, excluding {@code .ike/} from git the way
     * production machines do via the global gitignore (which the hermetic
     * surefire git config deliberately strips).
     */
    private static void writeState(Path repo, String branch) throws Exception {
        Files.writeString(repo.resolve(".git/info/exclude"), ".ike/\n",
                StandardCharsets.UTF_8);
        VcsState.writeTo(repo, VcsState.create(
                branch, VcsOperations.headSha(repo.toFile()), VcsState.Action.COMMIT));
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
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
            throw new RuntimeException("Command failed (exit " + exitCode
                    + "): " + String.join(" ", command));
        }
    }
}
