package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.WorkingSet;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.List;

/**
 * Preview removing a sibling working set — the read-only counterpart of
 * {@link SiblingRemovePublishMojo} (IKE-Network/ike-issues#600).
 *
 * <p>The documented cleanup used to be {@code rm -rf}: correct, but
 * unguarded against exactly the things that die silently with a deleted
 * tree. This preview names them per member repository — uncommitted
 * changes, stashes, unlanded branches (squash-aware), bare member trees —
 * and reads the sibling's lease without acquiring anything. Every finding
 * comes with its remediation; {@code rm -rf} remains valid for the
 * I-know-what-I-am-doing case, and {@code -Dforce=true} is its
 * goal-shaped equivalent.
 *
 * <pre>{@code
 * mvn ws:sibling-remove-draft   -Dfeature=jira-456
 * mvn ws:sibling-remove-publish -Dfeature=jira-456
 * }</pre>
 *
 * @see SiblingRemovePublishMojo for the executing counterpart
 * @see SiblingListMojo for the inventory these previews start from
 */
@Mojo(name = "sibling-remove-draft", projectRequired = false, aggregator = true)
public class SiblingRemoveDraftMojo extends AbstractWorkspaceMojo {

    /** Feature whose sibling ({@code <primary>꞉<feature>}) to remove. */
    @Parameter(property = "feature")
    String feature;

    /** Explicit sibling directory name; alternative to {@code -Dfeature}. */
    @Parameter(property = "name")
    String name;

    /** Creates this goal instance. */
    public SiblingRemoveDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        WorkingSet workingSet = resolveWorkingSet();
        File primaryRoot = isWorkspaceMode()
                ? workspaceRoot()
                : workingSet.members().getFirst().directory().toFile();
        SiblingRemoval.Target target = SiblingRemoval.resolve(
                primaryRoot, workingSet.baseName(), feature, name);

        getLog().info("");
        getLog().info(header("Sibling Remove — DRAFT"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Sibling: " + target.sibling().root().getAbsolutePath());
        getLog().info("");

        boolean clean = SiblingRemoval.clean(target);
        String lease = WorkingSetLease.status(target.sibling().root().toPath())
                .map(status -> status.line()
                        + (status.liveElsewhere()
                                ? " — **removal will refuse; force does not "
                                        + "override a live lease**" : ""))
                .orElse("no lease machinery on this machine");

        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Preview — nothing is deleted.**");
        SiblingRemoval.describeTarget(report, target);
        report.paragraph("**Lease:** " + lease);
        report.section("Preflight")
                .table(List.of("", "Member", "Finding"),
                        SiblingRemoval.preflightRows(target));
        report.paragraph(clean
                ? "Every member is clean and landed — `"
                        + WsGoal.SIBLING_REMOVE_PUBLISH.qualified()
                        + invocationSuffix() + "` will remove the sibling "
                        + "and garbage-collect its lease record."
                : "**Resolve the ✗ rows above** — finish the work "
                        + "(`ws:feature-finish-squash-publish` in the "
                        + "sibling), commit or drop stashes — **or pass "
                        + "`-Dforce=true`** to `"
                        + WsGoal.SIBLING_REMOVE_PUBLISH.qualified()
                        + "` to discard them deliberately. Force never "
                        + "overrides a lease held live elsewhere.");
        report.section("What removal does")
                .paragraph("Deletes the sibling directory (whole-working-set "
                        + "removal is the supported operation — the sync "
                        + "layer propagates it, staggered file versioning is "
                        + "the net) and deletes `leases/"
                        + target.sibling().name() + ".lease`, the record a "
                        + "working set leaves behind (ike-issues#1006's GC, "
                        + "done eagerly).");
        return new WorkspaceReportSpec(WsGoal.SIBLING_REMOVE_DRAFT,
                report.build());
    }

    private String invocationSuffix() {
        return name != null && !name.isBlank()
                ? " -Dname=" + name : " -Dfeature=" + feature;
    }
}
