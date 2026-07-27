package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IKE-Network/ike-issues#946: {@code ws:cleanup} classifies squash-finished
 * branches as content-landed (deletable) — merge-ancestry classification
 * alone can never collect branches finished by the recommended squash
 * strategy. Classification runs against {@code origin/<target>} after a
 * fetch, so a squash that landed on the remote target from another
 * machine is still recognized, and remote feature branches are deleted
 * along with local ones.
 */
class CleanupSquashAwareTest {

    private static final String BRANCH = "feature/done-work";

    @TempDir
    Path tempDir;

    private Path primary;

    @BeforeEach
    void setUp() throws Exception {
        TestWorkspaceHelper helper = new TestWorkspaceHelper(tempDir);
        primary = helper.buildSiblingScenario();
        Files.writeString(primary.resolve(".gitignore"),
                ".ike/vcs-state\nlib-a/\nlib-b/\napp-c/\n",
                StandardCharsets.UTF_8);
        exec(primary, "git", "add", ".gitignore");
        exec(primary, "git", "commit", "-m", "workspace: ignore subprojects");
    }

    /** Create a 2-commit feature branch in a member, pushed to origin. */
    private void featureBranch(String member) throws Exception {
        Path dir = primary.resolve(member);
        exec(dir, "git", "checkout", "-b", BRANCH);
        Files.writeString(dir.resolve("feature-a.txt"), "a\n",
                StandardCharsets.UTF_8);
        exec(dir, "git", "add", "feature-a.txt");
        exec(dir, "git", "commit", "-m", "feature: a");
        Files.writeString(dir.resolve("feature-b.txt"), "b\n",
                StandardCharsets.UTF_8);
        exec(dir, "git", "add", "feature-b.txt");
        exec(dir, "git", "commit", "-m", "feature: b");
        exec(dir, "git", "push", "-u", "origin", BRANCH);
        exec(dir, "git", "checkout", "main");
    }

    @Test
    void squashFinishedBranch_isClassifiedDeletable_andDeletedLocalAndRemote()
            throws Exception {
        featureBranch("lib-a");
        Path libA = primary.resolve("lib-a");
        // Squash-finish by hand: content lands on main as ONE commit —
        // the feature branch is NOT an ancestor of main.
        exec(libA, "git", "merge", "--squash", BRANCH);
        exec(libA, "git", "commit", "-m", "Squash done-work");
        exec(libA, "git", "push", "origin", "main");

        CleanupWorkspaceMojo mojo = TestLog.createMojo(CleanupWorkspaceMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.targetBranch = "main";
        mojo.publish = true;

        mojo.execute();

        assertThat(execCapture(libA, "git", "branch"))
                .as("squash-finished branch must be collected (#946)")
                .doesNotContain(BRANCH);
        assertThat(execCapture(libA,
                "git", "ls-remote", "--heads", "origin", BRANCH))
                .as("the remote feature branch is deleted too — content "
                        + "is on origin/main")
                .isEmpty();
    }

    @Test
    void activeBranch_withUnlandedContent_isKept() throws Exception {
        featureBranch("lib-b");
        // No squash — the content exists only on the feature branch.

        CleanupWorkspaceMojo mojo = TestLog.createMojo(CleanupWorkspaceMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.targetBranch = "main";
        mojo.publish = true;

        mojo.execute();

        assertThat(execCapture(primary.resolve("lib-b"), "git", "branch"))
                .as("a branch with unlanded content must never be collected")
                .contains(BRANCH);
        assertThat(execCapture(primary.resolve("lib-b"),
                "git", "ls-remote", "--heads", "origin", BRANCH))
                .contains("refs/heads/" + BRANCH);
    }

    @Test
    void squashLandedOnOriginOnly_staleLocalMain_isStillCollected()
            throws Exception {
        featureBranch("app-c");
        Path appC = primary.resolve("app-c");

        // The squash lands on ORIGIN main from a side clone (the other
        // machine); the local clone's main stays behind.
        String url = execCapture(appC, "git", "remote", "get-url", "origin");
        Path side = tempDir.resolve("side-app-c");
        exec(tempDir, "git", "clone", url, "side-app-c");
        exec(side, "git", "fetch", "origin", BRANCH + ":" + BRANCH);
        exec(side, "git", "merge", "--squash", BRANCH);
        exec(side, "git", "commit", "-m", "Squash done-work elsewhere");
        exec(side, "git", "push", "origin", "main");

        CleanupWorkspaceMojo mojo = TestLog.createMojo(CleanupWorkspaceMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.targetBranch = "main";
        mojo.publish = true;

        mojo.execute();

        assertThat(execCapture(appC, "git", "branch"))
                .as("classification must run against origin/main after a "
                        + "fetch — a stale local main cannot hide the finish")
                .doesNotContain(BRANCH);
        assertThat(execCapture(appC,
                "git", "ls-remote", "--heads", "origin", BRANCH))
                .isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────

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
