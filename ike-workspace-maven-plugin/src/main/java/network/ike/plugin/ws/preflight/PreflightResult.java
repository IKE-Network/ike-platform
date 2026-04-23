package network.ike.plugin.ws.preflight;

import network.ike.plugin.ws.WsGoal;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.util.List;

/**
 * Outcome of a preflight run. Collects any per-condition failures and
 * lets the caller decide how to react (hard-fail for publish mojos,
 * warn-only for their draft siblings).
 *
 * <p>See {@link Preflight#of(List, PreflightContext)}.
 */
public record PreflightResult(List<Failure> failures) {

    /** A single failing condition with the remediation it emitted. */
    public record Failure(PreflightCondition condition, String remediation) {}

    /** True if every condition passed. */
    public boolean passed() {
        return failures.isEmpty();
    }

    /**
     * Hard-fail contract for publish mojos. Throws {@link MojoException}
     * with an aggregated, user-facing message if any condition failed.
     *
     * @param goal the publish goal running the preflight —
     *                    used in the error header so the user sees
     *                    which goal is refusing to proceed
     * @throws MojoException if any condition failed
     */
    public void requirePassed(WsGoal goal) throws MojoException {
        if (passed()) return;
        var sb = new StringBuilder();
        sb.append(goal.qualified()).append(" preflight failed:\n\n");
        for (Failure f : failures) {
            sb.append("[").append(f.condition().description()).append("]\n");
            sb.append(f.remediation()).append("\n\n");
        }
        throw new MojoException(sb.toString().stripTrailing());
    }

    /**
     * Soft-fail contract for draft mojos. Emits a warning per failing
     * condition but does not throw, so the user can still preview the
     * operation's plan.
     *
     * @param log         Maven logger
     * @param goal the publish goal whose preflight this would
     *                    block — named in the warning so the user
     *                    knows what would fail
     */
    public void warnIfFailed(Log log, WsGoal goal) {
        if (passed()) return;
        log.warn("");
        log.warn("  ⚠ " + goal.qualified()
                + " preflight would fail:");
        for (Failure f : failures) {
            log.warn("");
            log.warn("  [" + f.condition().description() + "]");
            for (String line : f.remediation().split("\n")) {
                log.warn("  " + line);
            }
        }
        log.warn("");
    }
}
