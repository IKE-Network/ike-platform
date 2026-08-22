package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.WorkingSet;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Remove a sibling working set, safely (IKE-Network/ike-issues#600).
 *
 * <p>Three gates, in order:
 * <ol>
 *   <li><b>The sibling's lease.</b> Removal is the last write a working
 *       set ever sees, so this machine confirms it holds the sibling's
 *       lease first ({@link WorkingSetLease}): free or expired acquires
 *       silently, live on another machine refuses — and {@code -Dforce}
 *       does <em>not</em> override that, because displacing a live holder
 *       is a human decision on every surface. Inert on machines without
 *       the lease protocol.</li>
 *   <li><b>The content preflight.</b> Uncommitted changes, stashes,
 *       unlanded branches (squash-aware), bare member trees — anything
 *       that dies with the tree refuses unless {@code -Dforce=true}
 *       discards it deliberately, with the findings persisted in the
 *       report either way.</li>
 *   <li><b>The deletion.</b> The whole sibling directory — whole-working-set
 *       removal is the supported operation; the sync layer propagates it
 *       and staggered file versioning is the net — followed by the lease
 *       record it leaves behind ({@code leases/<name>.lease}), the
 *       reconciliation daemon's GC action done eagerly
 *       (IKE-Network/ike-issues#1006).</li>
 * </ol>
 *
 * <pre>{@code
 * mvn ws:sibling-remove-draft   -Dfeature=jira-456
 * mvn ws:sibling-remove-publish -Dfeature=jira-456
 * mvn ws:sibling-remove-publish -Dfeature=jira-456 -Dforce=true
 * }</pre>
 *
 * @see SiblingRemoveDraftMojo for the read-only preview
 */
@Mojo(name = "sibling-remove-publish", projectRequired = false, aggregator = true)
public class SiblingRemovePublishMojo extends AbstractWorkspaceMojo {

    /** Feature whose sibling ({@code <primary>꞉<feature>}) to remove. */
    @Parameter(property = "feature")
    String feature;

    /** Explicit sibling directory name; alternative to {@code -Dfeature}. */
    @Parameter(property = "name")
    String name;

    /**
     * Discard uncommitted work, stashes, unlanded branches and bare trees
     * deliberately. Never overrides a lease held live on another machine.
     */
    @Parameter(property = "force", defaultValue = "false")
    boolean force;

    /** Creates this goal instance. */
    public SiblingRemovePublishMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        WorkingSet workingSet = resolveWorkingSet();
        File primaryRoot = isWorkspaceMode()
                ? workspaceRoot()
                : workingSet.members().getFirst().directory().toFile();
        SiblingRemoval.Target target = SiblingRemoval.resolve(
                primaryRoot, workingSet.baseName(), feature, name);

        getLog().info("");
        getLog().info(header("Sibling Remove"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Sibling: " + target.sibling().root().getAbsolutePath());
        getLog().info("");

        // Gate 1 — the sibling's lease. Removal is a write; force never
        // reaches past a live holder on another machine.
        WorkingSetLease.Decision decision = WorkingSetLease.confirm(
                target.sibling().root().toPath());
        switch (decision.verdict()) {
            case NOT_APPLICABLE -> { }
            case HELD -> getLog().info("  Working-set lease: confirmed for "
                    + decision.detail());
            case FENCED -> throw new MojoException("Another machine holds '"
                    + target.sibling().name() + "' live; removing it from "
                    + "here would delete a working set someone is in. Have "
                    + "that machine release it (close the project / "
                    + "lease.sh release), then re-run.\n" + decision.detail());
        }

        // Gate 2 — the content preflight, persisted whichever way it goes.
        List<String[]> preflightRows = SiblingRemoval.preflightRows(target);
        boolean clean = SiblingRemoval.clean(target);
        GoalReportBuilder report = new GoalReportBuilder();
        SiblingRemoval.describeTarget(report, target);
        report.section("Preflight")
                .table(List.of("", "Member", "Finding"), preflightRows);
        if (!clean && !force) {
            report.paragraph("**Refused.** The ✗ rows above would be lost. "
                    + "Finish or commit them, or pass `-Dforce=true` to "
                    + "discard them deliberately.");
            throw new WorkspaceReportException("Sibling '"
                    + target.sibling().name() + "' holds work that dies "
                    + "with the tree (see the "
                    + WsGoal.SIBLING_REMOVE_PUBLISH.qualified()
                    + " report). Finish it, or -Dforce=true.",
                    new WorkspaceReportSpec(WsGoal.SIBLING_REMOVE_PUBLISH,
                            report.build()));
        }
        if (!clean) {
            getLog().warn("  -Dforce=true: discarding the ✗ findings above.");
        }

        // Gate 3 — delete, then GC the lease record.
        Path gcRecord = SiblingRemoval.delete(target);
        getLog().info("  Removed " + target.sibling().root().getAbsolutePath());
        if (gcRecord != null) {
            getLog().info("  Lease record GC'd: " + gcRecord.getFileName());
        }

        report.section("Removed")
                .paragraph("`" + target.sibling().root().getAbsolutePath()
                        + "` (" + target.states().size() + " member "
                        + "director" + (target.states().size() == 1 ? "y" : "ies")
                        + (clean ? "" : ", force-discarded findings above")
                        + "). The sync layer propagates the deletion to "
                        + "every machine; staggered file versioning is the "
                        + "recovery net."
                        + (gcRecord == null ? ""
                                : " Lease record `" + gcRecord.getFileName()
                                        + "` garbage-collected."));
        return new WorkspaceReportSpec(WsGoal.SIBLING_REMOVE_PUBLISH,
                report.build());
    }
}
