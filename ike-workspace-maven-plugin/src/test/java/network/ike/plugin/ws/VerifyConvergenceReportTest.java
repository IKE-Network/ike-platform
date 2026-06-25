package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report tests for {@code ws:verify-convergence}.
 *
 * <p>Convergence verification is by-nature subproject-scoped: it
 * compares resolved dependency versions <em>across subprojects</em>,
 * and the aggregator (workspace root) is not a dependency-convergence
 * node — it carries no inter-subproject coordinate to converge.
 * Unlike the mutating working-set reports migrated under epic
 * #764/#767, this report adds no aggregator row. Per #767, that
 * omission must be stated explicitly rather than left silent, so the
 * summary carries a "subprojects only" scope note.
 */
class VerifyConvergenceReportTest {

    @Test
    void summary_statesSubprojectsOnlyScopeNote() {
        // A clean run (no skew, no contamination, no divergence) still
        // carries the by-nature subproject-scope note.
        String markdown = VerifyConvergenceMojo.buildSummary(
                "example-ws", 3,
                false, false, List.of(), false);

        assertThat(markdown)
                .as("the by-nature subproject scope must be stated, "
                        + "never silently omitting the aggregator")
                .contains("subprojects only")
                .contains("dependency-convergence node");
    }

    @Test
    void summary_withDivergences_stillStatesScopeNote() {
        // The note is unconditional — present whether the run passes
        // or fails — so it is never lost behind a divergence finding.
        String markdown = VerifyConvergenceMojo.buildSummary(
                "example-ws", 4,
                true, false, List.of(), true);

        assertThat(markdown)
                .as("scope note must survive a failing run")
                .contains("subprojects only")
                .contains("FAIL");
    }
}
