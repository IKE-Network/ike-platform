package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for pure functions in {@link WsCheckpointDraftMojo}: YAML generation,
 * tag name derivation, file naming, and status formatting.
 */
class CheckpointSupportTest {

    @Test
    void buildCheckpointYaml_header_containsMetadata() {
        String yaml = WsCheckpointDraftMojo.buildCheckpointYaml(
                "sprint-42", "2026-03-20T10:00:00Z", "kec", "1.0",
                List.of(), List.of());

        assertThat(yaml)
                .contains("name: \"sprint-42\"")
                .contains("created: \"2026-03-20T10:00:00Z\"")
                .contains("author: \"kec\"")
                .contains("schema-version: \"1.0\"")
                .contains("subprojects:");
    }

    @Test
    void buildCheckpointYaml_singleSubproject() {
        SubprojectSnapshot snap = new SubprojectSnapshot(
                "ike-platform", "abc123def456", "abc123d",
                "main", "20-SNAPSHOT", false);

        String yaml = WsCheckpointDraftMojo.buildCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(snap), List.of());

        assertThat(yaml)
                .contains("    ike-platform:")
                .contains("      sha: \"abc123def456\"")
                .contains("      short-sha: \"abc123d\"")
                .contains("      branch: \"main\"")
                .contains("      version: \"20-SNAPSHOT\"")
                .doesNotContain("dirty: true")
                .doesNotContain("type:");
    }

    @Test
    void buildCheckpointYaml_dirtyFlagDoesNotAppearInOutput() {
        // Checkpoints require a clean worktree; the dirty field in
        // SubprojectSnapshot is informational only and is not written to YAML.
        SubprojectSnapshot snap = new SubprojectSnapshot(
                "ike-docs", "aaa", "aaa",
                "feature/docs", "1.0-SNAPSHOT", true);

        String yaml = WsCheckpointDraftMojo.buildCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(snap), List.of());

        assertThat(yaml)
                .contains("    ike-docs:")
                .contains("      sha: \"aaa\"")
                .doesNotContain("dirty:");
    }

    @Test
    void buildCheckpointYaml_absentSubproject_markedAbsent() {
        String yaml = WsCheckpointDraftMojo.buildCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(), List.of("missing-repo"));

        assertThat(yaml)
                .contains("    missing-repo:")
                .contains("      status: absent");
    }

    @Test
    void buildCheckpointYaml_nullVersion_omitted() {
        SubprojectSnapshot snap = new SubprojectSnapshot(
                "no-pom", "ccc", "ccc",
                "main", null, false);

        String yaml = WsCheckpointDraftMojo.buildCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(snap), List.of());

        // The subproject section should not contain a version line
        // (schema-version in the header is separate)
        String subprojectSection = yaml.substring(yaml.indexOf("    no-pom:"));
        assertThat(subprojectSection)
                .doesNotContain("version:");
    }

    @Test
    void buildCheckpointYaml_emptySubprojects_minimalOutput() {
        String yaml = WsCheckpointDraftMojo.buildCheckpointYaml(
                "empty", "2026-01-01T00:00:00Z", "ci", "1.0",
                List.of(), List.of());

        assertThat(yaml)
                .contains("checkpoint:")
                .contains("  subprojects:")
                .endsWith("  subprojects:\n");
    }

    @Test
    void buildCheckpointYaml_multipleSubprojects_allPresent() {
        List<SubprojectSnapshot> snaps = List.of(
                new SubprojectSnapshot("alpha", "a1", "a1", "main", "1.0", false),
                new SubprojectSnapshot("beta", "b2", "b2", "main", "2.0", false));

        String yaml = WsCheckpointDraftMojo.buildCheckpointYaml(
                "multi", "2026-01-01T00:00:00Z", "ci", "1.0",
                snaps, List.of());

        assertThat(yaml)
                .contains("    alpha:")
                .contains("    beta:");
    }

    // ── checkpointFileName ──────────────────────────────────────────

    @Test
    void checkpointFileName_standardFormat() {
        assertThat(WsCheckpointDraftMojo.checkpointFileName("sprint-42"))
                .isEqualTo("checkpoint-sprint-42.yaml");
    }

    @Test
    void checkpointFileName_withTimestamp() {
        assertThat(WsCheckpointDraftMojo.checkpointFileName("pre-release-20260320-100000"))
                .isEqualTo("checkpoint-pre-release-20260320-100000.yaml");
    }

}
