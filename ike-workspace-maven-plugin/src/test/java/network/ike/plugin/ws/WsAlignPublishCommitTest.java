package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Declared-equals-observed tests for {@code ws:align-publish}'s
 * {@link GoalBehavior#COORDINATING} {@link AuthoredCommit#IN_ISOLATION} commit
 * and its {@code -Ddefer-commit} cascade hand-off (IKE-Network/ike-issues#780).
 *
 * <p>{@link TestWorkspaceHelper#buildWorkspace} sets up lib-a/lib-b/app-c as
 * clean git repos with app-c carrying a stale lib-b coordinate — the drift
 * align rewrites.
 */
class WsAlignPublishCommitTest {

    @TempDir
    Path tempDir;

    @Test
    void publish_commitsAlignedPomsInIsolation() throws Exception {
        new TestWorkspaceHelper(tempDir).buildWorkspace();
        Path appC = tempDir.resolve("app-c");

        WsAlignPublishMojo mojo = TestLog.createMojo(WsAlignPublishMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.scope = "poms";
        mojo.execute();

        // align rewrote app-c's stale lib-b coordinate and committed it in
        // isolation, so app-c carries a new align commit...
        String log = execCapture(appC, "git", "log", "--oneline", "-1");
        assertThat(log).contains("align inter-subproject versions");
        // ...touching pom.xml...
        String files = execCapture(appC,
                "git", "show", "--name-only", "--format=", "HEAD");
        assertThat(files).contains("pom.xml");
        // ...and the tree is clean afterward (the rewrite is committed, not
        // left as WIP).
        assertThat(execCapture(appC, "git", "status", "--porcelain")).isEmpty();
    }

    @Test
    void publish_deferCommit_writesButDoesNotCommit() throws Exception {
        new TestWorkspaceHelper(tempDir).buildWorkspace();
        Path appC = tempDir.resolve("app-c");
        String headBefore = execCapture(appC, "git", "rev-parse", "HEAD");

        WsAlignPublishMojo mojo = TestLog.createMojo(WsAlignPublishMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.scope = "poms";
        mojo.deferCommit = true;
        mojo.execute();

        // Under the cascade hand-off the aligned pom is written but left
        // UNCOMMITTED for the caller (e.g. checkpoint) to commit.
        assertThat(execCapture(appC, "git", "status", "--porcelain"))
                .contains("pom.xml");
        assertThat(execCapture(appC, "git", "rev-parse", "HEAD"))
                .as("align must author no commit under -Ddefer-commit")
                .isEqualTo(headBefore);
    }

    private String execCapture(Path workDir, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
