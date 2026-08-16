package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rollback plan/apply semantics of {@link WorkspaceReleaseRollback}
 * (IKE-Network/ike-issues#1010) against real git repositories: unpushed
 * release-cadence commits and their local tags are discarded back to
 * the last pushed or non-cadence commit; pushed history, later work,
 * and dirty trees refuse.
 */
class WorkspaceReleaseRollbackTest {

    @TempDir
    Path tempDir;

    private File repo;

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(tempDir.resolve("member")).toFile();
        git("init", "-b", "main");
        git("config", "user.email", "t@t");
        git("config", "user.name", "t");
        git("config", "core.hooksPath",
                Files.createDirectories(tempDir.resolve("nohooks")).toString());
        git("config", "commit.gpgsign", "false");
        git("config", "tag.gpgsign", "false");
        commit("work: base");
        Path bare = tempDir.resolve("origin.git");
        git("init", "--bare", bare.toString());
        git("remote", "add", "origin", bare.toString());
        git("push", "-u", "origin", "main");
    }

    @Test
    void discards_unpushed_cadence_commits_and_their_tags()
            throws Exception {
        String base = head();
        commit("release: set version to 2.0.0");
        git("tag", "-a", "2.0.0", "-m", "release");
        commit("post-release: bump to 2.0.1-SNAPSHOT");

        WorkspaceReleaseRollback.RepoPlan plan =
                WorkspaceReleaseRollback.plan("member", repo);
        assertThat(plan.refusal()).isNull();
        assertThat(plan.discards()).hasSize(2);
        assertThat(plan.tags()).containsExactly("2.0.0");
        assertThat(plan.targetSha()).startsWith(base);

        WorkspaceReleaseRollback.apply(plan,
                new org.apache.maven.api.plugin.Log() {
                    public void debug(CharSequence c) {}
                    public void debug(CharSequence c, Throwable t) {}
                    public void debug(Throwable t) {}
                    public void debug(java.util.function.Supplier<String> s) {}
                    public void debug(java.util.function.Supplier<String> s, Throwable t) {}
                    public void info(CharSequence c) {}
                    public void info(CharSequence c, Throwable t) {}
                    public void info(Throwable t) {}
                    public void info(java.util.function.Supplier<String> s) {}
                    public void info(java.util.function.Supplier<String> s, Throwable t) {}
                    public void warn(CharSequence c) {}
                    public void warn(CharSequence c, Throwable t) {}
                    public void warn(Throwable t) {}
                    public void warn(java.util.function.Supplier<String> s) {}
                    public void warn(java.util.function.Supplier<String> s, Throwable t) {}
                    public void error(CharSequence c) {}
                    public void error(CharSequence c, Throwable t) {}
                    public void error(Throwable t) {}
                    public void error(java.util.function.Supplier<String> s) {}
                    public void error(java.util.function.Supplier<String> s, Throwable t) {}
                    public boolean isDebugEnabled() { return false; }
                    public boolean isInfoEnabled() { return false; }
                    public boolean isWarnEnabled() { return false; }
                    public boolean isErrorEnabled() { return false; }
                });
        assertThat(head()).startsWith(base);
        assertThat(capture("git", "tag", "-l")).doesNotContain("2.0.0");
    }

    @Test
    void pushed_cadence_history_is_never_touched() throws Exception {
        commit("release: set version to 2.0.0");
        commit("post-release: bump to 2.0.1-SNAPSHOT");
        git("push", "origin", "main");

        WorkspaceReleaseRollback.RepoPlan plan =
                WorkspaceReleaseRollback.plan("member", repo);
        assertThat(plan.refusal()).isNull();
        assertThat(plan.hasWork()).isFalse();
    }

    @Test
    void cycle_commits_buried_under_later_work_refuse() throws Exception {
        commit("release: set version to 2.0.0");
        commit("fix: something after the failed cycle");

        WorkspaceReleaseRollback.RepoPlan plan =
                WorkspaceReleaseRollback.plan("member", repo);
        assertThat(plan.refusal()).contains("buried beneath later work");
    }

    @Test
    void dirty_tree_refuses() throws Exception {
        commit("release: set version to 2.0.0");
        Files.writeString(repo.toPath().resolve("stray.txt"), "x",
                StandardCharsets.UTF_8);
        git("add", "stray.txt");

        WorkspaceReleaseRollback.RepoPlan plan =
                WorkspaceReleaseRollback.plan("member", repo);
        assertThat(plan.refusal()).contains("uncommitted changes");
    }

    @Test
    void non_cadence_work_below_the_cycle_is_the_reset_target()
            throws Exception {
        commit("feat: developer work not yet pushed");
        String devWork = head();
        commit("release: set version to 2.0.0");

        WorkspaceReleaseRollback.RepoPlan plan =
                WorkspaceReleaseRollback.plan("member", repo);
        assertThat(plan.refusal()).isNull();
        assertThat(plan.discards()).hasSize(1);
        assertThat(plan.targetSha()).startsWith(devWork);
    }

    // ── git plumbing ─────────────────────────────────────────────────

    private void commit(String subject) throws Exception {
        Files.writeString(repo.toPath().resolve("f.txt"),
                subject, StandardCharsets.UTF_8);
        git("add", "f.txt");
        git("commit", "-m", subject);
    }

    private String head() throws Exception {
        return capture("git", "rev-parse", "--short=9", "HEAD").strip();
    }

    private void git(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        capture(cmd);
    }

    private String capture(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(repo).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        process.getInputStream().transferTo(out);
        int exit = process.waitFor();
        String text = out.toString(StandardCharsets.UTF_8);
        if (exit != 0) {
            throw new AssertionError(String.join(" ", command)
                    + " failed: " + text);
        }
        return text;
    }
}
