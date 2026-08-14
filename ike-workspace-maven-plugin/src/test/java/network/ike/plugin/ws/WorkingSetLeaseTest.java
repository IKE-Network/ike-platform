package network.ike.plugin.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The property that matters about {@link WorkingSetLease} is what it does
 * when the lease protocol does not apply: nothing.
 *
 * <p>This plugin is published and run by people with no Syncthing-paired
 * fleet, and it is run inside temporary directories by this project's own
 * integration tests. In both cases the working-set lease is not a concept,
 * and a release goal must proceed exactly as it did before. A coordination
 * aid that can wedge a build is worse than no coordination aid.
 *
 * <p>These assertions hold on a machine with the lease machinery installed
 * and on one without, which is deliberate — the same test has to pass on a
 * fleet machine and on a CI agent. IKE-Network/ike-issues#1005.
 */
class WorkingSetLeaseTest {

    @Test
    @DisplayName("a directory outside ~/ike-dev is not a working set, so nothing is confirmed")
    void outsideIkeDevIsNotApplicable(@TempDir Path tempDir) {
        WorkingSetLease.Decision decision = WorkingSetLease.confirm(tempDir);

        assertEquals(WorkingSetLease.Verdict.NOT_APPLICABLE, decision.verdict(),
                "a temp directory is not a working set; the goal must proceed");
    }

    @Test
    @DisplayName("a null root is tolerated rather than thrown at")
    void nullRootIsNotApplicable() {
        WorkingSetLease.Decision decision = WorkingSetLease.confirm(null);

        assertEquals(WorkingSetLease.Verdict.NOT_APPLICABLE, decision.verdict());
    }

    @Test
    @DisplayName("a decision always carries a detail string, never null")
    void detailIsNeverNull(@TempDir Path tempDir) {
        assertNotNull(WorkingSetLease.confirm(tempDir).detail(),
                "callers log the detail; a null would fail the goal it is "
                        + "meant to explain");
    }

    @Test
    @DisplayName("the fleet root resolves to a working set, or to nothing at all")
    void ikeDevRootIsNeverFenced() {
        // ~/ike-dev itself is excluded from resolution — it is the folder,
        // not a working set in it — so this must never come back FENCED,
        // whether or not this machine has the lease machinery.
        Path ikeDev = Path.of(System.getProperty("user.home"), "ike-dev");

        WorkingSetLease.Decision decision = WorkingSetLease.confirm(ikeDev);

        assertEquals(WorkingSetLease.Verdict.NOT_APPLICABLE, decision.verdict(),
                "the ike-dev root is not itself a working set");
    }
}
