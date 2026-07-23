package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for IKE-Network/ike-issues#919 — {@code ws:scaffold-publish}
 * used to self-trip: its workspace reconcilers rewrite every POM (parent-version
 * cascade), then the per-subproject {@code ike:scaffold-publish} fan-out rejects
 * the tree those reconcilers just dirtied.
 *
 * <p>The fix mirrors the #537 checkpoint precedent — an up-front clean-tree
 * preflight, then a goal-owned commit of the reconciler output before the
 * fan-out. These tests exercise both halves against the workspace root.
 */
class WsScaffoldPublishSelfTripTest {

    @TempDir
    Path tempDir;

    @Test
    void reconcilerEdits_areCommittedUnderGoalOwnedMessage() throws Exception {
        scaffoldGitWorkspace();
        WsScaffoldDraftMojo mojo = publishMojo();
        WorkspaceGraph graph = mojo.loadGraph();
        File root = mojo.workspaceRoot();

        // Simulate the reconcilers rewriting the root POM (parent-version
        // cascade) — the dirtiness that used to trip the fan-out.
        Files.writeString(tempDir.resolve("pom.xml"),
                Files.readString(tempDir.resolve("pom.xml"), StandardCharsets.UTF_8)
                        .replace("1-SNAPSHOT", "2-SNAPSHOT"),
                StandardCharsets.UTF_8);

        mojo.commitReconcilerEdits(graph, root, new GoalReportBuilder());

        assertThat(execCapture(tempDir, "git", "status", "--porcelain").strip())
                .as("goal-owned commit must leave a clean tree for the fan-out")
                .isEmpty();
        assertThat(execCapture(tempDir, "git", "log", "--format=%s"))
                .as("the reconciler edits are recorded under a goal-owned message")
                .contains("ws:scaffold-publish: commit reconciliation edits");
    }

    @Test
    void cleanTree_isANoOp() throws Exception {
        scaffoldGitWorkspace();
        WsScaffoldDraftMojo mojo = publishMojo();
        WorkspaceGraph graph = mojo.loadGraph();
        String headBefore = execCapture(tempDir, "git", "rev-parse", "HEAD").strip();

        mojo.commitReconcilerEdits(graph, mojo.workspaceRoot(),
                new GoalReportBuilder());

        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD").strip())
                .as("nothing to commit → no new commit")
                .isEqualTo(headBefore);
    }

    @Test
    void uncommittedUserWork_failsPreflightBeforeReconciling() throws Exception {
        scaffoldGitWorkspace();
        WsScaffoldDraftMojo mojo = publishMojo();
        WorkspaceGraph graph = mojo.loadGraph();

        // User has uncommitted work — the up-front preflight must catch it so
        // it is never swept into a goal-owned reconciliation commit.
        Files.writeString(tempDir.resolve("notes.txt"), "wip\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() ->
                mojo.requireCleanTreesForPublish(graph, mojo.workspaceRoot()))
                .as("uncommitted user work must fail the preflight")
                .isInstanceOf(MojoException.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private WsScaffoldDraftMojo publishMojo() throws Exception {
        WsScaffoldDraftMojo mojo = new WsScaffoldDraftMojo();
        TestLog.injectInto(mojo);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "publish", true, WsScaffoldDraftMojo.class);
        return mojo;
    }

    private void scaffoldGitWorkspace() throws Exception {
        Path noHooks = tempDir.resolve("no-hooks");
        Files.createDirectories(noHooks);

        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.1"
                generated: "2026-07-23"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>scaffold-self-trip-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);

        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "config", "commit.gpgsign", "false");
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "initial");
    }

    private static void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command));
        }
    }

    private static String execCapture(Path workDir, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
        return out;
    }

    private static void setField(Object target, String field, Object value,
                                 Class<?> declaringClass) throws Exception {
        Field f = declaringClass.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
