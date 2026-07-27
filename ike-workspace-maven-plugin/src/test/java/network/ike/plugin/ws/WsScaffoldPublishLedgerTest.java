package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the committed-work ledger of {@code ws:scaffold-publish}
 * (IKE-Network/ike-issues#954): the publish report ends by listing what the
 * run committed — per repo, with subject, short SHA, and files — plus the
 * "committed, not pushed" hint, replacing the #431-era status checklist
 * that #919's goal-owned commits had reduced to a permanent
 * "No files were modified."
 */
class WsScaffoldPublishLedgerTest {

    @TempDir
    Path tempDir;

    @Test
    void ledger_reportsGoalCommits_withPushHint() throws Exception {
        scaffoldGitWorkspace();
        WsScaffoldDraftMojo mojo = publishMojo();
        WorkspaceGraph graph = mojo.loadGraph();
        File root = mojo.workspaceRoot();
        Map<String, String> baselines =
                mojo.captureLedgerBaselines(graph, root);

        // Simulate the reconcilers rewriting the root POM, then the
        // goal-owned commit that follows them (#919).
        Files.writeString(tempDir.resolve("pom.xml"),
                Files.readString(tempDir.resolve("pom.xml"),
                        StandardCharsets.UTF_8)
                        .replace("1-SNAPSHOT", "2-SNAPSHOT"),
                StandardCharsets.UTF_8);
        mojo.commitReconcilerEdits(graph, root);

        GoalReportBuilder report = new GoalReportBuilder();
        mojo.reportGoalChanges(root, baselines, report);
        String md = report.build();

        assertThat(md)
                .contains("Committed by this goal")
                .contains("This work is committed, not pushed. To push:")
                .contains("mvn " + WsGoal.PUSH.qualified())
                .contains(WsScaffoldDraftMojo.WORKSPACE_ROOT_LABEL)
                .contains("ws:scaffold-publish: commit reconciliation edits")
                .contains("M pom.xml")
                .doesNotContain("No files were modified");
    }

    @Test
    void ledger_noChanges_statesNothingWasCommitted() throws Exception {
        scaffoldGitWorkspace();
        WsScaffoldDraftMojo mojo = publishMojo();
        WorkspaceGraph graph = mojo.loadGraph();
        File root = mojo.workspaceRoot();
        Map<String, String> baselines =
                mojo.captureLedgerBaselines(graph, root);

        GoalReportBuilder report = new GoalReportBuilder();
        mojo.reportGoalChanges(root, baselines, report);
        String md = report.build();

        assertThat(md)
                .contains("Committed by this goal")
                .contains("No changes were needed")
                .doesNotContain("To push:")
                .doesNotContain("Left uncommitted");
    }

    @Test
    void ledger_rendersResidueAsWarning() throws Exception {
        scaffoldGitWorkspace();
        WsScaffoldDraftMojo mojo = publishMojo();
        WorkspaceGraph graph = mojo.loadGraph();
        File root = mojo.workspaceRoot();
        Map<String, String> baselines =
                mojo.captureLedgerBaselines(graph, root);

        // A goal commit that failed would leave modified files uncommitted —
        // the anomaly the ledger renders as a warning.
        Files.writeString(tempDir.resolve("pom.xml"),
                Files.readString(tempDir.resolve("pom.xml"),
                        StandardCharsets.UTF_8)
                        .replace("1-SNAPSHOT", "3-SNAPSHOT"),
                StandardCharsets.UTF_8);

        GoalReportBuilder report = new GoalReportBuilder();
        mojo.reportGoalChanges(root, baselines, report);
        String md = report.build();

        assertThat(md)
                .contains("Left uncommitted")
                .contains("M pom.xml");
    }

    @Test
    void baselines_captureWorkspaceRootHead() throws Exception {
        scaffoldGitWorkspace();
        WsScaffoldDraftMojo mojo = publishMojo();
        WorkspaceGraph graph = mojo.loadGraph();

        Map<String, String> baselines =
                mojo.captureLedgerBaselines(graph, mojo.workspaceRoot());

        assertThat(baselines)
                .containsKey(WsScaffoldDraftMojo.WORKSPACE_ROOT_LABEL);
        assertThat(baselines.get(WsScaffoldDraftMojo.WORKSPACE_ROOT_LABEL))
                .isNotNull().hasSize(8);
    }

    // ── Helpers (mirrors WsScaffoldPublishSelfTripTest) ───────────────

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
                    <artifactId>scaffold-ledger-ws</artifactId>
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

    private static void setField(Object target, String field, Object value,
                                 Class<?> declaringClass) throws Exception {
        Field f = declaringClass.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
