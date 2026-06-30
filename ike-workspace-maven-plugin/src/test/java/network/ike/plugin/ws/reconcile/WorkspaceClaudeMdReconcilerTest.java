package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.TestLog;
import network.ike.plugin.ws.bootstrap.SubprojectInitializer;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WorkspaceClaudeMdReconciler} — heals the generated
 * workspace-root {@code CLAUDE.md} on {@code ws:scaffold-publish}, not just on
 * the initial {@code ws:scaffold-init} (IKE-Network/ike-issues#790).
 */
class WorkspaceClaudeMdReconcilerTest {

    private static final String WS_NAME = "test-wsr";

    @TempDir
    Path tempDir;

    private final WorkspaceClaudeMdReconciler reconciler = new WorkspaceClaudeMdReconciler();

    @BeforeEach
    void writeRootPom() throws Exception {
        // The reconciler derives the workspace name from the root POM
        // artifactId (matching ws:scaffold-init), so a deterministic POM makes
        // the generated content deterministic.
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><artifactId>" + WS_NAME + "</artifactId></project>",
                StandardCharsets.UTF_8);
    }

    @Test
    void missingFile_isStale_andApplyWritesIt() {
        WorkspaceContext ctx = ctx(ReconcilerOptions.empty());

        DriftReport detected = reconciler.detect(ctx);
        assertThat(detected.hasDrift())
                .as("a workspace with no CLAUDE.md must register as drifted")
                .isTrue();
        assertThat(detected.detailLines())
                .anyMatch(line -> line.contains("missing"));

        reconciler.apply(ctx);

        assertThat(tempDir.resolve("CLAUDE.md"))
                .as("apply must write CLAUDE.md from the generator")
                .exists()
                .hasContent(expected());

        assertThat(reconciler.detect(ctx).hasDrift())
                .as("re-running detect after apply must report clean")
                .isFalse();
    }

    @Test
    void driftedContent_isReportedAndHealed() throws Exception {
        Files.writeString(tempDir.resolve("CLAUDE.md"),
                "hand-edited content, not the generator output",
                StandardCharsets.UTF_8);

        WorkspaceContext ctx = ctx(ReconcilerOptions.empty());
        DriftReport report = reconciler.detect(ctx);
        assertThat(report.hasDrift()).isTrue();
        assertThat(report.detailLines())
                .anyMatch(line -> line.contains("drifted"));

        reconciler.apply(ctx);
        assertThat(tempDir.resolve("CLAUDE.md")).hasContent(expected());
    }

    @Test
    void upToDateFile_reportsNoDrift() throws Exception {
        Files.writeString(tempDir.resolve("CLAUDE.md"), expected(),
                StandardCharsets.UTF_8);
        assertThat(reconciler.detect(ctx(ReconcilerOptions.empty())).hasDrift())
                .as("a CLAUDE.md matching the generator is not drift")
                .isFalse();
    }

    @Test
    void applyOptedOut_leavesFileAlone() throws Exception {
        Files.writeString(tempDir.resolve("CLAUDE.md"),
                "stale CLAUDE", StandardCharsets.UTF_8);
        WorkspaceContext ctx =
                ctx(new ReconcilerOptions(Map.of("updateClaudeMd", "false")));

        // detect always reports state regardless of opt-out (opt-out gates
        // only apply()).
        assertThat(reconciler.detect(ctx).hasDrift()).isTrue();

        reconciler.apply(ctx);

        assertThat(tempDir.resolve("CLAUDE.md"))
                .as("opt-out must preserve user content")
                .hasContent("stale CLAUDE");
    }

    @Test
    void dimensionAndOptOutFlag_areStable() {
        assertThat(reconciler.dimension()).isEqualTo("Workspace CLAUDE.md");
        assertThat(reconciler.optOutFlag()).isEqualTo("updateClaudeMd");
    }

    @Test
    void registryIncludesReconciler() {
        boolean present = ReconcilerRegistry.all().stream()
                .anyMatch(r -> r instanceof WorkspaceClaudeMdReconciler);
        assertThat(present)
                .as("WorkspaceClaudeMdReconciler must be wired into the registry "
                        + "so ws:scaffold-publish picks it up")
                .isTrue();
    }

    private String expected() {
        return SubprojectInitializer.generateWorkspaceClaudeMd(WS_NAME, graph());
    }

    private WorkspaceGraph graph() {
        Manifest manifest = ManifestReader.read(new StringReader("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                """));
        return new WorkspaceGraph(manifest);
    }

    private WorkspaceContext ctx(ReconcilerOptions options) {
        return new WorkspaceContext(
                tempDir.toFile(),
                tempDir.resolve("workspace.yaml"),
                graph(),
                options,
                new TestLog());
    }
}
