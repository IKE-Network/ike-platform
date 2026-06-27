package network.ike.plugin.ws;

/**
 * How a {@code ws:}/{@code ike:} goal treats the git working tree's
 * uncommitted state as a precondition — one half of a goal's declared
 * {@link GoalBehavior}. See IKE-Network/ike-issues#780 for the taxonomy and
 * the rationale for rejecting a blanket "every goal requires an unmodified
 * tree" rule.
 */
public enum TreePreflight {

    /**
     * The goal refuses to run unless the working tree is unmodified, so its
     * diff is attributable solely to the goal and is reviewable/recoverable.
     * The {@code -Dallow-uncommitted} flag overrides this for the documented
     * roll-forward and fold-into-feature cases.
     */
    REQUIRE_UNMODIFIED,

    /**
     * The goal evaluates the working-tree-clean condition and logs it, but
     * does not block — the read-only {@code *-draft} preview pattern
     * ({@code warnIfFailed}).
     */
    WARN,

    /**
     * Uncommitted changes are expected input or irrelevant; the goal does not
     * gate on them. Covers {@code ws:commit} (consumes them), the sync goals
     * (which auto-stash in-flight Syncthing WIP rather than refuse), the
     * roll-forward release path, and the read-only goals.
     */
    IGNORE
}
