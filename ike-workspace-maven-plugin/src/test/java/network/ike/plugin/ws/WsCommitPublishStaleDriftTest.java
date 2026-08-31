package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiring contract of the stale-drift gate in
 * {@code ws:commit-publish} (IKE-Network/ike-issues#1082):
 *
 * <ul>
 *   <li>a staged delta whose every path byte-matches an older
 *       committed state is refused, naming the paths and the escape
 *       hatch, and no commit is created;</li>
 *   <li>{@code -Dallow-stale-drift=true} commits the same delta;</li>
 *   <li>a mixed stale-plus-novel delta commits (with warnings);</li>
 *   <li>ordinary novel work is untouched by the gate.</li>
 * </ul>
 */
class WsCommitPublishStaleDriftTest {

    @TempDir
    Path tempDir;

    private Path repo;

    private final String originalUserDir = System.getProperty("user.dir");

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(tempDir.resolve("solo"));
        exec("git", "init", "-b", "main");
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec("git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec("git", "config", "commit.gpgsign", "false");
        exec("git", "config", "user.email", "test@ike.test");
        exec("git", "config", "user.name", "Test");
        commitFile("a.txt", "a version 1", "c1: add a");
        commitFile("a.txt", "a version 2", "c2: evolve a");
        System.setProperty("user.dir", repo.toAbsolutePath().toString());
    }

    @Test
    void whollyStaleStagedDelta_isRefused_andNothingIsCommitted()
            throws Exception {
        Files.writeString(repo.resolve("a.txt"), "a version 1",
                StandardCharsets.UTF_8);
        exec("git", "add", "a.txt");

        WsCommitPublishMojo mojo = TestLog.createMojo(WsCommitPublishMojo.class);
        mojo.message = "would re-commit history backwards";
        mojo.stagedOnly = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("stale drift, not WIP")
                .hasMessageContaining("a.txt")
                .hasMessageContaining("c1: add a")
                .hasMessageContaining("-Dallow-stale-drift=true");

        assertThat(execCapture("git", "log", "-1", "--format=%s"))
                .as("the refusal must leave HEAD untouched")
                .isEqualTo("c2: evolve a");
    }

    @Test
    void allowStaleDrift_commitsTheDeliberateRevert() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "a version 1",
                StandardCharsets.UTF_8);
        exec("git", "add", "a.txt");

        WsCommitPublishMojo mojo = TestLog.createMojo(WsCommitPublishMojo.class);
        mojo.message = "deliberate revert to version 1";
        mojo.stagedOnly = true;
        mojo.allowStaleDrift = true;

        mojo.execute();

        assertThat(execCapture("git", "log", "-1", "--format=%s"))
                .isEqualTo("deliberate revert to version 1");
    }

    @Test
    void mixedStaleAndNovelDelta_commits() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "a version 1",
                StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("fresh.txt"), "novel work",
                StandardCharsets.UTF_8);
        exec("git", "add", "a.txt", "fresh.txt");

        WsCommitPublishMojo mojo = TestLog.createMojo(WsCommitPublishMojo.class);
        mojo.message = "revert plus novel work";
        mojo.stagedOnly = true;

        mojo.execute();

        assertThat(execCapture("git", "log", "-1", "--format=%s"))
                .isEqualTo("revert plus novel work");
        assertThat(execCapture("git", "show", "--stat", "--format=", "HEAD"))
                .contains("a.txt")
                .contains("fresh.txt");
    }

    @Test
    void novelWork_defaultSweepMode_isUntouchedByTheGate() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "a version 3 — brand new",
                StandardCharsets.UTF_8);

        WsCommitPublishMojo mojo = TestLog.createMojo(WsCommitPublishMojo.class);
        mojo.message = "ordinary novel commit";

        mojo.execute();

        assertThat(execCapture("git", "log", "-1", "--format=%s"))
                .isEqualTo("ordinary novel commit");
    }

    // ── helpers ──────────────────────────────────────────────────

    private void commitFile(String name, String content, String message)
            throws Exception {
        Files.writeString(repo.resolve(name), content,
                StandardCharsets.UTF_8);
        exec("git", "add", name);
        exec("git", "commit", "-m", message);
    }

    private void exec(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
    }

    private String execCapture(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
        return out;
    }
}
