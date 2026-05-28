package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WsScaffoldDraftMojo#buildScaffoldArgs}, the pure
 * arg-builder behind {@code runScaffoldInSubproject}.
 *
 * <p>Covers IKE-Network/ike-issues#449 (the {@code validate} phase
 * runs ahead of the scaffold goal) and IKE-Network/ike-issues#440
 * (the workspace always owns {@code <parent>}, so
 * {@code -Dike.scaffold.skip-parent=true} is forwarded unconditionally
 * — pre-#440 this only fired in {@code publish && applyFoundation},
 * which let a plain {@code ws:scaffold-publish} dry-run print a
 * misleading {@code "<parent> 70 → 35"} downgrade arrow).
 */
class WsScaffoldArgsTest {

    private static final String MVN = "/abs/path/mvnw";
    private static final String DRAFT_GOAL = "ike:scaffold-draft";
    private static final String PUBLISH_GOAL = "ike:scaffold-publish";

    @Test
    void draft_alwaysIncludesValidatePhaseBeforeGoal() {
        // #449: the subprocess must run `validate` before the goal
        // so ike-parent's unpack-scaffold-templates execution
        // populates target/scaffold.
        List<String> args = WsScaffoldDraftMojo.buildScaffoldArgs(
                MVN, DRAFT_GOAL, false, false, false);

        int validateIdx = args.indexOf("validate");
        int goalIdx = args.indexOf(DRAFT_GOAL);
        assertThat(validateIdx)
                .as("validate phase must appear before the scaffold goal")
                .isGreaterThan(0)
                .isLessThan(goalIdx);
    }

    @Test
    void draftMode_alwaysForwardsSkipParent() {
        // #440 + #418: workspace owns <parent>; skip-parent must
        // travel to every per-subproject invocation in every mode.
        List<String> args = WsScaffoldDraftMojo.buildScaffoldArgs(
                MVN, DRAFT_GOAL, false, false, false);

        assertThat(args)
                .as("draft mode must forward skip-parent (#440)")
                .contains("-Dike.scaffold.skip-parent=true");
        assertThat(args)
                .as("draft mode must not request foundation apply")
                .doesNotContain("-Dike.scaffold.apply-foundation=true");
    }

    @Test
    void publishMode_withoutApplyFoundation_stillForwardsSkipParent() {
        // The exact regression in #440: `ws:scaffold-publish` (no
        // -Dapply-foundation) used to drop skip-parent, letting the
        // inner ike:scaffold-publish dry-run compare the live <parent>
        // against the stale baked `foundation:` pin and emit a
        // "downgrade" arrow.
        List<String> args = WsScaffoldDraftMojo.buildScaffoldArgs(
                MVN, PUBLISH_GOAL, true, false, false);

        assertThat(args)
                .as("publish mode without apply-foundation must still skip-parent (#440)")
                .contains("-Dike.scaffold.skip-parent=true");
        assertThat(args)
                .as("apply-foundation must not be set when applyFoundation=false")
                .doesNotContain("-Dike.scaffold.apply-foundation=true");
    }

    @Test
    void publishMode_withApplyFoundation_setsApplyAndSkipParent() {
        List<String> args = WsScaffoldDraftMojo.buildScaffoldArgs(
                MVN, PUBLISH_GOAL, true, true, false);

        assertThat(args)
                .contains("-Dike.scaffold.skip-parent=true")
                .contains("-Dike.scaffold.apply-foundation=true")
                .doesNotContain("-Dike.scaffold.resolve-foundation=true");
    }

    @Test
    void publishMode_withResolveFoundation_addsResolveOnly_whenApplying() {
        // resolve-foundation only travels when apply-foundation is set;
        // otherwise the inner publish wouldn't act on it.
        List<String> withApply = WsScaffoldDraftMojo.buildScaffoldArgs(
                MVN, PUBLISH_GOAL, true, true, true);
        assertThat(withApply)
                .contains("-Dike.scaffold.resolve-foundation=true");

        List<String> withoutApply = WsScaffoldDraftMojo.buildScaffoldArgs(
                MVN, PUBLISH_GOAL, true, false, true);
        assertThat(withoutApply)
                .as("resolve-foundation should not fire without apply-foundation")
                .doesNotContain("-Dike.scaffold.resolve-foundation=true");
    }

    @Test
    void firstArgIsTheMvnCommand() {
        List<String> args = WsScaffoldDraftMojo.buildScaffoldArgs(
                MVN, DRAFT_GOAL, false, false, false);
        assertThat(args.get(0))
                .as("argv[0] must be the resolved mvn wrapper / command")
                .isEqualTo(MVN);
    }
}
