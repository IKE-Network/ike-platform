package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IKE-Network/ike-issues#947: {@code ws:add -Dbranch} must produce a FULL
 * clone — full fetch refspec, full history — with branch selection
 * governing only the checkout. The old {@code --depth 1 -b <branch>}
 * clone was both single-branch (origin/main unresolvable) and shallow
 * (merge-base reported unrelated histories), which made every downstream
 * origin/&lt;target&gt; assessment affirmatively wrong.
 */
class WsAddCloneRefspecTest {

    private static final String FEATURE_BRANCH = "feature/clone-test";

    @TempDir
    Path tempDir;

    private Path wsDir;
    private String repoUrl;

    @BeforeEach
    void setUp() throws Exception {
        wsDir = Files.createDirectories(tempDir.resolve("ws"));

        // Bare upstream with main (2 commits) + a feature branch (1 more).
        Path bare = tempDir.resolve("upstream.git");
        Files.createDirectories(bare);
        exec(bare, "git", "init", "--bare", "-b", "main");

        Path work = Files.createDirectories(tempDir.resolve("work"));
        exec(work, "git", "init", "-b", "main");
        hermetic(work);
        Files.writeString(work.resolve("README.md"), "one\n",
                StandardCharsets.UTF_8);
        exec(work, "git", "add", "README.md");
        exec(work, "git", "commit", "-m", "c1");
        Files.writeString(work.resolve("README.md"), "two\n",
                StandardCharsets.UTF_8);
        exec(work, "git", "add", "README.md");
        exec(work, "git", "commit", "-m", "c2");
        exec(work, "git", "checkout", "-b", FEATURE_BRANCH);
        Files.writeString(work.resolve("feature.txt"), "f\n",
                StandardCharsets.UTF_8);
        exec(work, "git", "add", "feature.txt");
        exec(work, "git", "commit", "-m", "f1");
        exec(work, "git", "remote", "add", "origin",
                bare.toAbsolutePath().toString());
        exec(work, "git", "push", "origin", "main", FEATURE_BRANCH);

        repoUrl = bare.toUri().toString();
    }

    @Test
    void branchClone_hasFullRefspec_fullHistory_andSeesOriginMain()
            throws Exception {
        WsAddMojo.cloneSubprojectFull(wsDir, repoUrl, "sub", FEATURE_BRANCH,
                new TestLog());

        File sub = wsDir.resolve("sub").toFile();

        // Checkout: the requested branch.
        assertThat(capture(sub, "git", "branch", "--show-current"))
                .isEqualTo(FEATURE_BRANCH);

        // Refspec: full — never narrowed to the requested branch.
        assertThat(capture(sub, "git", "config", "--get",
                "remote.origin.fetch"))
                .as("branch selection must never narrow the refspec (#947)")
                .isEqualTo("+refs/heads/*:refs/remotes/origin/*");

        // origin/main is resolvable — the downstream comparisons' anchor.
        assertThat(capture(sub, "git", "rev-parse",
                "refs/remotes/origin/main")).isNotBlank();

        // Not shallow: full history, so merge ancestry works.
        assertThat(sub.toPath().resolve(".git/shallow"))
                .as("clone must not be shallow (#947)")
                .doesNotExist();
        assertThat(capture(sub, "git", "merge-base",
                FEATURE_BRANCH, "origin/main"))
                .as("merge-base must resolve (no unrelated-histories)")
                .isNotBlank();
    }

    @Test
    void missingRemoteBranch_isCreatedLocally_stillFullClone() throws Exception {
        WsAddMojo.cloneSubprojectFull(wsDir, repoUrl, "sub2",
                "feature/not-on-remote", new TestLog());

        File sub = wsDir.resolve("sub2").toFile();
        assertThat(capture(sub, "git", "branch", "--show-current"))
                .isEqualTo("feature/not-on-remote");
        assertThat(capture(sub, "git", "config", "--get",
                "remote.origin.fetch"))
                .isEqualTo("+refs/heads/*:refs/remotes/origin/*");
        assertThat(capture(sub, "git", "rev-parse",
                "refs/remotes/origin/main")).isNotBlank();
    }

    /**
     * The clone carries a repo-local {@code core.fsmonitor=false}: a fresh
     * repo's first fsmonitor daemon query can deadlock on macOS under a file
     * watcher such as Syncthing (IKE-Network/ike-issues#1052).
     */
    @Test
    void branchClone_optsOutOfFsmonitorRepoLocally() throws Exception {
        WsAddMojo.cloneSubprojectFull(wsDir, repoUrl, "sub3", FEATURE_BRANCH,
                new TestLog());

        assertThat(capture(wsDir.resolve("sub3").toFile(),
                "git", "config", "--local", "core.fsmonitor"))
                .as("ws:add clone opts out of fsmonitor (#1052)")
                .isEqualTo("false");
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

    private static String capture(File workDir, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir)
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
