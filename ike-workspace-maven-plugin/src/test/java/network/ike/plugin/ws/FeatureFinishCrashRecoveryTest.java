package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.FaultableExec;
import network.ike.plugin.ws.vcs.WorkspaceStateAssert;
import network.ike.plugin.ws.vcs.WorkspaceStateAssert.SubState;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Crash-hardening tests for feature-finish (IKE-Network/ike-issues#667).
 *
 * <p>The #667 incident: a feature-finish that strips the branch qualifier and
 * merges in a single interleaved loop, crashing at subproject N, left
 * subprojects 1..N-1 stripped+merged and N+1.. still carrying the
 * {@code -<feature>-SNAPSHOT} qualifier — a NON-COMPILABLE workspace. The fix
 * front-loads the strip into its own pass, so any later merge-pass crash leaves
 * a workspace where every subproject is plain {@code -SNAPSHOT}.
 *
 * <p>The fixture mirrors {@link FeatureFinishIntegrationTest}: a three-subproject
 * chain ({@code lib-a} → {@code lib-b} → {@code app-c}) on
 * {@code feature/<name>}, each POM branch-qualified, with real feature commits
 * and a hermetic per-repo git identity. Failures are injected with the Phase-1
 * harness {@link FaultableExec}; workspace invariants are read with
 * {@link WorkspaceStateAssert}.
 *
 * <p>Eligible subprojects are processed in reverse-topological order
 * ({@code app-c}, {@code lib-b}, {@code lib-a}), so failing the merge in the
 * MIDDLE subproject {@code lib-b} leaves {@code app-c} merged, {@code lib-b}
 * failed, and {@code lib-a} not yet attempted — exercising the "some merged,
 * some not" partial state the fix must keep compilable.
 */
class FeatureFinishCrashRecoveryTest {

    private static final String FEATURE_NAME = "test-finish";
    private static final String BRANCH_NAME = "feature/" + FEATURE_NAME;
    private static final List<String> SUBPROJECTS = List.of("lib-a", "lib-b", "app-c");

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        for (String name : SUBPROJECTS) {
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

            // Real feature work beyond the version bump, so the squash/merge
            // actually has content to land (not a version-only no-op).
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
        yaml = yaml.replace("branch: main", "branch: " + BRANCH_NAME);
        Files.writeString(wsYaml, yaml, StandardCharsets.UTF_8);
    }

    // ── Test 1: crash leaves a compilable workspace (the core fix) ──

    /**
     * Inject a {@code git merge} failure in the MIDDLE subproject
     * ({@code lib-b}) of a non-draft merge-publish. The goal must throw, but —
     * because the version strip is front-loaded into its own pass — every
     * subproject's POM must already be plain {@code -SNAPSHOT}, so the
     * workspace compiles. The thrown message must name the failed subproject
     * and carry the resume command.
     */
    @Test
    void mergeCrash_leavesCompilableWorkspace_andEmitsResumeGuidance() {
        try (var _ = FaultableExec.install()
                .failWhenCommandContains("merge").inDirNamed("lib-b")) {

            FeatureFinishMergeDraftMojo mojo = newMergeMojo();

            assertThatThrownBy(mojo::execute)
                    .as("the injected lib-b merge failure must surface")
                    .isInstanceOf(MojoException.class)
                    .hasMessageContaining("lib-b")
                    .hasMessageContaining(
                            WsGoal.FEATURE_FINISH_MERGE_PUBLISH.qualified())
                    .hasMessageContaining("-Dfeature=" + FEATURE_NAME);
        }

        // The compilable invariant: NO subproject carries the feature
        // qualifier — the front-loaded prep pass stripped all three before
        // any merge ran, so the crash left a tree that still builds.
        assertAllStrippedNoQualifier();
    }

    /**
     * Red-first guard for {@link #mergeCrash_leavesCompilableWorkspace_andEmitsResumeGuidance}.
     *
     * <p>This reproduces the PRE-FIX interleaved behaviour by hand: strip and
     * merge each subproject in a single loop, in the same reverse-topological
     * order the mojo uses, failing the merge at {@code lib-b}. It asserts that
     * the not-yet-reached subproject ({@code lib-a}) is STILL branch-qualified
     * — i.e. the workspace would NOT compile. This documents exactly the bug
     * the two-pass fix removes; the test above proves the fix holds.
     */
    @Test
    void redFirst_interleavedStripAndMerge_leavesQualifierOnUnreachedSubproject()
            throws Exception {
        // eligible order = reverse topological = app-c, lib-b, lib-a
        List<String> eligible = List.of("app-c", "lib-b", "lib-a");
        boolean threw = false;
        for (String name : eligible) {
            Path dir = tempDir.resolve(name);
            // strip-then-merge, interleaved (the pre-#667 shape)
            stripQualifierInline(dir);
            if ("lib-b".equals(name)) {
                // simulate the merge crash at the middle subproject
                threw = true;
                break;
            }
            exec(dir, "git", "checkout", "main");
            exec(dir, "git", "merge", "--no-ff", BRANCH_NAME, "-m", "merge");
        }

        assertThat(threw).as("the simulated crash must have fired at lib-b").isTrue();

        Map<String, SubState> states =
                WorkspaceStateAssert.snapshot(tempDir, SUBPROJECTS);
        // app-c stripped+merged, lib-b stripped (crash before merge), but
        // lib-a was never reached → STILL branch-qualified. This is the
        // non-compilable state #667 produced and the two-pass fix prevents.
        assertThat(states.get("lib-a").pomVersion())
                .as("pre-fix interleave leaves lib-a branch-qualified "
                        + "(non-compilable) — the fixed two-pass path must not")
                .contains("-" + FEATURE_NAME + "-SNAPSHOT");
    }

    // ── Test 2: resume completes after the crash ──

    /**
     * After the test-1 crash state (achieved here in-line), close the
     * interceptor and re-run the goal: already-merged subprojects are skipped
     * via {@code ALREADY_DONE}, the remaining ones merge, and no feature
     * qualifier remains anywhere.
     */
    @Test
    void mergeResume_afterCrash_completesAndSkipsAlreadyMerged() {
        // First run: crash at lib-b.
        try (var _ = FaultableExec.install()
                .failWhenCommandContains("merge").inDirNamed("lib-b")) {
            FeatureFinishMergeDraftMojo mojo = newMergeMojo();
            assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class);
        }

        // Sanity: the crash left a compilable (fully-stripped) workspace.
        assertAllStrippedNoQualifier();

        // Resume run: interceptor reset (try-with-resources closed). lib-a /
        // lib-b finish; app-c is detected ALREADY_DONE and skipped.
        FeatureFinishMergeDraftMojo resume = newMergeMojo();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(resume::execute);

        // Every subproject is now on main and free of the feature qualifier.
        Map<String, SubState> states =
                WorkspaceStateAssert.snapshot(tempDir, SUBPROJECTS);
        for (String name : SUBPROJECTS) {
            assertThat(states.get(name).branch())
                    .as(name + " must end on main after resume")
                    .isEqualTo("main");
            assertThat(states.get(name).pomVersion())
                    .as(name + " must carry no feature qualifier after resume")
                    .doesNotContain("-" + FEATURE_NAME + "-")
                    .endsWith("-SNAPSHOT");
        }
    }

    // ── Test 3: squash counterpart of the core fix ──

    /**
     * The squash variant of test 1: a {@code git merge} failure in
     * {@code lib-b} during {@code feature-finish-squash-publish}
     * ({@code git merge --squash} also runs {@code git merge}). The same
     * compilable invariant must hold — every POM plain {@code -SNAPSHOT} — and
     * the resume command must reference the squash-publish goal.
     */
    @Test
    void squashCrash_leavesCompilableWorkspace_andEmitsResumeGuidance() {
        try (var _ = FaultableExec.install()
                .failWhenCommandContains("merge").inDirNamed("lib-b")) {

            FeatureFinishSquashDraftMojo mojo = newSquashMojo();

            assertThatThrownBy(mojo::execute)
                    .as("the injected lib-b squash-merge failure must surface")
                    .isInstanceOf(MojoException.class)
                    .hasMessageContaining("lib-b")
                    .hasMessageContaining(
                            WsGoal.FEATURE_FINISH_SQUASH_PUBLISH.qualified())
                    .hasMessageContaining("-Dfeature=" + FEATURE_NAME);
        }

        assertAllStrippedNoQualifier();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private FeatureFinishMergeDraftMojo newMergeMojo() {
        FeatureFinishMergeDraftMojo mojo =
                TestLog.createMojo(FeatureFinishMergeDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.publish = true;
        mojo.push = false;
        mojo.keepBranch = true;   // keep branches so resume can re-detect them
        return mojo;
    }

    private FeatureFinishSquashDraftMojo newSquashMojo() {
        FeatureFinishSquashDraftMojo mojo =
                TestLog.createMojo(FeatureFinishSquashDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.feature = FEATURE_NAME;
        mojo.targetBranch = "main";
        mojo.message = "Squash feature";
        mojo.publish = true;
        mojo.push = false;
        return mojo;
    }

    /**
     * Assert that every subproject's own POM version is a plain
     * {@code -SNAPSHOT} with no {@code -<feature>} qualifier — the
     * compile-safe invariant the front-loaded strip guarantees regardless of
     * where the merge pass crashed.
     */
    private void assertAllStrippedNoQualifier() {
        Map<String, SubState> states =
                WorkspaceStateAssert.snapshot(tempDir, SUBPROJECTS);
        for (String name : SUBPROJECTS) {
            assertThat(states.get(name).pomVersion())
                    .as(name + " POM must be plain -SNAPSHOT after a crash "
                            + "(compilable invariant, #667): " + states.get(name))
                    .isNotNull()
                    .doesNotContain("-" + FEATURE_NAME + "-")
                    .endsWith("-SNAPSHOT");
        }
    }

    /**
     * Strip the branch qualifier from a single subproject's POM and commit it
     * on the current (feature) branch — the manual equivalent of one prep-pass
     * step, used only by the red-first reproduction of the pre-fix interleave.
     */
    private void stripQualifierInline(Path dir) throws Exception {
        Path pom = dir.resolve("pom.xml");
        String content = Files.readString(pom, StandardCharsets.UTF_8);
        String stripped = content.replace("-" + FEATURE_NAME + "-SNAPSHOT",
                "-SNAPSHOT");
        Files.writeString(pom, stripped, StandardCharsets.UTF_8);
        exec(dir, "git", "add", "-A");
        exec(dir, "git", "commit", "-m",
                "merge-prep: strip branch qualifier");
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
}
