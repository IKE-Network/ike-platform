package network.ike.plugin.ws;

/**
 * A goal's declared working-tree contract: how it treats an uncommitted tree
 * on entry ({@link TreePreflight}) and how it commits the changes it authors
 * ({@link AuthoredCommit}). Declared per {@link WsGoal} constant so the policy
 * is compiler-visible and test-enforced rather than decided ad hoc per mojo
 * (IKE-Network/ike-issues#780).
 *
 * <p>The named constants are the taxonomy clusters; a goal that does not fit a
 * cluster constructs its own pair.
 *
 * <p><b>Status:</b> these declarations are the <em>locked target contract</em>
 * from #780. Runtime enforcement is being brought up to them goal-by-goal —
 * some goals (the #780 "laggards": {@code scaffold-publish}, {@code align-publish},
 * {@code stignore}, {@code add}, {@code remove}, …) do not yet honor their
 * declared behavior at runtime. The exhaustiveness test asserts every goal
 * declares a behavior and the structural invariants; the per-goal
 * declared-equals-observed enforcement lands with the migration slice.
 *
 * @param treePreflight  the entry precondition on the working tree
 * @param authoredCommit how the goal commits what it writes
 */
public record GoalBehavior(TreePreflight treePreflight,
                           AuthoredCommit authoredCommit) {

    /**
     * Pure read-only goals: {@code graph}, {@code overview}, {@code report},
     * {@code release-status}, {@code help}, {@code lint}, {@code check-branch},
     * {@code verify-convergence}, and the {@code commit-draft} preview (which
     * must run against uncommitted changes). No preflight, nothing authored.
     */
    public static final GoalBehavior READ_ONLY =
            new GoalBehavior(TreePreflight.IGNORE, AuthoredCommit.NONE);

    /**
     * The {@code *-draft} preview goals: warn on an uncommitted tree but do
     * not block; they write a {@code .gitignore}'d {@code .md} report and
     * author no tracked commit.
     */
    public static final GoalBehavior DRAFT =
            new GoalBehavior(TreePreflight.WARN, AuthoredCommit.NONE);

    /**
     * Coordinating structural mutations ({@code scaffold-publish},
     * {@code align-publish}, {@code stignore}, {@code checkpoint-publish},
     * {@code feature-start*}/{@code feature-finish*}/{@code feature-abandon-publish},
     * {@code post-release}, {@code update-feature-publish}, {@code add},
     * {@code remove}, {@code reconcile-branches-publish}): require an
     * unmodified tree (escapable with {@code -Dallow-uncommitted}) and commit
     * authored changes in isolation.
     */
    public static final GoalBehavior COORDINATING =
            new GoalBehavior(TreePreflight.REQUIRE_UNMODIFIED,
                    AuthoredCommit.IN_ISOLATION);

    /** The commit goal itself ({@code ws:commit-publish}). */
    public static final GoalBehavior COMMIT =
            new GoalBehavior(TreePreflight.IGNORE, AuthoredCommit.IS_THE_COMMIT);

    /**
     * VCS-state sync goals ({@code pull}, {@code sync}, {@code push},
     * {@code refresh-main}, {@code cleanup-publish}, {@code scaffold-init}):
     * uncommitted input is expected — they auto-stash in-flight WIP where
     * needed rather than refuse — and they author no tracked commit.
     */
    public static final GoalBehavior SYNC =
            new GoalBehavior(TreePreflight.IGNORE, AuthoredCommit.NONE);

    /**
     * No clean-tree preflight, but commits its own changes in isolation:
     * {@code release-publish} (honors interrupted-release roll-forward) and
     * {@code switch-publish} (auto-stashes WIP, commits the manifest switch).
     */
    public static final GoalBehavior NO_PREFLIGHT_COMMIT =
            new GoalBehavior(TreePreflight.IGNORE, AuthoredCommit.IN_ISOLATION);
}
