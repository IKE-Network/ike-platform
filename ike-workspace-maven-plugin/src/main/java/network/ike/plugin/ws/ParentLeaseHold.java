package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The short-hold of the <em>parent</em> working set's lease during a
 * sibling finish — the last open thread of the local-origin model
 * (IKE-Network/ike-issues#992, settled scope of #1002, delivered as
 * #1005's {@code ws:} half).
 *
 * <p>A sibling finish lands in the parent: the absorb fast-forwards the
 * parent's refs, which makes the finish a <em>writer of the parent</em>,
 * and single-writer is per working set. So the finish confirms the
 * parent's lease before landing — free or expired is taken silently,
 * already-mine simply confirms, and live on another machine refuses with
 * the recall left to the human, the one rule every surface shares.
 *
 * <p>"Short" means the hold gives back exactly what it took: a lease this
 * machine already held stays held; one taken fresh for the landing is
 * returned when the hold closes, success or failure, so a finished (or
 * failed) sibling never leaves its parent pinned to this machine.
 * Inert wherever the lease machinery is ({@link WorkingSetLease}).
 */
final class ParentLeaseHold implements AutoCloseable {

    private final Path parent;
    private final boolean takenFresh;
    private final Log log;

    private ParentLeaseHold(Path parent, boolean takenFresh, Log log) {
        this.parent = parent;
        this.takenFresh = takenFresh;
        this.log = log;
    }

    /**
     * Confirms the parent's lease for a finish that is about to land in
     * it.
     *
     * @param workingSetRoot the sibling working set the finish runs in
     * @param log            the goal's log
     * @return the hold; inert when the working set has no local parent or
     *         the lease machinery is absent
     * @throws MojoException if another machine holds the parent live —
     *                       landing there would collide with its writer
     */
    static ParentLeaseHold take(File workingSetRoot, Log log)
            throws MojoException {
        Optional<File> parent = SiblingFinish.localParent(workingSetRoot);
        if (parent.isEmpty()) {
            return new ParentLeaseHold(null, false, log);
        }
        Path parentPath = parent.get().toPath();
        boolean alreadyMine = WorkingSetLease.status(parentPath)
                .map(status -> status.line().contains(": MINE"))
                .orElse(false);
        WorkingSetLease.Decision decision =
                WorkingSetLease.confirm(parentPath);
        return switch (decision.verdict()) {
            case NOT_APPLICABLE -> new ParentLeaseHold(null, false, log);
            case HELD -> {
                log.info("  Parent lease: confirmed for " + decision.detail()
                        + (alreadyMine ? "" : " (short-hold — returned after"
                                + " the landing)"));
                yield new ParentLeaseHold(parentPath, !alreadyMine, log);
            }
            case FENCED -> throw new MojoException(
                    "The finish lands in the parent working set, and another "
                            + "machine holds it live; landing here would "
                            + "collide with its writer.\n" + decision.detail());
        };
    }

    /**
     * Gives back what the hold took: returns the parent's lease when it
     * was taken fresh for this landing, and only then. Best-effort — a
     * failed return is logged, never thrown; the record ages out.
     */
    @Override
    public void close() {
        if (parent == null || !takenFresh) {
            return;
        }
        if (WorkingSetLease.returnLease(parent)) {
            log.info("  Parent lease: short-hold returned");
        } else {
            log.warn("  Parent lease: short-hold return failed; the record "
                    + "will age out at its ttl");
        }
    }
}
