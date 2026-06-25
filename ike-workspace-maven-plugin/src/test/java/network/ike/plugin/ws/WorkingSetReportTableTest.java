package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.WorkingSet;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WorkingSetReportTable} (#766): one row per working-set
 * member, the aggregator always present, with kind labels, SHA formatting,
 * and the checkpoint self-pin sentinel.
 */
class WorkingSetReportTableTest {

    @Test
    void render_includesAggregatorRow_labelsKinds_andFormatsSha() {
        WorkingSet.Member sub =
                WorkingSet.Member.subproject("lib-a", Path.of("lib-a"));
        WorkingSet.Member agg =
                WorkingSet.Member.aggregator("ike-komet-wsr", Path.of("."));
        List<WorkingSetReportTable.Row> rows = List.of(
                new WorkingSetReportTable.Row(
                        sub, "1.0.0-SNAPSHOT", "main", "abc1234", "committed"),
                new WorkingSetReportTable.Row(
                        agg, "1-SNAPSHOT", "main",
                        WorkingSetReportTable.SELF_COMMIT, "tagged + pushed"));

        String md = WorkingSetReportTable.render(
                new GoalReportBuilder(), "Working set", rows).build();

        assertThat(md).contains("Member").contains("Kind").contains("Effect");
        // The aggregator is a row, labeled — the staleness #763 hid is visible.
        assertThat(md).contains("ike-komet-wsr").contains("aggregator");
        assertThat(md).contains("lib-a").contains("subproject");
        // A real short SHA is backticked; the self-pin sentinel is not.
        assertThat(md).contains("`abc1234`");
        assertThat(md).contains(WorkingSetReportTable.SELF_COMMIT);
        assertThat(md).doesNotContain("`" + WorkingSetReportTable.SELF_COMMIT + "`");
        assertThat(md).contains("tagged + pushed");
    }

    @Test
    void render_readOnlyGoalNamesFinalColumnStatus() {
        WorkingSet.Member agg =
                WorkingSet.Member.aggregator("ike-komet-wsr", Path.of("."));
        List<WorkingSetReportTable.Row> rows = List.of(
                new WorkingSetReportTable.Row(
                        agg, "1-view-options-popup-SNAPSHOT", "main", "def5678",
                        "clean"));

        String md = WorkingSetReportTable.render(
                new GoalReportBuilder(), "Status", "Status", rows).build();

        // Read-only goals label the final column Status, not Effect.
        assertThat(md).contains("Status").doesNotContain("Effect");
        // The aggregator's stale version is now visible (the #763 case).
        assertThat(md).contains("1-view-options-popup-SNAPSHOT");
        assertThat(md).contains("aggregator");
    }

    @Test
    void render_blankCellsBecomeDash() {
        WorkingSet.Member sub =
                WorkingSet.Member.subproject("lib-b", Path.of("lib-b"));
        List<WorkingSetReportTable.Row> rows = List.of(
                new WorkingSetReportTable.Row(
                        sub, "", null, "", "skipped (no-op)"));

        String md = WorkingSetReportTable.render(
                new GoalReportBuilder(), "Working set", rows).build();

        assertThat(md).contains(WorkingSetReportTable.NONE);
        assertThat(md).contains("skipped (no-op)");
    }
}
