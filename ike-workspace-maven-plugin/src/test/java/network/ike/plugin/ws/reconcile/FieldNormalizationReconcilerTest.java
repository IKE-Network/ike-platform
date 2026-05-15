package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.TestLog;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the {@code #387} safety net wired into
 * {@link FieldNormalizationReconciler} by IKE-Network/ike-issues#399:
 * {@code apply()} must collapse pre-existing duplicate subproject
 * field keys even when no {@code version}/{@code groupId} field has
 * drifted from POM truth.
 */
class FieldNormalizationReconcilerTest {

    /**
     * A manifest with two consecutive {@code sha:} lines per
     * subproject block — the shape produced by the pre-#387
     * duplicate-key bug. No subproject is cloned (no {@code pom.xml}
     * on disk), so the version/groupId drift pass is a no-op and only
     * the collapse path can change the file.
     */
    private static final String DUPLICATED = """
            schema-version: "1.0"
            defaults:
              branch: main
            subprojects:
              alpha:
                repo: https://example.com/alpha.git
                version: "1.0.0"
                groupId: network.ike.test
                sha: aaaaaaaa
                sha: bbbbbbbb
              beta:
                repo: https://example.com/beta.git
                version: "2.0.0"
                groupId: network.ike.test
                sha: cccccccc
                sha: dddddddd
            """;

    @Test
    void apply_collapses_duplicate_keys_with_no_field_drift(
            @TempDir Path tmp) throws IOException {
        Path manifest = tmp.resolve("workspace.yaml");
        Files.writeString(manifest, DUPLICATED, StandardCharsets.UTF_8);

        WorkspaceContext ctx = new WorkspaceContext(
                tmp.toFile(),
                manifest,
                new WorkspaceGraph(ManifestReader.read(manifest)),
                ReconcilerOptions.empty(),
                new TestLog());

        new FieldNormalizationReconciler().apply(ctx);

        String result = Files.readString(manifest, StandardCharsets.UTF_8);
        long shaLines = result.lines()
                .filter(l -> l.startsWith("    sha:"))
                .count();
        assertThat(shaLines)
                .as("one sha: line per subproject after collapse")
                .isEqualTo(2);
        // YAML last-wins — the kept value is the final occurrence.
        assertThat(result).contains("    sha: bbbbbbbb");
        assertThat(result).contains("    sha: dddddddd");
        assertThat(result).doesNotContain("aaaaaaaa");
        assertThat(result).doesNotContain("cccccccc");
    }

    @Test
    void detect_reports_duplicate_keys(@TempDir Path tmp)
            throws IOException {
        Path manifest = tmp.resolve("workspace.yaml");
        Files.writeString(manifest, DUPLICATED, StandardCharsets.UTF_8);

        WorkspaceContext ctx = new WorkspaceContext(
                tmp.toFile(),
                manifest,
                new WorkspaceGraph(ManifestReader.read(manifest)),
                ReconcilerOptions.empty(),
                new TestLog());

        DriftReport report = new FieldNormalizationReconciler().detect(ctx);

        assertThat(report.hasDrift()).isTrue();
        assertThat(report.detailLines())
                .anyMatch(d -> d.contains("duplicate subproject field"));
    }

    @Test
    void apply_is_a_noop_on_a_clean_manifest(@TempDir Path tmp)
            throws IOException {
        String clean = """
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  alpha:
                    repo: https://example.com/alpha.git
                    version: "1.0.0"
                    groupId: network.ike.test
                    sha: bbbbbbbb
                """;
        Path manifest = tmp.resolve("workspace.yaml");
        Files.writeString(manifest, clean, StandardCharsets.UTF_8);

        WorkspaceContext ctx = new WorkspaceContext(
                tmp.toFile(),
                manifest,
                new WorkspaceGraph(ManifestReader.read(manifest)),
                ReconcilerOptions.empty(),
                new TestLog());

        new FieldNormalizationReconciler().apply(ctx);

        assertThat(Files.readString(manifest, StandardCharsets.UTF_8))
                .isEqualTo(clean);
    }
}
