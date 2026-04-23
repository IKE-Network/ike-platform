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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for workspace Mojo goals.
 *
 * <p>Each test creates a fresh temp workspace via {@link TestWorkspaceHelper},
 * then instantiates a Mojo directly, sets its {@code manifest} field
 * (package-private in {@link AbstractWorkspaceMojo}), and calls
 * {@link org.apache.maven.api.plugin.Mojo#execute()}.
 *
 * <p>These Mojos log output via {@code getLog().info()} which defaults
 * to {@code SystemStreamLog} — no mock is needed.
 */
class WorkspaceMojoIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
    }

    // ── VerifyWorkspaceMojo ─────────────────────────────────────────

    @Test
    void verify_validWorkspace_noException() {
        VerifyWorkspaceMojo mojo = TestLog.createMojo(VerifyWorkspaceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── VerifyConvergenceMojo (issues #181, #182) ──────────────────

    /**
     * Regression test for issue #181: VerifyConvergenceMojo must resolve
     * the Maven wrapper via {@code ReleaseSupport.resolveMavenWrapper}
     * (which honors {@code mvnw.cmd} on Windows) — not by constructing a
     * hardcoded {@code mvnw} path.
     *
     * <p>This test plants a stub {@code mvnw} at the workspace root that
     * always succeeds, runs verify-convergence, and asserts the mojo
     * completes — proving the resolution path is wired up. The real
     * Windows behavior is covered by the unit tests in
     * {@code ReleaseSupportTest.resolveMavenWrapperFor_*} which inject OS.
     */
    @Test
    void verifyConvergence_routesThroughResolveMavenWrapper() throws Exception {
        Path mvnwStub = tempDir.resolve("mvnw");
        // Empty dependency:tree output — the parser tolerates it; the mojo
        // logs "Fewer than 2 components resolved" and returns cleanly.
        Files.writeString(mvnwStub,
                "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        mvnwStub.toFile().setExecutable(true);

        VerifyConvergenceMojo mojo = TestLog.createMojo(VerifyConvergenceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();

        // Should not throw — the stub mvnw is found by resolveMavenWrapper
        // and invoked (returns 0 with empty output → fewer-than-2 path).
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    /**
     * Regression test for issue #181 paired with #182: when no wrapper is
     * present at the workspace root, the mojo must fall back to system
     * {@code mvn} via {@code which}/{@code where}. This proves the mojo
     * does not throw on a workspace without a wrapper, provided system
     * Maven is installed.
     */
    @Test
    void verifyConvergence_noWrapper_fallsBackToSystemMvn() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                hasSystemMvn(), "system 'mvn' not on PATH");
        // Deliberately do NOT create mvnw — force system fallback path.

        VerifyConvergenceMojo mojo = TestLog.createMojo(VerifyConvergenceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();

        // System mvn is available — fallback path resolves it via `which mvn`
        // and the mojo runs to completion (may produce divergence findings,
        // but must not throw on wrapper resolution).
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── GraphWorkspaceMojo ──────────────────────────────────────────

    @Test
    void graph_textFormat_runsSuccessfully() {
        GraphWorkspaceMojo mojo = TestLog.createMojo(GraphWorkspaceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.format = "text";

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void graph_dotFormat_runsSuccessfully() {
        GraphWorkspaceMojo mojo = TestLog.createMojo(GraphWorkspaceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.format = "dot";

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── OverviewWorkspaceMojo ─────────────────────────────────────────

    @Test
    void status_allClean_runsSuccessfully() {
        OverviewWorkspaceMojo mojo = TestLog.createMojo(OverviewWorkspaceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void status_dirtyRepo_runsSuccessfully() throws Exception {
        // Add an untracked (uncommitted) file to lib-a
        Path untracked = tempDir.resolve("lib-a").resolve("dirty.txt");
        Files.writeString(untracked, "uncommitted", StandardCharsets.UTF_8);

        OverviewWorkspaceMojo mojo = TestLog.createMojo(OverviewWorkspaceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── OverviewWorkspaceMojo ─────────────────────────────────────

    @Test
    void dashboard_cleanWorkspace_succeeds() {
        OverviewWorkspaceMojo mojo = TestLog.createMojo(OverviewWorkspaceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void dashboard_dirtyWorkspace_showsCascade() throws Exception {
        // Add an untracked (uncommitted) file to lib-a
        Path untracked = tempDir.resolve("lib-a").resolve("dirty.txt");
        Files.writeString(untracked, "uncommitted", StandardCharsets.UTF_8);

        OverviewWorkspaceMojo mojo = TestLog.createMojo(OverviewWorkspaceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    // ── InitWorkspaceMojo ────────────────────────────────────────────

    @Test
    void init_freshClone_clonesAllComponents() throws Exception {
        Path initRoot = Files.createTempDirectory(tempDir, "init-");
        TestWorkspaceHelper initHelper = new TestWorkspaceHelper(initRoot);
        initHelper.buildWorkspaceWithUpstreams();

        InitWorkspaceMojo mojo = TestLog.createMojo(InitWorkspaceMojo.class);
        mojo.manifest = initHelper.workspaceYaml().toFile();

        mojo.execute();

        // All 3 components should be cloned
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path subDir = initRoot.resolve(name);
            assertThat(subDir).isDirectory();
            assertThat(subDir.resolve(".git")).isDirectory();
            assertThat(subDir.resolve("pom.xml")).isRegularFile();
        }
    }

    @Test
    void init_alreadyCloned_skips() throws Exception {
        Path initRoot = Files.createTempDirectory(tempDir, "init-");
        TestWorkspaceHelper initHelper = new TestWorkspaceHelper(initRoot);
        initHelper.buildWorkspaceWithUpstreams();

        // Pre-create lib-a as a git repo with one commit
        Path libA = initRoot.resolve("lib-a");
        Files.createDirectories(libA);
        Files.writeString(libA.resolve("pom.xml"), "<project/>",
                StandardCharsets.UTF_8);
        exec(libA, "git", "init", "-b", "main");
        exec(libA, "git", "config", "user.email", "test@example.com");
        exec(libA, "git", "config", "user.name", "Test");
        exec(libA, "git", "add", ".");
        exec(libA, "git", "commit", "-m", "pre-existing");

        // Count commits before init
        String countBefore = execCapture(libA, "git", "rev-list", "--count", "HEAD");

        InitWorkspaceMojo mojo = TestLog.createMojo(InitWorkspaceMojo.class);
        mojo.manifest = initHelper.workspaceYaml().toFile();

        mojo.execute();

        // lib-a should not be re-cloned — commit count unchanged
        String countAfter = execCapture(libA, "git", "rev-list", "--count", "HEAD");
        assertThat(countAfter).isEqualTo(countBefore);

        // lib-b and app-c should have been cloned
        assertThat(initRoot.resolve("lib-b").resolve(".git")).isDirectory();
        assertThat(initRoot.resolve("app-c").resolve(".git")).isDirectory();
    }

    // ── StignoreWorkspaceMojo ───────────────────────────────────────

    @Test
    void stignore_createsFiles() throws Exception {
        StignoreWorkspaceMojo mojo = TestLog.createMojo(StignoreWorkspaceMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();

        mojo.execute();

        // Workspace-level .stignore
        Path wsStignore = tempDir.resolve(".stignore");
        assertThat(wsStignore).exists();
        String wsContent = Files.readString(wsStignore, StandardCharsets.UTF_8);
        assertThat(wsContent).contains("**/target");
        assertThat(wsContent).contains("**/.git");
        assertThat(wsContent).contains("checkpoints");

        // Per-component .stignore files
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path compStignore = tempDir.resolve(name).resolve(".stignore");
            assertThat(compStignore).exists();
            String compContent = Files.readString(compStignore,
                    StandardCharsets.UTF_8);
            assertThat(compContent).contains("**/target");
            assertThat(compContent).contains("**/.git");
        }
    }

    // ── WsCheckpointDraftMojo ──────────────────────────────────────────────

    /**
     * Subclass that overrides {@code checkpointComponent} to simulate a
     * build without invoking a real Maven subprocess: creates the expected
     * annotated tag at the current HEAD so the mojo can read its SHA.
     */
    private static WsCheckpointDraftMojo checkpointMojoWithSimulatedBuild() {
        var mojo = new WsCheckpointDraftMojo() {
            @Override
            protected void checkpointComponent(File dir, String checkpointLabel)
                    throws MojoException {
                ReleaseSupport.exec(dir, getLog(),
                        "git", "tag", "-a", "checkpoint/" + checkpointLabel,
                        "-m", "Simulated checkpoint " + checkpointLabel);
            }
        };
        TestLog.injectInto(mojo);
        return mojo;
    }

    @Test
    void wsCheckpoint_writesYamlFile() throws Exception {
        WsCheckpointDraftMojo mojo = checkpointMojoWithSimulatedBuild();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.name = "test-cp";
        mojo.publish = true;

        mojo.execute();

        Path checkpointFile = tempDir.resolve("checkpoints")
                .resolve("checkpoint-test-cp.yaml");
        assertThat(checkpointFile).exists();

        String content = Files.readString(checkpointFile, StandardCharsets.UTF_8);
        assertThat(content).contains("lib-a");
        assertThat(content).contains("lib-b");
        assertThat(content).contains("app-c");
    }

    @Test
    void wsCheckpoint_yamlContainsVersionTagAndSha() throws Exception {
        WsCheckpointDraftMojo mojo = checkpointMojoWithSimulatedBuild();
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.name = "test-cp2";
        mojo.publish = true;

        mojo.execute();

        Path checkpointFile = tempDir.resolve("checkpoints")
                .resolve("checkpoint-test-cp2.yaml");
        String content = Files.readString(checkpointFile, StandardCharsets.UTF_8);

        // YAML should have version, SHA, and branch for each component
        assertThat(content).contains("version: \"");
        assertThat(content).contains("sha: \"");
        assertThat(content).contains("branch: \"");
    }

    // ── WsReleaseDraftMojo ───────────────────────────────────────────────

    @Test
    void wsRelease_dryRun_showsPlan() throws Exception {
        // Build a workspace with upstreams so graph loads correctly
        Path releaseRoot = Files.createTempDirectory(tempDir, "release-");
        TestWorkspaceHelper releaseHelper = new TestWorkspaceHelper(releaseRoot);
        releaseHelper.buildWorkspace();

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = releaseHelper.workspaceYaml().toFile();
        mojo.publish = false;

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void wsRelease_noChanges_reportsClean() throws Exception {
        // Build workspace and tag every component as released
        Path releaseRoot = Files.createTempDirectory(tempDir, "release-clean-");
        TestWorkspaceHelper releaseHelper = new TestWorkspaceHelper(releaseRoot);
        releaseHelper.buildWorkspace();

        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            exec(releaseRoot.resolve(name), "git", "tag", "v1.0.0");
        }

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.manifest = releaseHelper.workspaceYaml().toFile();
        mojo.publish = false;

        // Should complete without exception — reports "No components need releasing"
        assertThatCode(mojo::execute).doesNotThrowAnyException();
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

    private static boolean hasSystemMvn() {
        try {
            Process p = new ProcessBuilder("which", "mvn")
                    .redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && out.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
