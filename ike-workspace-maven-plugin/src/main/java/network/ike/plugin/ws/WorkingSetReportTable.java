package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.WorkingSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the shared working-set report table — one row per
 * {@link WorkingSet.Member}, the aggregator included — framing a goal's
 * output as <em>its effect on the working set</em> (#766, under epic #764).
 *
 * <p>Columns are {@code [Member · Kind · Version · Branch · SHA · Effect]}.
 * Because the aggregator (workspace root) is a first-class member, it is
 * always a row, so the staleness a subproject-only table hid — the root left
 * on {@code 1-<feature>-SNAPSHOT} (#763) — is visible. The {@code Effect}
 * column states what the goal did or will do to that member: a
 * <em>planned</em> effect for a {@code -draft} goal, an <em>applied</em>
 * effect for {@code -publish} (e.g. {@code tagged + pushed},
 * {@code version-stripped → 1-SNAPSHOT}, {@code skipped (no-op)}).
 *
 * <p>Content only: this builds Markdown through {@link GoalReportBuilder};
 * {@code WorkspaceReport.write()} still owns the frame.
 */
public final class WorkingSetReportTable {

    /** The fixed column headers, in order. */
    public static final List<String> HEADERS =
            List.of("Member", "Kind", "Version", "Branch", "SHA", "Effect");

    /**
     * SHA cell for the checkpoint manifest's self-pin: the aggregator commit
     * that records the set cannot cite its own not-yet-made SHA.
     */
    public static final String SELF_COMMIT = "n/a — this commit";

    /** Placeholder for an absent or not-applicable cell. */
    public static final String NONE = "—";

    private WorkingSetReportTable() {}

    /**
     * One working-set member's row of report data.
     *
     * @param member  the working-set member — carries its name and
     *                {@link WorkingSet.Member.Kind kind}
     * @param version the member's version, or {@code null}/blank for none
     * @param branch  the member's branch, or {@code null}/blank for none
     * @param sha     the member's short HEAD SHA, {@link #SELF_COMMIT} for the
     *                checkpoint self-pin, or {@code null}/blank for none
     * @param effect  what the goal did or will do to this member
     */
    public record Row(WorkingSet.Member member, String version, String branch,
                      String sha, String effect) {}

    /**
     * Render {@code rows} as the working-set table within a section.
     *
     * @param report  the report builder to append to
     * @param section the section title (e.g. {@code "Working set"})
     * @param rows    one row per member, aggregator included
     * @return {@code report}, for chaining
     */
    public static GoalReportBuilder render(GoalReportBuilder report,
                                           String section, List<Row> rows) {
        List<String[]> tableRows = new ArrayList<>();
        for (Row row : rows) {
            tableRows.add(new String[]{
                    row.member().name(),
                    kindLabel(row.member().kind()),
                    orNone(row.version()),
                    orNone(row.branch()),
                    shaCell(row.sha()),
                    orNone(row.effect())
            });
        }
        return report.section(section).table(HEADERS, tableRows);
    }

    /**
     * The lowercase label for a member kind, as it appears in the Kind column.
     *
     * @param kind the member kind
     * @return {@code "aggregator"} or {@code "subproject"}
     */
    static String kindLabel(WorkingSet.Member.Kind kind) {
        return kind == WorkingSet.Member.Kind.AGGREGATOR
                ? "aggregator" : "subproject";
    }

    private static String shaCell(String sha) {
        if (sha == null || sha.isBlank()) {
            return NONE;
        }
        if (sha.equals(SELF_COMMIT) || sha.equals(NONE)) {
            return sha;
        }
        return "`" + sha + "`";
    }

    private static String orNone(String value) {
        return (value == null || value.isBlank()) ? NONE : value;
    }
}
