package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for branch resolution in {@link WsAddMojo}.
 *
 * <p>Verifies the workspace-branch-coherence rule from ike-issues#286:
 * the workspace repo's git HEAD is authoritative for the branch a new
 * subproject lands on, and an explicit {@code -Dbranch=} that disagrees
 * with that HEAD is rejected.
 */
class WsAddBranchResolutionTest {

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
    }

    @Test
    void resolves_to_workspace_head_when_repo_is_on_a_feature_branch() throws Exception {
        gitInit(tempDir);
        gitCheckoutBranch(tempDir, "feature/reasoner");

        WsAddMojo mojo = TestLog.createMojo(WsAddMojo.class);
        String resolved = invokeResolveBranch(mojo, tempDir);

        assertThat(resolved).isEqualTo("feature/reasoner");
    }

    @Test
    void resolves_to_workspace_head_even_when_dbranch_matches() throws Exception {
        gitInit(tempDir);
        gitCheckoutBranch(tempDir, "feature/reasoner");

        WsAddMojo mojo = TestLog.createMojo(WsAddMojo.class);
        setBranch(mojo, "feature/reasoner");
        String resolved = invokeResolveBranch(mojo, tempDir);

        assertThat(resolved).isEqualTo("feature/reasoner");
    }

    @Test
    void rejects_dbranch_that_disagrees_with_workspace_head() throws Exception {
        gitInit(tempDir);
        gitCheckoutBranch(tempDir, "feature/reasoner");

        WsAddMojo mojo = TestLog.createMojo(WsAddMojo.class);
        setBranch(mojo, "main");

        assertThatThrownBy(() -> invokeResolveBranch(mojo, tempDir))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("disagrees")
                .hasMessageContaining("feature/reasoner")
                .hasMessageContaining("main");
    }

    @Test
    void falls_back_to_defaults_branch_when_workspace_has_no_git() throws Exception {
        WsAddMojo mojo = TestLog.createMojo(WsAddMojo.class);
        String resolved = invokeResolveBranch(mojo, tempDir);

        assertThat(resolved).isEqualTo("main");
    }

    @Test
    void uses_dbranch_when_workspace_has_no_git() throws Exception {
        WsAddMojo mojo = TestLog.createMojo(WsAddMojo.class);
        setBranch(mojo, "feature/foo");
        String resolved = invokeResolveBranch(mojo, tempDir);

        assertThat(resolved).isEqualTo("feature/foo");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static void gitInit(Path dir) throws Exception {
        run(dir, "git", "init", "-q");
    }

    /**
     * Point HEAD at the named branch via symbolic-ref. Avoids the need
     * for a commit — {@code git branch --show-current} reports the
     * symbolic-ref target regardless of whether the branch has any
     * commits yet.
     */
    private static void gitCheckoutBranch(Path dir, String branch) throws Exception {
        run(dir, "git", "symbolic-ref", "HEAD", "refs/heads/" + branch);
    }

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        int exit = p.waitFor();
        if (exit != 0) {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("Command failed (" + exit + "): "
                    + String.join(" ", cmd) + "\n" + out);
        }
    }

    private static void setBranch(WsAddMojo mojo, String branch) throws Exception {
        var field = WsAddMojo.class.getDeclaredField("branch");
        field.setAccessible(true);
        field.set(mojo, branch);
    }

    private String invokeResolveBranch(WsAddMojo mojo, Path wsDir) throws Exception {
        Method m = WsAddMojo.class.getDeclaredMethod(
                "resolveBranch", Path.class, Path.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(mojo, wsDir, wsDir.resolve("workspace.yaml"));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }
}
