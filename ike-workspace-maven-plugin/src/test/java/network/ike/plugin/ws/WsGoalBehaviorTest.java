package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural invariants on each {@link WsGoal}'s declared {@link GoalBehavior}
 * (IKE-Network/ike-issues#780). The compiler already forces every goal to
 * declare a behavior (the {@link WsGoal} constructor requires it); these guards
 * catch a declaration that compiles but contradicts the taxonomy.
 *
 * <p>The stronger declared-equals-observed check — each goal's runtime
 * preflight/commit matching its declaration — lands with the migration slice
 * that wires the contract into the mojos.
 */
class WsGoalBehaviorTest {

    @Test
    void every_goal_declares_a_non_null_behavior() {
        for (WsGoal goal : WsGoal.values()) {
            assertThat(goal.behavior())
                    .withFailMessage("WsGoal.%s has no GoalBehavior", goal.name())
                    .isNotNull();
            assertThat(goal.behavior().treePreflight())
                    .withFailMessage("WsGoal.%s has no TreePreflight", goal.name())
                    .isNotNull();
            assertThat(goal.behavior().authoredCommit())
                    .withFailMessage("WsGoal.%s has no AuthoredCommit", goal.name())
                    .isNotNull();
        }
    }

    @Test
    void commit_publish_is_the_only_commit_goal() {
        for (WsGoal goal : WsGoal.values()) {
            boolean isCommit = goal.behavior().authoredCommit()
                    == AuthoredCommit.IS_THE_COMMIT;
            if (goal == WsGoal.COMMIT_PUBLISH) {
                assertThat(isCommit)
                        .withFailMessage("commit-publish must be IS_THE_COMMIT")
                        .isTrue();
            } else {
                assertThat(isCommit)
                        .withFailMessage("WsGoal.%s must not be IS_THE_COMMIT —"
                                + " only commit-publish is the commit", goal.name())
                        .isFalse();
            }
        }
    }

    @Test
    void draft_goals_never_author_a_commit_or_refuse() {
        for (WsGoal goal : WsGoal.values()) {
            if (!goal.goalName().endsWith("-draft")) {
                continue;
            }
            assertThat(goal.behavior().authoredCommit())
                    .withFailMessage("draft goal %s must author no commit",
                            goal.name())
                    .isEqualTo(AuthoredCommit.NONE);
            assertThat(goal.behavior().treePreflight())
                    .withFailMessage("draft goal %s must not REQUIRE_UNMODIFIED"
                            + " — a preview never refuses", goal.name())
                    .isNotEqualTo(TreePreflight.REQUIRE_UNMODIFIED);
        }
    }

    @Test
    void requiring_an_unmodified_tree_implies_committing_in_isolation() {
        // The coordinating cluster: a goal refuses unless the tree is
        // unmodified to keep its own diff attributable — so it must also
        // commit that diff in isolation rather than strand it for the next
        // goal's preflight (the #774 failure mode).
        for (WsGoal goal : WsGoal.values()) {
            if (goal.behavior().treePreflight() == TreePreflight.REQUIRE_UNMODIFIED) {
                assertThat(goal.behavior().authoredCommit())
                        .withFailMessage("WsGoal.%s REQUIRE_UNMODIFIED but does"
                                + " not commit in isolation", goal.name())
                        .isEqualTo(AuthoredCommit.IN_ISOLATION);
            }
        }
    }

    @Test
    void the_commit_goal_cannot_require_an_unmodified_tree() {
        for (WsGoal goal : WsGoal.values()) {
            if (goal.behavior().authoredCommit() == AuthoredCommit.IS_THE_COMMIT) {
                assertThat(goal.behavior().treePreflight())
                        .withFailMessage("WsGoal.%s is the commit but requires an"
                                + " unmodified tree — impossible", goal.name())
                        .isEqualTo(TreePreflight.IGNORE);
            }
        }
    }
}
