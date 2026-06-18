package network.ike.plugin.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bare-mode (no {@code workspace.yaml}) tests for {@code ws:report} —
 * IKE-Network/ike-issues#704. It lists {@code ws꞉*.md} reports at the
 * current repository's root (a working set of one) instead of throwing
 * "Cannot find workspace.yaml".
 */
class ReportMojoBareModeTest {

    /** {@code ws꞉} — the shared report filename prefix (U+A789). */
    private static final String PREFIX = "ws꞉";

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void bareReport_listsReportsAtSingleRepoRoot() throws Exception {
        Files.writeString(tempDir.resolve(PREFIX + "overview.md"),
                "# ws:overview\n", StandardCharsets.UTF_8);

        ReportMojo mojo = TestLog.createMojo(ReportMojo.class);
        setField(mojo, "printOnly", true);
        WorkspaceReportSpec spec = mojo.runGoal();

        assertThat(spec.content())
                .as("a bare repo's own ws꞉*.md reports are listed")
                .contains(PREFIX + "overview.md");
    }

    @Test
    void bareReport_noReports_doesNotThrow() throws Exception {
        ReportMojo mojo = TestLog.createMojo(ReportMojo.class);
        setField(mojo, "printOnly", true);
        WorkspaceReportSpec spec = mojo.runGoal();

        assertThat(spec.content())
                .as("an empty bare repo reports cleanly, not a workspace error")
                .contains("No");
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
