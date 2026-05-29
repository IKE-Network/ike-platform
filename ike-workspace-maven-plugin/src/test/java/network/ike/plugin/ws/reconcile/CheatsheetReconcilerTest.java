package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.TestLog;
import network.ike.plugin.ws.bootstrap.SubprojectInitializer;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CheatsheetReconciler} — the reconciler shape that
 * makes {@code GOALS.md} / {@code WS-REFERENCE.md} regenerate on
 * {@code ws:scaffold-publish} too, not just on {@code ws:scaffold-init}
 * (IKE-Network/ike-issues#452).
 */
class CheatsheetReconcilerTest {

    @TempDir
    Path tempDir;

    private final CheatsheetReconciler reconciler = new CheatsheetReconciler();

    @Test
    void noFiles_isStale_andApplyWritesBoth() throws Exception {
        WorkspaceContext ctx = ctx(ReconcilerOptions.empty());

        DriftReport detected = reconciler.detect(ctx);
        assertThat(detected.hasDrift())
                .as("a workspace with no cheatsheets must register as drifted")
                .isTrue();
        assertThat(detected.summary()).contains("2 cheatsheets stale");

        reconciler.apply(ctx);

        assertThat(tempDir.resolve("GOALS.md"))
                .as("apply must write GOALS.md from the generator")
                .exists()
                .hasContent(SubprojectInitializer.generateGoalCheatsheet());
        assertThat(tempDir.resolve("WS-REFERENCE.md"))
                .as("apply must write WS-REFERENCE.md from the generator")
                .exists()
                .hasContent(SubprojectInitializer.generateWorkspaceReference());

        // Idempotent: a second pass should see no drift.
        assertThat(reconciler.detect(ctx).hasDrift())
                .as("re-running detect after apply must report clean")
                .isFalse();
    }

    @Test
    void staleGoalsFile_onlyTheStaleOneIsReported() throws Exception {
        Files.writeString(tempDir.resolve("GOALS.md"),
                "old hand-edited content, not the generator's output",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("WS-REFERENCE.md"),
                SubprojectInitializer.generateWorkspaceReference(),
                StandardCharsets.UTF_8);

        DriftReport report = reconciler.detect(ctx(ReconcilerOptions.empty()));
        assertThat(report.hasDrift()).isTrue();
        assertThat(report.summary()).contains("1 cheatsheet stale");
        assertThat(report.detailLines())
                .as("only GOALS.md should appear in the drift detail")
                .anyMatch(line -> line.contains("GOALS.md"))
                .noneMatch(line -> line.contains("WS-REFERENCE.md"));
    }

    @Test
    void applyOptedOut_leavesStaleFilesAlone() throws Exception {
        // Pre-populate both with stale content so detect would normally
        // report drift; apply with -DupdateCheatsheets=false must leave
        // them untouched.
        Files.writeString(tempDir.resolve("GOALS.md"),
                "stale GOALS", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("WS-REFERENCE.md"),
                "stale REFERENCE", StandardCharsets.UTF_8);

        ReconcilerOptions optOut = new ReconcilerOptions(
                Map.of("updateCheatsheets", "false"));
        WorkspaceContext ctx = ctx(optOut);

        // Sanity: detect always reports state regardless of opt-out
        // (opt-out gates only apply()).
        assertThat(reconciler.detect(ctx).hasDrift()).isTrue();

        reconciler.apply(ctx);

        assertThat(tempDir.resolve("GOALS.md"))
                .as("opt-out must preserve user content")
                .hasContent("stale GOALS");
        assertThat(tempDir.resolve("WS-REFERENCE.md"))
                .as("opt-out must preserve user content")
                .hasContent("stale REFERENCE");
    }

    @Test
    void dimensionAndOptOutFlag_areStable() {
        assertThat(reconciler.dimension())
                .as("dimension is rendered in scaffold-draft output and "
                        + "should stay short + descriptive")
                .isEqualTo("Workspace cheatsheets (GOALS.md, WS-REFERENCE.md)");
        assertThat(reconciler.optOutFlag())
                .as("opt-out flag is referenced from docs / muscle memory")
                .isEqualTo("updateCheatsheets");
    }

    @Test
    void registryIncludesCheatsheetReconciler() {
        boolean present = ReconcilerRegistry.all().stream()
                .anyMatch(r -> r instanceof CheatsheetReconciler);
        assertThat(present)
                .as("CheatsheetReconciler must be wired into the registry "
                        + "so ws:scaffold-publish picks it up")
                .isTrue();
    }

    private WorkspaceContext ctx(ReconcilerOptions options) {
        // Minimal manifest — the cheatsheet generators don't read the
        // graph, but WorkspaceContext requires non-null fields.
        Manifest manifest = ManifestReader.read(new StringReader("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                """));
        return new WorkspaceContext(
                tempDir.toFile(),
                tempDir.resolve("workspace.yaml"),
                new WorkspaceGraph(manifest),
                options,
                new TestLog());
    }
}
