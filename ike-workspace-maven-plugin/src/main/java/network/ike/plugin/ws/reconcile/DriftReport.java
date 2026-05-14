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
}
