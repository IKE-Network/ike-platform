package network.ike.plugin.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * End-to-end integration test for #192 cascade behavior.
 *
 * <p>Uses {@link TestWorkspaceHelper}'s 3-subproject linear chain
 * ({@code lib-a → lib-b → app-c}) to verify the release-set cascade
 * end-to-end through {@link WsReleaseDraftMojo#execute} in draft mode
 * (so no {@code mvn ike:release-publish} subprocess is invoked).
 *
 * <p>The mojo logs its release plan via {@code getLog().info(...)};
 * we capture stdout to verify cascaded subprojects appear in the plan
 * with the correct "downstream of X" reason.
 */
class WsReleaseCascadeIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;
    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // Capture stdout to verify the release plan logs.
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    /**
     * Mid-graph (here: leaf) source change should pull all downstream
     * subprojects into the release plan, not just the source-changed
     * one. This is the headline acceptance criterion of #192.
     *
     * <p>Workspace: {@code lib-a → lib-b → app-c}. Tag all three at
     * v1.0.0 (so they're "clean"), then add a commit only to lib-a.
     * Source-changed = {lib-a}. Expected release plan = {lib-a, lib-b,
     * app-c}, with lib-b and app-c labeled "downstream of lib-a".
     */
    @Test
    void cascade_leafChange_pullsAllDownstreamIntoPlan() throws Exception {
        // Tag every subproject so they start clean
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "tag", "v1.0.0");
        }

        // Source change in lib-a only
        addCommit(tempDir.resolve("lib-a"), "new feature in lib-a");

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = false; // draft — does not invoke mvn ike:release

        assertThatCode(mojo::execute).doesNotThrowAnyException();

        String log = capturedOut.toString(StandardCharsets.UTF_8);

        // All three must appear in the plan, in topological order.
        assertThat(log)
                .contains("Components to release (3)")
                .contains("1. lib-a")
                .contains("2. lib-b")
                .contains("3. app-c");

        // Reason vocabulary is correct: lib-a has source commits;
        // lib-b and app-c are downstream of lib-a.
        assertThat(log)
                .contains("lib-a")
                .contains("commits since v1.0.0")
                .contains("downstream of lib-a");
    }

    /**
     * Catch-up does not expand the release set: when only a leaf
     * subproject (no downstream) has source changes, no cascade
     * happens and only that leaf is in the plan.
     *
     * <p>app-c is the leaf of the chain ({@code lib-a → lib-b → app-c}
     * means app-c depends on lib-b, so app-c has no downstream).
     * Tagging all three and committing only in app-c should produce
     * a 1-element plan.
     */
    @Test
    void cascade_terminalChange_noCascade() throws Exception {
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "tag", "v1.0.0");
        }
        addCommit(tempDir.resolve("app-c"), "new feature in app-c");

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = false;

        assertThatCode(mojo::execute).doesNotThrowAnyException();

        String log = capturedOut.toString(StandardCharsets.UTF_8);

        assertThat(log).contains("Components to release (1)");
        assertThat(log).contains("app-c");
        // Critically: lib-a and lib-b should NOT be pulled in by
        // catch-up — they have stale property to app-c maybe, but
        // they're upstream, not downstream of the change.
        assertThat(log).doesNotContain("downstream of");
    }

    /**
     * Mid-chain source change cascades only to true downstream, not
     * to upstream. lib-b changes → release plan {lib-b, app-c}.
     * lib-a stays out (it's upstream of lib-b, not downstream).
     */
    @Test
    void cascade_midChainChange_onlyDownstreamPulled() throws Exception {
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "tag", "v1.0.0");
        }
        addCommit(tempDir.resolve("lib-b"), "new feature in lib-b");

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = false;

        assertThatCode(mojo::execute).doesNotThrowAnyException();

        String log = capturedOut.toString(StandardCharsets.UTF_8);

        assertThat(log).contains("Components to release (2)");
        assertThat(log).contains("lib-b");
        assertThat(log).contains("app-c");
        assertThat(log).contains("downstream of lib-b");
        // lib-a is upstream of the change — must not appear in the
        // numbered plan list.
        assertThat(log).doesNotContain("1. lib-a");
    }

    // ── Helpers (mirror TestWorkspaceHelper) ────────────────────────

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

    private void addCommit(Path dir, String message) throws Exception {
        Path file = dir.resolve("file-" + System.nanoTime() + ".txt");
        Files.writeString(file, message, StandardCharsets.UTF_8);
        exec(dir, "git", "add", file.getFileName().toString());
        exec(dir, "git", "commit", "-m", message);
    }
}
