package network.ike.plugin.ws.reconcile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DriftReport#toMarkdown()} — the shared reconciler
 * drift renderer used by goal report files (IKE-Network/ike-issues#407).
 */
class DriftReportTest {

    @Test
    void toMarkdown_noDrift_isACheckmarkLine() {
        String md = DriftReport.noDrift("Parent version").toMarkdown();

        assertThat(md).isEqualTo("- ✓ **Parent version** — no drift\n");
    }

    @Test
    void toMarkdown_withDrift_rendersSummaryDetailAndOptOut() {
        DriftReport report = new DriftReport(
                "Denormalized YAML fields", true,
                "2 field(s) drift from POM truth",
                List.of("alpha: version 1.0 → 1.1"),
                "sync from POM truth on scaffold-publish",
                "mvn ws:scaffold-publish -DupdateFields=false");

        String md = report.toMarkdown();

        assertThat(md).contains("⚠ **Denormalized YAML fields**");
        assertThat(md).contains("2 field(s) drift from POM truth");
        assertThat(md).contains("  - alpha: version 1.0 → 1.1");
        assertThat(md).contains("_Default:_ sync from POM truth");
        assertThat(md).contains("_Opt out:_ `mvn ws:scaffold-publish "
                + "-DupdateFields=false`");
        assertThat(md).doesNotContain("✓");
    }
}
