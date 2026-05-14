package network.ike.plugin.ws.reconcile;

/**
 * One dimension of workspace-state reconciliation under the
 * convergence pattern ({@code ws:scaffold-{draft,publish}}, #393).
 *
 * <p>Each reconciler owns a single conceptual dimension of workspace
 * state (denormalized field sync, parent version, inter-subproject
 * alignment, branch reconciliation, missing-clone detection, etc.)
 * and has two operations: {@link #detect} (read-only, used by
 * {@code scaffold-draft}) and {@link #apply} (mutating, used by
 * {@code scaffold-publish}).
 *
 * <p>The pattern intentionally subsumes what were previously
 * standalone Maven goals: each retired goal's logic becomes one
 * {@code Reconciler} implementation, and {@code scaffold-publish}
 * iterates the {@link ReconcilerRegistry} to apply them in order.
 *
 * @see DriftReport
 * @see WorkspaceContext
 * @see ReconcilerRegistry
 */
public interface Reconciler {

    /**
     * Human-readable name of the dimension this reconciler owns.
     * Used as the heading in {@code scaffold-draft} output.
     *
     * @return the dimension label, e.g. "Denormalized YAML fields"
     */
    String dimension();

    /**
     * The Maven property name (without the {@code -D} prefix) that
     * opts out of this reconciler's apply pass. Setting the property
     * to {@code "false"} skips this dimension on a given
     * {@code scaffold-publish} invocation.
     *
     * @return the opt-out flag name, e.g. {@code "updateFields"}
     */
    String optOutFlag();

    /**
     * The Maven property name (without the {@code -D} prefix) that
     * pins this reconciler to a specific value, overriding the
     * default "move to latest" behavior. Reconcilers that do not
     * support pinning (e.g., pure normalizers like field-sync) return
     * {@code null}.
     *
     * @return the pin flag name, or null if pinning is not supported
     */
    default String pinFlag() {
        return null;
    }

    /**
     * Inspect the workspace and report any drift this reconciler
     * would correct. Read-only — must not mutate the workspace.
     *
     * @param ctx the workspace context
     * @return drift report; {@link DriftReport#noDrift} if nothing to do
     */
    DriftReport detect(WorkspaceContext ctx);

    /**
     * Apply reconciliation. Caller is responsible for checking
     * {@link ReconcilerOptions#isOptedOut} before invoking;
     * implementations may also re-check defensively.
     *
     * @param ctx the workspace context
     */
    void apply(WorkspaceContext ctx);
}
