package network.ike.plugin.ws.preflight;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a sequence of {@link PreflightCondition}s against a shared
 * {@link PreflightContext} and collects the failures into a
 * {@link PreflightResult}. A single entry point both draft and publish
 * siblings share, so the two variants cannot diverge on which
 * conditions get checked.
 *
 * <p>Example:
 * <pre>{@code
 * PreflightResult result = Preflight.of(
 *     List.of(PreflightCondition.WORKING_TREE_CLEAN),
 *     PreflightContext.of(root, graph, sorted));
 *
 * if (publish) result.requirePassed(WsGoal.ALIGN_PUBLISH);
 * else         result.warnIfFailed(log, WsGoal.ALIGN_PUBLISH);
 * }</pre>
 */
public final class Preflight {

    private Preflight() {}

    /**
     * Evaluate each condition in order against the given context.
     * Evaluation is not short-circuiting — every condition runs so the
     * user sees every failing precondition in a single run.
     *
     * @param conditions the conditions to evaluate
     * @param ctx        the shared context for the run
     * @return the collected outcome
     */
    public static PreflightResult of(List<PreflightCondition> conditions,
                                      PreflightContext ctx) {
        List<PreflightResult.Failure> failures = new ArrayList<>();
        for (PreflightCondition cond : conditions) {
            cond.check(ctx).ifPresent(msg ->
                    failures.add(new PreflightResult.Failure(cond, msg)));
        }
        return new PreflightResult(failures);
    }
}
