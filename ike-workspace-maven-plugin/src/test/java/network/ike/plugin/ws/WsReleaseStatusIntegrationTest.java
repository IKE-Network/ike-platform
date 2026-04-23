package network.ike.plugin.ws;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * End-to-end coverage for {@link WsReleaseStatusMojo}.
 *
 * <p>Builds a workspace of three subprojects with real {@code git
 * init}'d repos, mutates each to a different release-state shape,
 * and asserts that
 * {@link WsReleaseStatusMojo#observe(String, java.io.File)} plus
 * {@link ReleaseStatusInspector#classify} produce the expected
 * verdict. The mojo's {@code execute()} is also driven to confirm
 * it does not throw on any of the four states.
 *
 * <p>Pure-rule coverage lives in {@link ReleaseStatusInspectorTest};
 * this file exists to lock the wiring between the git subprocess
 * layer and the inspector.
 */
class WsReleaseStatusIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
    }

    @Test
    void clean_subproject_with_no_release_artifacts_is_classified_clean()
            throws Exception {
        // lib-a is brand-new — no tags, no release branches.
        File subDir = tempDir.resolve("lib-a").toFile();

        ReleaseStatusInspector.Observation obs =
                WsReleaseStatusMojo.observe("lib-a", subDir);
        ReleaseStatusInspector.Finding f =
                ReleaseStatusInspector.classify(obs);

        assertThat(f.status())
                .isEqualTo(ReleaseStatusInspector.Status.CLEAN);
        assertThat(f.inFlightReleaseBranches()).isEmpty();
    }

    @Test
    void in_flight_when_release_branch_left_behind() throws Exception {
        File subDir = tempDir.resolve("lib-b").toFile();
        // Simulate an interrupted release that created the branch
        // but never deleted it.
        exec(subDir, "git", "checkout", "-b", "release/2.0.0");
        exec(subDir, "git", "checkout", "main");

        ReleaseStatusInspector.Observation obs =
                WsReleaseStatusMojo.observe("lib-b", subDir);
        ReleaseStatusInspector.Finding f =
                ReleaseStatusInspector.classify(obs);

        assertThat(f.status())
                .isEqualTo(ReleaseStatusInspector.Status.IN_FLIGHT);
        assertThat(f.inFlightReleaseBranches())
                .containsExactly("release/2.0.0");
    }

    @Test
    void absent_when_subproject_directory_missing() throws Exception {
        // Workspace.yaml lists app-c but we delete the working tree
        // to simulate a not-checked-out subproject.
        File subDir = tempDir.resolve("app-c").toFile();
        deleteRecursively(subDir);

        ReleaseStatusInspector.Observation obs =
                WsReleaseStatusMojo.observe("app-c", subDir);
        ReleaseStatusInspector.Finding f =
                ReleaseStatusInspector.classify(obs);

        assertThat(f.status())
                .isEqualTo(ReleaseStatusInspector.Status.ABSENT);
    }

    @Test
    void execute_completes_without_throwing_on_mixed_workspace()
            throws Exception {
        // Mix it up: lib-a clean, lib-b has a stray release branch,
        // app-c is removed.
        exec(tempDir.resolve("lib-b").toFile(),
                "git", "checkout", "-b", "release/2.0.0");
        exec(tempDir.resolve("lib-b").toFile(), "git", "checkout", "main");
        deleteRecursively(tempDir.resolve("app-c").toFile());

        WsReleaseStatusMojo mojo =
                TestLog.createMojo(WsReleaseStatusMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static void exec(File workDir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        int exit = p.waitFor();
        Assertions.assertThat(exit)
                .as("git command failed: %s", String.join(" ", command))
                .isZero();
    }

    private static void deleteRecursively(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!f.delete()) {
            throw new RuntimeException("Could not delete " + f);
        }
    }
}
