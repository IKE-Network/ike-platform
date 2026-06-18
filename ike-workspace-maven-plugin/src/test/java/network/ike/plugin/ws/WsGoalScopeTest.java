package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@link WorkspaceScope} classification on {@link WsGoal}
 * (IKE-Network/ike-issues#702): the daily-driver / lifecycle goals run on a
 * single repo, the graph goals require a workspace, and a goal and its
 * {@code -draft}/{@code -publish} sibling never disagree.
 */
class WsGoalScopeTest {

    @Test
    void bareCapableGoals_areMarkedBareAndWorkspace() {
        WsGoal[] bare = {
                WsGoal.COMMIT_DRAFT, WsGoal.COMMIT_PUBLISH, WsGoal.PUSH,
                WsGoal.PULL, WsGoal.SYNC,
                WsGoal.UPDATE_FEATURE_DRAFT, WsGoal.UPDATE_FEATURE_PUBLISH,
                WsGoal.RELEASE_DRAFT, WsGoal.RELEASE_PUBLISH,
                WsGoal.FEATURE_START_PUBLISH,
                WsGoal.FEATURE_FINISH_MERGE_PUBLISH,
                WsGoal.FEATURE_FINISH_SQUASH_PUBLISH,
                WsGoal.FEATURE_ABANDON_PUBLISH,
                WsGoal.SCAFFOLD_DRAFT, WsGoal.SCAFFOLD_PUBLISH,
                WsGoal.SCAFFOLD_INIT, WsGoal.SIBLING_CREATE,
                WsGoal.REPORT, WsGoal.HELP,
        };
        for (WsGoal g : bare) {
            assertThat(g.scope())
                    .as(g.qualified() + " must run on a single repo")
                    .isEqualTo(WorkspaceScope.BARE_AND_WORKSPACE);
            assertThat(g.scope().runsBare()).isTrue();
        }
    }

    @Test
    void graphGoals_requireAWorkspace() {
        WsGoal[] workspaceOnly = {
                WsGoal.ALIGN_DRAFT, WsGoal.ALIGN_PUBLISH, WsGoal.GRAPH,
                WsGoal.OVERVIEW, WsGoal.VERIFY_CONVERGENCE,
                WsGoal.RECONCILE_BRANCHES_PUBLISH, WsGoal.SWITCH_PUBLISH,
                WsGoal.REFRESH_MAIN, WsGoal.CHECKPOINT_PUBLISH,
                WsGoal.ADD, WsGoal.REMOVE, WsGoal.STIGNORE, WsGoal.LINT,
                // No-op in a single repo (no manifest to check), so the
                // goal's purpose still requires a workspace.
                WsGoal.CHECK_BRANCH,
        };
        for (WsGoal g : workspaceOnly) {
            assertThat(g.scope())
                    .as(g.qualified() + " requires a workspace")
                    .isEqualTo(WorkspaceScope.WORKSPACE_ONLY);
            assertThat(g.scope().runsBare()).isFalse();
        }
    }

    @Test
    void everyGoalHasAScope() {
        for (WsGoal g : WsGoal.values()) {
            assertThat(g.scope())
                    .as(g.qualified() + " must declare a scope")
                    .isNotNull();
        }
    }

    @Test
    void draftAndPublishSiblingsShareScope() {
        for (WsGoal draft : WsGoal.values()) {
            if (!draft.goalName().endsWith("-draft")) {
                continue;
            }
            String publishName = draft.goalName()
                    .substring(0, draft.goalName().length() - "-draft".length())
                    + "-publish";
            WsGoal publish = Arrays.stream(WsGoal.values())
                    .filter(g -> g.goalName().equals(publishName))
                    .findFirst()
                    .orElse(null);
            if (publish == null) {
                continue; // e.g. a draft with no publish sibling
            }
            assertThat(publish.scope())
                    .as(draft.qualified() + " and " + publish.qualified()
                            + " must share a scope")
                    .isEqualTo(draft.scope());
        }
    }
}
