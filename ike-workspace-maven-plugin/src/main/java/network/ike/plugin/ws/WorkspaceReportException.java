package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;

/**
 * A goal failure that still carries a report to persist
 * (IKE-Network/ike-issues#935).
 *
 * <p>The default {@code ws:*} contract is "a failed goal produces no
 * report": {@link AbstractWorkspaceMojo#runGoal()} throws a plain
 * {@link MojoException} and the {@code final}
 * {@link AbstractWorkspaceMojo#execute()} skips the report write. That is
 * correct for a goal that fails before producing anything worth recording.
 *
 * <p>Some goals, though, do real work and gather real findings before a
 * downstream step fails — {@code ws:scaffold-draft} / {@code -publish}
 * walk every subproject, accumulate per-subproject results and any newly
 * surfaced sections (e.g. a #417 foundation-upgrade notice), then fail if
 * any subproject failed. Throwing a plain {@link MojoException} there left
 * the <em>previous</em> run's {@code ws꞉scaffold-*.md} on disk, so a stale
 * report silently masqueraded as the current one, with no staleness marker
 * (#935).
 *
 * <p>A goal in that situation throws this exception instead. {@code execute()}
 * writes the carried report — stamped by {@link WorkspaceReport} with the
 * current run's timestamp, and by convention prefixed with a failure banner —
 * before propagating the failure, so the on-disk report always reflects the
 * run that just happened.
 */
public final class WorkspaceReportException extends MojoException {

    /** The report to persist before the failure propagates. */
    private final transient WorkspaceReportSpec report;

    /**
     * Fail the goal, but persist {@code report} first.
     *
     * @param message the failure message Maven surfaces
     * @param report  the report to write before the build fails
     */
    public WorkspaceReportException(String message, WorkspaceReportSpec report) {
        super(message);
        this.report = report;
    }

    /**
     * The report {@link AbstractWorkspaceMojo#execute()} writes before it
     * re-throws this failure.
     *
     * @return the report to persist
     */
    public WorkspaceReportSpec report() {
        return report;
    }
}
