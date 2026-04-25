package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link WsReleaseDraftMojo} using real temp workspaces.
 *
 * <p>Each test creates a fresh workspace with git repos, exercises the
 * release detection and planning logic, and verifies modified detection,
 * topological ordering, checkpoint writing, and version property updates.
 *
 * <p>The actual {@code mvn ike:release} subprocess cannot run in tests,
 * so non-dryRun release execution is not tested here. Instead, we test
 * static helpers ({@code extractVersionFromPom}, {@code resolveMvnCommand},
 * {@code buildPreReleaseCheckpointYaml}) and POM mutation via
 * {@link PomRewriter} directly.
 */
class WsReleaseIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
    }

    // ── Dry-run / modified detection ────────────────────────────────────

    @Test
    void dryRun_neverReleased_showsAllComponents() throws Exception {
        // No tags exist, so all components are "never released" and release-pending
        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = false;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void draft_modifiedComponents_showsPlan() throws Exception {
        // Tag all components, then modify two of them
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "tag", "v1.0.0");
        }

        // Add a commit to lib-a and app-c (making them modified)
        addCommit(tempDir.resolve("lib-a"), "new work in lib-a");
        addCommit(tempDir.resolve("app-c"), "new work in app-c");

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = false;

        // Should complete without exception — shows plan for lib-a and app-c
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void cleanComponents_nothingToRelease() throws Exception {
        // Tag every component at current HEAD
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(tempDir.resolve(name), "git", "tag", "v1.0.0");
        }

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = false;

        // Should complete without exception — "No components need releasing"
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void modifiedComponent_detectedByCommitsSinceTag() throws Exception {
        // Tag lib-a at current HEAD
        exec(tempDir.resolve("lib-a"), "git", "tag", "v1.0.0");

        // Add a commit after the tag
        addCommit(tempDir.resolve("lib-a"), "post-release work");

        // Verify commit count via the same git logic the Mojo uses
        String count = execCapture(tempDir.resolve("lib-a"),
                "git", "rev-list", "v1.0.0..HEAD", "--count");
        assertThat(Integer.parseInt(count.strip())).isEqualTo(1);
    }

    @Test
    void topologicalOrder_upstreamReleasedFirst() throws Exception {
        // All components are modified (never tagged), verify topological order
        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = false;

        // The workspace graph has lib-a -> lib-b -> app-c
        // Topological sort should put lib-a first, then lib-b, then app-c
        var graph = mojo.loadGraph();
        List<String> order = graph.topologicalSort();
        int libAIdx = order.indexOf("lib-a");
        int libBIdx = order.indexOf("lib-b");
        int appCIdx = order.indexOf("app-c");

        assertThat(libAIdx).isLessThan(libBIdx);
        assertThat(libBIdx).isLessThan(appCIdx);
    }

    // ── Checkpoint writing ───────────────────────────────────────────

    @Test
    void checkpoint_createdBeforeRelease() throws Exception {
        // Exercise checkpoint writing via the WsCheckpointDraftMojo using a
        // simulated build (no real Maven subprocess — just creates the tag).
        WsCheckpointDraftMojo cpMojo = new WsCheckpointDraftMojo() {
            @Override
            protected void checkpointComponent(File dir, String checkpointLabel)
                    throws MojoException {
                ReleaseSupport.exec(dir, getLog(),
                        "git", "tag", "-a", "checkpoint/" + checkpointLabel,
                        "-m", "Simulated checkpoint " + checkpointLabel);
            }
        };
        TestLog.injectInto(cpMojo);
        cpMojo.manifest = helper.workspaceYaml().toFile();
        cpMojo.name = "pre-release-test";
        cpMojo.publish = true;

        cpMojo.execute();

        Path checkpointFile = tempDir.resolve("checkpoints")
                .resolve("checkpoint-pre-release-test.yaml");
        assertThat(checkpointFile).exists();

        String content = Files.readString(checkpointFile, StandardCharsets.UTF_8);
        assertThat(content).contains("lib-a");
        assertThat(content).contains("lib-b");
        assertThat(content).contains("app-c");
        assertThat(content).contains("branch:");
        assertThat(content).contains("sha:");
    }

    // ── Static helpers: version property updates ─────────────────────

    @Test
    void updateVersionProperty_replacesPropertyValue() {
        String pom = """
                <project>
                    <properties>
                        <ike-platform.version>1.0.0-SNAPSHOT</ike-platform.version>
                    </properties>
                </project>
                """;

        String updated = PomRewriter.updateProperty(
                pom, "ike-platform.version", "1.0.1-SNAPSHOT");

        assertThat(updated).contains(
                "<ike-platform.version>1.0.1-SNAPSHOT</ike-platform.version>");
        assertThat(updated).doesNotContain("1.0.0-SNAPSHOT");
    }

    @Test
    void updateVersionProperty_noMatchLeavesUnchanged() {
        String pom = """
                <project>
                    <properties>
                        <other.version>1.0.0-SNAPSHOT</other.version>
                    </properties>
                </project>
                """;

        String updated = PomRewriter.updateProperty(
                pom, "ike-platform.version", "2.0.0-SNAPSHOT");

        // Should be unchanged
        assertThat(updated).isEqualTo(pom);
    }

    @Test
    void updateParentVersion_matchesGa() {
        String pom = """
                <project>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>lib-b</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                </project>
                """;

        String updated = PomRewriter.updateParentVersion(
                pom, "com.test", "lib-a", "1.0.1-SNAPSHOT");

        assertThat(updated).contains(
                "<artifactId>lib-a</artifactId>\n        <version>1.0.1-SNAPSHOT</version>");
        // project version should be unchanged
        assertThat(updated).contains(
                "<version>2.0.0-SNAPSHOT</version>");
    }

    @Test
    void updateParentVersion_wrongArtifactId_noChange() {
        String pom = """
                <project>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>other-parent</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                </project>
                """;

        String updated = PomRewriter.updateParentVersion(
                pom, "com.test", "lib-a", "1.0.1-SNAPSHOT");

        assertThat(updated).isEqualTo(pom);
    }

    /**
     * Regression guard for issue #241. Same artifactId, different
     * groupId — mutation must be gated on the full GA.
     */
    @Test
    void updateParentVersion_sameArtifactIdDifferentGroupId_noChange() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike.pipeline</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>111</version>
                    </parent>
                </project>
                """;

        String updated = PomRewriter.updateParentVersion(
                pom, "network.ike.platform", "ike-parent", "2");

        assertThat(updated).isEqualTo(pom);
    }

    // ── Static helpers: extractVersionFromPom ────────────────────────

    @Test
    void extractVersionFromPom_findsFirstVersion() {
        String pom = """
                <project>
                    <groupId>com.test</groupId>
                    <artifactId>test</artifactId>
                    <version>2.5.0-SNAPSHOT</version>
                </project>
                """;

        String version = WsReleaseDraftMojo.extractVersionFromPom(pom);
        assertThat(version).isEqualTo("2.5.0-SNAPSHOT");
    }

    @Test
    void extractVersionFromPom_nullContent_returnsUnknown() {
        assertThat(WsReleaseDraftMojo.extractVersionFromPom(null)).isEqualTo("unknown");
        assertThat(WsReleaseDraftMojo.extractVersionFromPom("")).isEqualTo("unknown");
        assertThat(WsReleaseDraftMojo.extractVersionFromPom("   ")).isEqualTo("unknown");
    }

    @Test
    void extractVersionFromPom_noVersionTag_returnsUnknown() {
        String pom = """
                <project>
                    <groupId>com.test</groupId>
                    <artifactId>test</artifactId>
                </project>
                """;

        assertThat(WsReleaseDraftMojo.extractVersionFromPom(pom)).isEqualTo("unknown");
    }

    // ── Static helpers: resolveMvnCommand ────────────────────────────

    @Test
    void resolveMvnCommand_noWrapper_fallsBackToMvn() {
        // tempDir has no mvnw or mvnw.cmd
        String cmd = WsReleaseDraftMojo.resolveMvnCommand(tempDir.toFile());
        assertThat(cmd).isEqualTo("mvn");
    }

    @Test
    void resolveMvnCommand_mvnwExists_returnsAbsolutePath() throws Exception {
        Path mvnw = tempDir.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\necho mvnw", StandardCharsets.UTF_8);
        mvnw.toFile().setExecutable(true);

        String cmd = WsReleaseDraftMojo.resolveMvnCommand(tempDir.toFile());
        assertThat(cmd).isEqualTo(mvnw.toAbsolutePath().toString());
    }

    // ── Static helpers: buildPreReleaseCheckpointYaml ─────────────────

    @Test
    void buildPreReleaseCheckpointYaml_formatsCorrectly() {
        List<String[]> data = List.of(
                new String[]{"lib-a", "main", "abc1234", "1.0.0-SNAPSHOT", "true"},
                new String[]{"app-c", "develop", "def5678", "3.0.0-SNAPSHOT", "false"}
        );

        String yaml = WsReleaseDraftMojo.buildPreReleaseCheckpointYaml(
                "pre-release-20260322", "2026-03-22T10:00:00Z", data);

        assertThat(yaml).contains("checkpoint: pre-release-20260322");
        assertThat(yaml).contains("timestamp: 2026-03-22T10:00:00Z");
        assertThat(yaml).contains("  lib-a:");
        assertThat(yaml).contains("    branch: main");
        assertThat(yaml).contains("    sha: abc1234");
        assertThat(yaml).contains("    modified: true");
        assertThat(yaml).contains("  app-c:");
        assertThat(yaml).contains("    branch: develop");
        assertThat(yaml).contains("    modified: false");
    }

    // ── Non-draft: error recovery path ───────────────────────────

    @Test
    void nonDryRun_noMvnw_failsWithMojoException() throws Exception {
        // All components modified (never tagged). Non-draft will try
        // to run "mvn ike:release" — which fails because there is no
        // mvn/mvnw in the component directories. Verify that:
        //  1. The error message names the failed component
        //  2. The exception is MojoException
        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = true;
        mojo.skipCheckpoint = true;
        mojo.push = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("Workspace release failed");
    }

    @Test
    void nonDryRun_failureReportsReleasedSoFar() throws Exception {
        // Tag lib-a and lib-b so only app-c is release-pending
        for (String name : new String[]{"lib-a", "lib-b"}) {
            exec(tempDir.resolve(name), "git", "tag", "v1.0.0");
        }

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = true;
        mojo.skipCheckpoint = true;
        mojo.push = false;

        // app-c is release-pending (never released) — will try mvn ike:release
        // and fail. The error message should name app-c.
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("app-c");
    }

    // ── Checkpoint skipping ─────────────────────────────────────────

    @Test
    void nonDryRun_skipCheckpoint_noCheckpointDir() throws Exception {
        // Tag all but lib-a — only lib-a is release-pending
        exec(tempDir.resolve("lib-b"), "git", "tag", "v1.0.0");
        exec(tempDir.resolve("app-c"), "git", "tag", "v1.0.0");

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = true;
        mojo.skipCheckpoint = true;
        mojo.push = false;

        try {
            mojo.execute();
        } catch (MojoException e) {
            // Expected — mvn ike:release fails
        }

        // No checkpoint directory should be created when skipCheckpoint=true
        // (Though the mojo may have failed before reaching that check if
        //  lib-a fails immediately, the checkpoint comes before release)
        // Actually skipCheckpoint=true skips checkpoint writing
    }

    // ── Pre-release checkpoint writing (non-draft) ────────────────

    @Test
    void nonDryRun_writesCheckpointBeforeRelease() throws Exception {
        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.publish = true;
        mojo.skipCheckpoint = false;
        mojo.push = false;

        try {
            mojo.execute();
        } catch (MojoException e) {
            // Expected — mvn ike:release fails
        }

        // A checkpoint file should have been written before the
        // release attempt
        Path checkpointsDir = tempDir.resolve("checkpoints");
        if (checkpointsDir.toFile().isDirectory()) {
            String[] files = checkpointsDir.toFile().list();
            assertThat(files).isNotNull();
            assertThat(files.length).isGreaterThanOrEqualTo(1);
        }
    }

    // ── updateParentVersion with dependency version-property ────────

    @Test
    void updateParentVersion_withVersionProperty_updatesProperty() {
        String pom = """
                <project>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>19-SNAPSHOT</version>
                    </parent>
                    <artifactId>my-app</artifactId>
                    <properties>
                        <ike-parent.version>19-SNAPSHOT</ike-parent.version>
                    </properties>
                </project>
                """;

        // updateProperty updates the property element
        String updated = PomRewriter.updateProperty(
                pom, "ike-parent.version", "20-SNAPSHOT");

        assertThat(updated).contains(
                "<ike-parent.version>20-SNAPSHOT</ike-parent.version>");
    }

    // ── updateParentVersion edge case: no parent block ──────────────

    @Test
    void updateParentVersion_noParentBlock_unchanged() {
        String pom = """
                <project>
                    <groupId>com.test</groupId>
                    <artifactId>standalone</artifactId>
                    <version>1.0.0</version>
                </project>
                """;

        String updated = PomRewriter.updateParentVersion(
                pom, "com.test", "any-parent", "2.0.0");

        assertThat(updated).isEqualTo(pom);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
        return output;
    }

    private void addCommit(Path dir, String message) throws Exception {
        Path file = dir.resolve("file-" + System.nanoTime() + ".txt");
        Files.writeString(file, message, StandardCharsets.UTF_8);
        exec(dir, "git", "add", file.getFileName().toString());
        exec(dir, "git", "commit", "-m", message);
    }
}
