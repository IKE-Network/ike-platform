package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link FeatureStartSiblingDraftMojo}
 * (IKE-Network/ike-issues#770): the read-only preview of a sibling-clone
 * feature start. It writes a plan + preflight report and makes no clone.
 */
class FeatureStartSiblingDraftTest {

    private static final List<String> COMPONENTS = List.of("lib-a", "lib-b", "app-c");

    @TempDir
    Path tempDir;

    private Path primary;

    @BeforeEach
    void setUp() throws Exception {
        primary = new TestWorkspaceHelper(tempDir).buildSiblingScenario();
    }

    @Test
    void draft_writesPlanAndPreflight_makesNoClone() throws Exception {
        FeatureStartSiblingDraftMojo mojo =
                TestLog.createMojo(FeatureStartSiblingDraftMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-456";

        mojo.execute();

        // No sibling directory created — this is a preview.
        assertThat(tempDir.resolve("primary-jira-456")).doesNotExist();

        // The primary is untouched, still on main.
        assertThat(branch(primary)).isEqualTo("main");

        String report = readReport("feature-start-sibling-draft");

        // Plan section: the sibling dir, branch, base, and every member.
        assertThat(report).contains("Preview").contains("no clone");
        assertThat(report).contains("primary-jira-456");
        assertThat(report).contains("feature/jira-456");
        assertThat(report).contains("Plan");
        assertThat(report).contains("primary").contains("aggregator");
        for (String name : COMPONENTS) {
            assertThat(report).as("plan row for " + name).contains(name);
        }

        // Preflight section with the checks.
        assertThat(report).contains("Preflight");
        assertThat(report).contains("already exist");
        assertThat(report).contains("origin");
        assertThat(report).contains("Base branch");

        // Clone-cost note.
        assertThat(report).contains("Clone cost")
                .contains("--reference").contains("--dissociate");

        // Base resolved from main (the manifest base).
        assertThat(report).contains("**Base:** `main`");
    }

    @Test
    void draft_onFeatureBranch_withoutFrom_refusesWithGuard() throws Exception {
        // Off the manifest base, no -Dfrom — the same guard the publish mojo
        // applies must abort the preview.
        execCapture(primary, "git", "checkout", "-b", "feature/wip");

        FeatureStartSiblingDraftMojo mojo =
                TestLog.createMojo(FeatureStartSiblingDraftMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-456";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("not the base branch")
                .hasMessageContaining("-Dfrom=feature/wip");
    }

    @Test
    void draft_onFeatureBranch_withFrom_previews() throws Exception {
        execCapture(primary, "git", "checkout", "-b", "feature/wip");

        FeatureStartSiblingDraftMojo mojo =
                TestLog.createMojo(FeatureStartSiblingDraftMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-456";
        mojo.from = "feature/wip";

        mojo.execute();

        assertThat(tempDir.resolve("primary-jira-456")).doesNotExist();
        String report = readReport("feature-start-sibling-draft");
        assertThat(report).contains("**Base:** `feature/wip`");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String branch(Path repo) throws Exception {
        return execCapture(repo, "git", "rev-parse", "--abbrev-ref", "HEAD");
    }

    private String readReport(String goalStem) throws Exception {
        try (Stream<Path> stream = Files.list(primary)) {
            Path reportFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getFileName().toString().contains(goalStem))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No report file matching '" + goalStem
                                    + "' in " + primary));
            return Files.readString(reportFile, StandardCharsets.UTF_8);
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
                            + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
