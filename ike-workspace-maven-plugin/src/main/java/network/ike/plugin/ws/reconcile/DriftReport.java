package network.ike.plugin.ws.reconcile;

import java.util.List;

/**
 * Report from a {@link Reconciler#detect} call describing what (if
 * anything) the reconciler would change on {@code scaffold-publish}.
 *
 * <p>The {@code optOutCommand} is rendered inline beside the
 * dimension in {@code scaffold-draft} output so users can copy-paste
 * to opt out without first looking up the flag name elsewhere.
 *
 * @param dimension      human-readable dimension name
 * @param hasDrift       true if reconciliation would change state
 * @param summary        one-line drift summary (empty when no drift)
 * @param detailLines    additional context lines (empty list ok)
 * @param defaultAction  one-line description of what apply would do
 * @param optOutCommand  exact copy-paste command to skip this dimension
 */
public record DriftReport(
        String dimension,
        boolean hasDrift,
        String summary,
        List<String> detailLines,
        String defaultAction,
        String optOutCommand) {

    /**
     * Convenience constructor for the "no drift" case.
     *
     * @param dimension the dimension name
     * @return a report with {@code hasDrift = false}
     */
    public static DriftReport noDrift(String dimension) {
        return new DriftReport(dimension, false, "", List.of(), "", "");
    }

    /**
     * Render this report as a Markdown fragment for a goal report
     * file (IKE-Network/ike-issues#407).
     *
     * <p>Shared renderer so reconciler drift looks the same in every
     * report that includes it: a checkmark line when there is no
     * drift, otherwise the dimension, summary, detail lines, default
     * action, and the copy-paste opt-out command.
     *
     * @return a Markdown fragment, newline-terminated
     */
    public String toMarkdown() {
        if (!hasDrift) {
            return "- ✓ **" + dimension + "** — no drift\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- ⚠ **").append(dimension).append("**");
        if (!summary.isEmpty()) {
            sb.append(" — ").append(summary);
        }
        sb.append("\n");
        for (String line : detailLines) {
            sb.append("  - ").append(line).append("\n");
        }
        if (!defaultAction.isEmpty()) {
            sb.append("  - _Default:_ ").append(defaultAction).append("\n");
        }
        if (!optOutCommand.isEmpty()) {
            sb.append("  - _Opt out:_ `").append(optOutCommand).append("`\n");
        }
        return sb.toString();
    }

    /**
     * Render this report as a Markdown fragment describing what a
     * {@code scaffold-publish} reconciler <em>applied</em> — the
     * past-tense counterpart of {@link #toMarkdown}
     * (IKE-Network/ike-issues#431).
     *
     * <p>A reconciler's {@link Reconciler#apply} does exactly what its
     * {@link Reconciler#detect} reported when the two are called back
     * to back on an unchanged workspace, so the same {@code detailLines}
     * (e.g. {@code doc-example: ike-parent:66 → 67}) describe the
     * applied changes. The drift {@code summary}, {@code defaultAction},
     * and {@code optOutCommand} are future-tense and omitted here.
     *
     * @return a Markdown fragment, newline-terminated
     */
    public String toAppliedMarkdown() {
        if (!hasDrift) {
            return "- ✓ **" + dimension
                    + "** — already current, nothing to apply\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- ✓ **").append(dimension).append("** — applied ")
                .append(detailLines.size()).append(" change(s)\n");
        for (String line : detailLines) {
            sb.append("  - ").append(line).append("\n");
        }
        return sb.toString();
    }
}
