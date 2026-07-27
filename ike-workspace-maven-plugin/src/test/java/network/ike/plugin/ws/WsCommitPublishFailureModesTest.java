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
 * Failure-mode contracts of {@code ws:commit-publish}:
 *
 * <ul>
 *   <li>IKE-Network/ike-issues#841 — a failed push is reported as a PUSH
 *       failure naming the member repo and the local-only commit, never
 *       as "N commit(s) failed"; when only pushes failed the error says
 *       every commit is sound and hands over {@code ws:push}.</li>
 *   <li>IKE-Network/ike-issues#900 — completing a merge-in-progress with
 *       {@code -Dmessage} uses the supplied message (never
 *       {@code .git/MERGE_MSG} verbatim with its {@code # Conflicts:}
 *       comment lines) and preserves both merge parents.</li>
 * </ul>
 */
class WsCommitPublishFailureModesTest {

    @TempDir
    Path tempDir;

    private final String originalUserDir = System.getProperty("user.dir");

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() {
        helper = new TestWorkspaceHelper(tempDir);
    }

    // ── #841: push failure vs commit failure ─────────────────────

    @Test
    void pushFailure_isReportedAsPushFailure_namingRepo_withWsPushRemediation()
            throws Exception {
        Path primary = helper.buildSiblingScenario();
        Files.writeString(primary.resolve(".gitignore"),
                ".ike/vcs-state\nlib-a/\nlib-b/\napp-c/\n",
                StandardCharsets.UTF_8);
        exec(primary, "git", "add", ".gitignore");
        exec(primary, "git", "commit", "-m", "workspace: ignore subprojects");

        // Work in two subprojects.
        for (String name : new String[]{"lib-a", "app-c"}) {
            Files.writeString(primary.resolve(name).resolve("work.txt"),
                    "work in " + name, StandardCharsets.UTF_8);
        }

        // app-c's origin refuses pushes (reachable, rejecting) — the
        // transient-push-error shape of the #841 incident.
        String url = execCapture(primary.resolve("app-c"),
                "git", "remote", "get-url", "origin");
        Path hook = Path.of(java.net.URI.create(url)).resolve("hooks/pre-receive");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\n"
                + "echo 'pre-receive: pushes disabled for test' >&2\n"
                + "exit 1\n", StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);

        WsCommitPublishMojo mojo = TestLog.createMojo(WsCommitPublishMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.message = "Commit with mixed push outcomes";
        mojo.push = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                // Names the repo and the operation that actually failed…
                .hasMessageContaining("app-c — push failed")
                .hasMessageContaining("committed, local only")
                // …says the commits are sound, with the completion command…
                .hasMessageContaining("Every commit succeeded")
                .hasMessageContaining(WsGoal.PUSH.qualified())
                // …and never mis-names it a commit failure (#841).
                .hasMessageNotContaining("commit(s) failed");

        // The commit in app-c is real and local-only; lib-a pushed fine.
        assertThat(execCapture(primary.resolve("app-c"),
                "git", "log", "-1", "--format=%s"))
                .isEqualTo("Commit with mixed push outcomes");
        String appCLocal = execCapture(primary.resolve("app-c"),
                "git", "rev-parse", "HEAD");
        String appCRemote = execCapture(primary.resolve("app-c"),
                "git", "ls-remote", "origin", "main").split("\\s+")[0];
        assertThat(appCRemote).isNotEqualTo(appCLocal);
        String libALocal = execCapture(primary.resolve("lib-a"),
                "git", "rev-parse", "HEAD");
        String libARemote = execCapture(primary.resolve("lib-a"),
                "git", "ls-remote", "origin", "main").split("\\s+")[0];
        assertThat(libARemote).isEqualTo(libALocal);
    }

    // ── #900: merge-in-progress honors -Dmessage ─────────────────

    @Test
    void mergeInProgress_suppliedMessageWins_noConflictCommentLines_parentsKept()
            throws Exception {
        // Single bare-mode repo with a resolved-but-uncommitted merge.
        Path repo = Files.createDirectories(tempDir.resolve("solo"));
        exec(repo, "git", "init", "-b", "main");
        hermetic(repo);
        Files.writeString(repo.resolve("file.txt"), "base\n",
                StandardCharsets.UTF_8);
        exec(repo, "git", "add", "file.txt");
        exec(repo, "git", "commit", "-m", "base");
        exec(repo, "git", "checkout", "-b", "side");
        Files.writeString(repo.resolve("file.txt"), "side change\n",
                StandardCharsets.UTF_8);
        exec(repo, "git", "commit", "-am", "side edit");
        exec(repo, "git", "checkout", "main");
        Files.writeString(repo.resolve("file.txt"), "main change\n",
                StandardCharsets.UTF_8);
        exec(repo, "git", "commit", "-am", "main edit");

        // Conflicting merge; resolve and stage — MERGE_MSG now carries
        // the '# Conflicts:' comment block git would strip on cleanup.
        execExpectFailure(repo, "git", "merge", "side");
        Files.writeString(repo.resolve("file.txt"), "resolved\n",
                StandardCharsets.UTF_8);
        exec(repo, "git", "add", "file.txt");
        assertThat(Files.readString(repo.resolve(".git/MERGE_MSG"),
                StandardCharsets.UTF_8)).contains("Conflicts:");

        System.setProperty("user.dir", repo.toAbsolutePath().toString());
        WsCommitPublishMojo mojo = TestLog.createMojo(WsCommitPublishMojo.class);
        mojo.message = "Resolve the side merge my way";
        mojo.stagedOnly = true;

        mojo.execute();

        String body = execCapture(repo, "git", "log", "-1", "--format=%B");
        assertThat(body)
                .as("-Dmessage must win over .git/MERGE_MSG (#900)")
                .startsWith("Resolve the side merge my way");
        assertThat(body)
                .as("no MERGE_MSG comment lines may leak into the commit (#900)")
                .doesNotContain("# Conflicts")
                .doesNotContain("Conflicts:");
        assertThat(execCapture(repo, "git", "rev-list",
                "--parents", "-1", "HEAD").split("\\s+"))
                .as("the merge commit must keep both parents")
                .hasSize(3);
    }

    // ── helpers ──────────────────────────────────────────────────

    private void hermetic(Path dir) throws Exception {
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec(dir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(dir, "git", "config", "commit.gpgsign", "false");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
    }

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + output);
        }
    }

    /** Run a command expected to exit non-zero (e.g. a conflicting merge). */
    private void execExpectFailure(Path workDir, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() == 0) {
            throw new RuntimeException("Expected failure but succeeded: "
                    + String.join(" ", command));
        }
    }

    private static String execCapture(Path workDir, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(false)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command));
        }
        return output.trim();
    }
}
