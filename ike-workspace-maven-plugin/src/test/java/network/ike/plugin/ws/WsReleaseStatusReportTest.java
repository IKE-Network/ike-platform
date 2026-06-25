package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report coverage for {@link WsReleaseStatusMojo} after its migration to
 * the shared {@link WorkingSetReportTable} (#767, under epic #764).
 *
 * <p>The goal previously walked {@code graph.topologicalSort()} — the
 * subprojects only — so the workspace root (the aggregator) never got a
 * row and its release state, including a stale POM version, was invisible
 * (#763). The migration switches the walk to
 * {@link AbstractWorkspaceMojo#resolveWorkingSet()}{@code .members()},
 * which yields the aggregator as a first-class member. This test scaffolds
 * a workspace whose <em>root</em> is a real git repo carrying a distinctive
 * stale version, drives {@code ws:release-status}, and asserts that the
 * aggregator row appears — labeled {@code aggregator} — and that the root's
 * version string is rendered (the #763 fix).
 */
class WsReleaseStatusReportTest {

    @TempDir
    Path tempDir;

    private static final String ROOT_VERSION = "1-release-status-it-SNAPSHOT";

    @Test
    void report_includesAggregatorRow_withRootVersion() throws Exception {
        TestWorkspaceHelper helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
        // The helper builds the three subprojects but leaves the
        // workspace root without a git repo or POM. Give the root both —
        // a distinctive stale version mirrors the #763 case the old
        // subprojects-only table hid.
        gitInitRootWithPom();

        WsReleaseStatusMojo mojo =
                TestLog.createMojo(WsReleaseStatusMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.execute();

        String report = readReport("release-status");

        // Subproject rows still appear.
        assertThat(report)
                .as("subproject rows survive the migration")
                .contains("lib-a")
                .contains("lib-b")
                .contains("app-c")
                .contains("subproject");

        // The aggregator (workspace root) is now a row, labeled — the
        // staleness the subprojects-only table hid is visible.
        String rootName = tempDir.getFileName().toString();
        assertThat(report)
                .as("the aggregator row appears, labeled 'aggregator'")
                .contains(rootName)
                .contains("aggregator");

        // #763 fix: the root's own POM version is gathered like a
        // subproject and rendered, so a root left on a stale version is
        // no longer invisible.
        assertThat(report)
                .as("the aggregator's POM version is rendered (#763)")
                .contains(ROOT_VERSION);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void gitInitRootWithPom() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <packaging>pom</packaging>
                </project>
                """.formatted(tempDir.getFileName().toString(), ROOT_VERSION),
                StandardCharsets.UTF_8);
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(tempDir, "git", "config", "commit.gpgsign", "false");
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "add", "pom.xml", "workspace.yaml");
        exec(tempDir, "git", "commit", "-m", "Initial workspace root");
    }

    private String readReport(String goalStem) throws Exception {
        // WorkspaceReport names files "ws<COLON>release-status.md"; find
        // the first matching .md in the workspace root so the test
        // tolerates the colon-substitution char without hard-coding it.
        try (Stream<Path> stream = Files.list(tempDir)) {
            Path reportFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getFileName().toString().contains(goalStem))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No report file matching '" + goalStem
                                    + "' in " + tempDir));
            return Files.readString(reportFile, StandardCharsets.UTF_8);
        }
    }

    private static void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit " + exitCode
                    + "): " + String.join(" ", command) + "\n" + out);
        }
    }
}
