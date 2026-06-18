package network.ike.plugin.ws;

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
 * Bare-mode (no {@code workspace.yaml}) integration tests for
 * {@code ws:pull} and {@code ws:sync} — IKE-Network/ike-issues#703, which
 * completes the #611 working-set migration so these match {@code ws:commit}
 * / {@code ws:push} and run on a working set of one instead of failing with
 * "Cannot find workspace.yaml".
 */
class PullSyncBareModeTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void barePull_withoutUpstream_runsAndEmitsPlainRecovery() throws Exception {
        initRepo(tempDir);
        useAsCwd(tempDir);

        PullWorkspaceMojo mojo = TestLog.createMojo(PullWorkspaceMojo.class);
        WorkspaceReportSpec spec = mojo.runGoal();

        String report = spec.content();
        assertThat(report)
                .as("bare pull must run, not demand a workspace")
                .contains("No upstream configured");
        // atCwd ⇒ the recovery command is plain (no `cd <subdir>` prefix),
        // because for a single repo you are already in it.
        assertThat(report)
                .contains("git push -u origin main")
                .doesNotContain("(cd ");
    }

    @Test
    void barePull_upToDate_recordsExplicitState() throws Exception {
        initRepo(tempDir);
        addBareOrigin(tempDir);
        useAsCwd(tempDir);

        PullWorkspaceMojo mojo = TestLog.createMojo(PullWorkspaceMojo.class);
        WorkspaceReportSpec spec = mojo.runGoal();

        assertThat(spec.content())
                .as("a single repo with an upstream pulls and reports up-to-date")
                .contains("up-to-date");
    }

    @Test
    void barePull_notAGitRepo_failsWithClearError() throws Exception {
        // A directory with neither workspace.yaml nor .git resolves to a
        // working set of one, then fails on the missing repo — not with the
        // generic "Cannot find workspace.yaml".
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>",
                StandardCharsets.UTF_8);
        useAsCwd(tempDir);

        PullWorkspaceMojo mojo = TestLog.createMojo(PullWorkspaceMojo.class);
        assertThatThrownBy(mojo::runGoal)
                .hasMessageContaining("not a git repository")
                .hasMessageNotContaining("workspace.yaml");
    }

    @Test
    void bareSync_runsBothHalvesWithoutAWorkspace() throws Exception {
        initRepo(tempDir);
        addBareOrigin(tempDir);
        useAsCwd(tempDir);

        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        setField(mojo, "remote", "origin");
        WorkspaceReportSpec spec = mojo.runGoal();

        // Both phases ran (pull then push) against the single repo; the
        // workspace-only refresh-main / PostMutationSync steps were skipped
        // and nothing threw "Cannot find workspace.yaml".
        assertThat(spec.content())
                .contains("Pull phase")
                .contains("Push phase");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Initialise {@code dir} as a git repo on {@code main} with one commit. */
    private static void initRepo(Path dir) throws Exception {
        exec(dir, "git", "init", "-b", "main");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>bare-repo</artifactId>
                    <version>1-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);
        exec(dir, "git", "add", ".");
        exec(dir, "git", "commit", "-m", "Initial commit");
    }

    /**
     * Give {@code repo} a bare {@code origin} and push {@code main} with
     * upstream tracking, leaving it up-to-date.
     */
    private static void addBareOrigin(Path repo) throws Exception {
        Path origin = Files.createTempDirectory("bare-origin-");
        exec(origin, "git", "init", "--bare", "-b", "main");
        exec(repo, "git", "remote", "add", "origin",
                origin.toAbsolutePath().toString());
        exec(repo, "git", "push", "-u", "origin", "main");
    }

    private void useAsCwd(Path dir) {
        System.setProperty("user.dir", dir.toAbsolutePath().toString());
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

    private static void setField(Object target, String fieldName, Object value)
            throws Exception {
        var f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
