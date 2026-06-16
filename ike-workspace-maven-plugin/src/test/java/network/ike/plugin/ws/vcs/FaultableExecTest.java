package network.ike.plugin.ws.vcs;

import network.ike.plugin.ws.TestLog;
import network.ike.plugin.ws.vcs.WorkspaceStateAssert.SubState;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the Phase-1 ws-plugin test harness (ike-issues#691): the
 * {@link FaultableExec} command-failure injector and the
 * {@link WorkspaceStateAssert} state-snapshot utility.
 *
 * <p>Setup mirrors {@link VcsOperationsTest}: a single real git repo in a
 * {@link TempDir}, a {@link TestLog}, a local {@code exec} helper, and a
 * hermetic per-repo git identity (the module's surefire config additionally
 * exports {@code GIT_CONFIG_GLOBAL=hermetic.gitconfig}).
 */
class FaultableExecTest {

    @TempDir
    Path tempDir;

    private File repoDir;
    private final Log log = new TestLog();

    @BeforeEach
    void setUp() throws Exception {
        repoDir = tempDir.toFile();
        exec("git", "init", "-b", "main");
        // Hermetic sandbox identity, matching VcsOperationsTest
        // (IKE-Network/ike-issues#560): never depend on the host config.
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec("git", "config", "core.hooksPath", noHooks.toAbsolutePath().toString());
        exec("git", "config", "commit.gpgsign", "false");
        exec("git", "config", "tag.gpgsign", "false");
        exec("git", "config", "user.email", "test@example.com");
        exec("git", "config", "user.name", "Test");
        Files.writeString(tempDir.resolve("file.txt"), "hello", StandardCharsets.UTF_8);
        exec("git", "add", ".");
        exec("git", "commit", "-m", "Initial commit");
    }

    /**
     * A scripted failure is injected into a real {@link VcsOperations} commit:
     * the interceptor fires before {@code git commit} runs, so the op throws a
     * {@link MojoException} carrying the harness marker, and the count proves the
     * subprocess was intercepted (not actually executed).
     */
    @Test
    void injectsFailureIntoRealVcsOperation() throws Exception {
        Files.writeString(tempDir.resolve("change.txt"), "work", StandardCharsets.UTF_8);
        VcsOperations.addAll(repoDir, log);

        try (FaultableExec fault = FaultableExec.install()
                .failWhenCommandContains("commit")) {
            assertThatThrownBy(() -> VcsOperations.commit(repoDir, log, "msg"))
                    .isInstanceOf(MojoException.class)
                    .hasMessageContaining("FaultableExec injected");
            assertThat(fault.interceptedCount()).isGreaterThan(0);
            assertThat(fault.matchCount()).isEqualTo(1);
        }

        // The injected failure short-circuited the real git commit, so the
        // staged change is still uncommitted.
        String staged = execCapture("git", "diff", "--cached", "--name-only");
        assertThat(staged).contains("change.txt");
    }

    /**
     * After the try-with-resources block, {@link FaultableExec#close()} has reset
     * the interceptor to the default no-op, so a subsequent real commit succeeds
     * — no injected state leaks across tests.
     */
    @Test
    void resetsOnCloseSoNoStateLeaks() throws Exception {
        // Trip the interceptor once, then let the block close it.
        Files.writeString(tempDir.resolve("first.txt"), "one", StandardCharsets.UTF_8);
        VcsOperations.addAll(repoDir, log);
        try (FaultableExec fault = FaultableExec.install()
                .failWhenCommandContains("commit")) {
            assertThatThrownBy(() -> VcsOperations.commit(repoDir, log, "should fail"))
                    .isInstanceOf(MojoException.class)
                    .hasMessageContaining("FaultableExec injected");
            assertThat(fault.interceptedCount()).isGreaterThan(0);
        }

        // Interceptor is back to no-op: a fresh staged commit must succeed.
        Files.writeString(tempDir.resolve("second.txt"), "two", StandardCharsets.UTF_8);
        VcsOperations.addAll(repoDir, log);
        assertThatCode(() -> VcsOperations.commit(repoDir, log, "should succeed"))
                .doesNotThrowAnyException();

        String logOut = execCapture("git", "log", "--oneline");
        assertThat(logOut).contains("should succeed");
        assertThat(logOut).doesNotContain("should fail");
    }

    /**
     * With {@code .onOccurrence(2)} only the second qualifying command fails:
     * the first commit goes through the real subprocess and lands, the second is
     * intercepted and throws.
     */
    @Test
    void onOccurrenceFailsOnlyTheNthMatch() throws Exception {
        try (FaultableExec fault = FaultableExec.install()
                .failWhenCommandContains("commit").onOccurrence(2)) {

            // 1st commit: matches the rule (occurrence 1) but is not the target,
            // so it runs for real and succeeds.
            Files.writeString(tempDir.resolve("a.txt"), "a", StandardCharsets.UTF_8);
            VcsOperations.addAll(repoDir, log);
            assertThatCode(() -> VcsOperations.commit(repoDir, log, "first commit"))
                    .doesNotThrowAnyException();

            // 2nd commit: occurrence 2 — the target — so it is failed.
            Files.writeString(tempDir.resolve("b.txt"), "b", StandardCharsets.UTF_8);
            VcsOperations.addAll(repoDir, log);
            assertThatThrownBy(() -> VcsOperations.commit(repoDir, log, "second commit"))
                    .isInstanceOf(MojoException.class)
                    .hasMessageContaining("FaultableExec injected");

            assertThat(fault.matchCount()).isEqualTo(2);
        }

        String logOut = execCapture("git", "log", "--oneline");
        assertThat(logOut).contains("first commit");
        assertThat(logOut).doesNotContain("second commit");
    }

    /**
     * {@link WorkspaceStateAssert#snapshot} captures a subproject's branch, HEAD
     * sha, own POM version, and tags-at-HEAD; a tag created after the first
     * snapshot shows up in a fresh one.
     */
    @Test
    void workspaceStateAssertSnapshotsGitAndPomState() throws Exception {
        // Build a tiny subproject repo under a workspace root.
        Path wsRoot = Files.createDirectories(tempDir.resolve("ws"));
        Path subDir = Files.createDirectories(wsRoot.resolve("lib-a"));
        gitIn(subDir, "init", "-b", "release/1");
        gitIn(subDir, "config", "core.hooksPath",
                Files.createDirectories(subDir.resolve(".nohooks")).toAbsolutePath().toString());
        gitIn(subDir, "config", "commit.gpgsign", "false");
        gitIn(subDir, "config", "tag.gpgsign", "false");
        gitIn(subDir, "config", "user.email", "test@example.com");
        gitIn(subDir, "config", "user.name", "Test");
        Files.writeString(subDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>1-SNAPSHOT</version>
                  </parent>
                  <artifactId>lib-a</artifactId>
                  <version>2.5.0</version>
                </project>
                """, StandardCharsets.UTF_8);
        gitIn(subDir, "add", ".");
        gitIn(subDir, "commit", "-m", "init lib-a");

        List<String> subs = List.of("lib-a");
        Map<String, SubState> before =
                WorkspaceStateAssert.snapshot(wsRoot.toFile(), subs);
        SubState a = before.get("lib-a");

        assertThat(a.branch()).isEqualTo("release/1");
        assertThat(a.headSha()).isNotBlank();
        // Own <version> after </parent>, not the parent's 1-SNAPSHOT.
        WorkspaceStateAssert.assertVersion(a, "2.5.0");
        assertThat(a.tagsAtHead()).isEmpty();
        WorkspaceStateAssert.assertNoTagAtHead(a, "v2.5.0");

        // Tag HEAD, then re-snapshot: the tag now points at HEAD.
        gitIn(subDir, "tag", "v2.5.0");
        Map<String, SubState> after =
                WorkspaceStateAssert.snapshot(wsRoot.toFile(), subs);
        SubState a2 = after.get("lib-a");

        assertThat(a2.tagsAtHead()).contains("v2.5.0");
        assertThat(a2.headSha()).isEqualTo(a.headSha());
        WorkspaceStateAssert.assertVersion(a2, "2.5.0");
    }

    // ── Helpers (mirror VcsOperationsTest) ───────────────────────

    private void exec(String... command) throws Exception {
        runIn(repoDir, command);
    }

    private void gitIn(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        runIn(dir.toFile(), command);
    }

    private void runIn(File dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(dir)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command)
                            + (output.isBlank() ? "" : "\n" + output));
        }
    }

    private String execCapture(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(repoDir)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        process.waitFor();
        return output;
    }
}
