package network.ike.plugin.ws;

import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Preview rolling the working set back from a failed release cycle
 * (IKE-Network/ike-issues#1010).
 *
 * <p>For the root and every cloned member, reports the release-cadence
 * commits sitting unpushed on top of the branch, the local tags on
 * them, and the commit a rollback would reset to. Pushed history is
 * never touched; repositories with uncommitted changes, no upstream,
 * or cycle commits buried beneath later work are reported as refusals
 * — and {@code ws:release-rollback-publish} is all-or-nothing over the
 * set, refusing while any repository refuses.
 *
 * <p>Usage: {@code mvn ws:release-rollback-draft}
 */
@Mojo(name = "release-rollback-draft", projectRequired = false,
        aggregator = true)
public class WsReleaseRollbackDraftMojo extends AbstractWorkspaceMojo {

    /** Draft by default; the publish subclass flips it. */
    boolean publish = false;

    /** Creates this goal instance. */
    public WsReleaseRollbackDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        List<String> members = graph.topologicalSort(
                new LinkedHashSet<>(graph.manifest().subprojects().keySet()));

        getLog().info("");
        getLog().info("ike-komet-wsr — Release rollback"
                .replace("ike-komet-wsr", root.getName()));
        getLog().info("══════════════════════════════════════════════════");
        getLog().info(publish
                ? "  Mode:  PUBLISH — resets and tag deletions are real"
                : "  Mode:  DRAFT — nothing is reset");
        getLog().info("");

        List<WorkspaceReleaseRollback.RepoPlan> plans = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        for (String name : members) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                getLog().info("  ⚠ " + name + " — not cloned, skipping");
                continue;
            }
            plans.add(WorkspaceReleaseRollback.plan(name, dir));
        }
        plans.add(WorkspaceReleaseRollback.plan("(workspace root)", root));

        int discarding = 0;
        for (WorkspaceReleaseRollback.RepoPlan plan : plans) {
            if (plan.refusal() != null) {
                refusals.add(plan.name() + ": " + plan.refusal());
                getLog().error("  ✗ " + plan.name() + " — " + plan.refusal());
                continue;
            }
            if (!plan.hasWork()) {
                getLog().info("  = " + plan.name() + " — nothing to roll back");
                continue;
            }
            discarding++;
            getLog().info("  ↩ " + plan.name() + " — reset to "
                    + plan.targetSha().substring(0, 9));
            for (String line : plan.discards()) {
                getLog().info("      discard " + line);
            }
            for (String tag : plan.tags()) {
                getLog().info("      delete tag " + tag);
            }
        }
        getLog().info("");

        if (!refusals.isEmpty()) {
            if (publish) {
                throw new MojoException("Rollback refused — the working set"
                        + " rolls back atomically and " + refusals.size()
                        + " repositories block it:\n    "
                        + String.join("\n    ", refusals));
            }
            getLog().warn("Publish would refuse: " + refusals.size()
                    + " repositories block an atomic rollback.");
        }

        if (publish && discarding > 0) {
            for (WorkspaceReleaseRollback.RepoPlan plan : plans) {
                if (plan.hasWork()) {
                    getLog().info("  Rolling back " + plan.name());
                    WorkspaceReleaseRollback.apply(plan, getLog());
                }
            }
            getLog().info("Rollback complete: " + discarding
                    + " repositories reset.");
        } else if (!publish) {
            getLog().info("[DRAFT] " + discarding + " repositories would"
                    + " reset; run ws:release-rollback-publish to apply.");
        } else {
            getLog().info("Nothing to roll back anywhere.");
        }

        return new WorkspaceReportSpec(
                publish ? WsGoal.RELEASE_ROLLBACK_PUBLISH
                        : WsGoal.RELEASE_ROLLBACK_DRAFT,
                "# Release rollback\n\n" + discarding
                        + " repositories with cycle commits; "
                        + refusals.size() + " refusals.\n");
    }
}
