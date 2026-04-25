package network.ike.plugin.ws;

import network.ike.plugin.support.upgrade.VersionUpgradeApplyException;
import network.ike.plugin.support.upgrade.VersionUpgradePlanApplier;
import network.ike.plugin.support.upgrade.VersionUpgradePlanBuilder;
import network.ike.workspace.LiteralVersionUpgrade;
import network.ike.workspace.NodeVersionUpgrade;
import network.ike.workspace.ParentVersionUpgrade;
import network.ike.workspace.PropertyVersionUpgrade;
import network.ike.workspace.Subproject;
import network.ike.workspace.VersionUpgradePlan;
import network.ike.workspace.VersionUpgradePlanException;
import network.ike.workspace.VersionUpgradePlanReader;
import network.ike.workspace.VersionUpgradeScope;
import network.ike.workspace.VersionUpgradeStatus;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apply a previously drafted workspace
 * {@code versions-upgrade-plan.yaml} across every subproject in the
 * workspace.
 *
 * <p>Walks {@code workspace.yaml} in topological order. For each node
 * named in the plan, locates the matching subproject directory,
 * confirms the on-disk POM exists, and rewrites
 * {@code <parent><version>}, {@code <properties>} entries, and
 * literal plugin/dependency versions to the targets recorded in the
 * plan. Edits are performed via OpenRewrite's XML LST so comments
 * and formatting round-trip cleanly. Entries whose status is not
 * {@code READY} are skipped — {@code BLOCKED} and
 * {@code PENDING_UPSTREAM} are diagnostic markers, not actions.
 *
 * <p><strong>Staleness check.</strong> Before any edit, the publish
 * recomputes the {@code pomFingerprint} of the current set of POMs
 * (in the same insertion order the plan recorded) and compares to the
 * one stamped into the plan at draft time. If they differ, the
 * publish aborts with a "regenerate the plan" hint — staleness is
 * never silently absorbed. Pass {@code -DforceStale=true} to skip the
 * top-level check (intended for scripted recovery; the plan applier
 * still re-validates each {@code from-version} per entry).
 *
 * <p>The plan file is left in place after a successful publish so the
 * caller can re-run for documentation or incorporate it into a
 * release commit message. Pass {@code -DdeletePlan=true} to remove it.
 *
 * <p>Each subproject is edited in place but not committed — review
 * the changes per subproject and use {@code ws:commit} to land them
 * in coordinated form.
 *
 * @see WsVersionsUpgradeDraftMojo
 */
@Mojo(name = "versions-upgrade-publish", projectRequired = false,
        aggregator = true)
public class WsVersionsUpgradePublishMojo extends AbstractWorkspaceMojo {

    /**
     * Path to the plan file written by {@code ws:versions-upgrade-draft}.
     * Defaults to {@code versions-upgrade-plan.yaml} at the workspace
     * root.
     */
    @Parameter(property = "planFile")
    String planFile;

    /**
     * If true, skip the workspace POM-set fingerprint staleness check.
     * The per-entry {@code from-version} validation in
     * {@link VersionUpgradePlanApplier} still runs, so mismatches
     * still abort — this only bypasses the top-level fingerprint guard.
     */
    @Parameter(property = "forceStale", defaultValue = "false")
    boolean forceStale;

    /**
     * If true, delete the plan file after a successful publish.
     */
    @Parameter(property = "deletePlan", defaultValue = "false")
    boolean deletePlan;

    /** Creates this goal instance. */
    public WsVersionsUpgradePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        File workspaceRoot = workspaceRoot();
        Path workspaceRootPath = workspaceRoot.toPath();
        Path planPath = resolvePlanPath(workspaceRootPath);

        if (!Files.isRegularFile(planPath)) {
            throw new MojoException(
                    "Plan not found: " + planPath
                            + "\n  Run ws:versions-upgrade-draft first,"
                            + " or set -DplanFile=<path>.");
        }

        VersionUpgradePlan plan = readPlan(planPath);
        if (plan.scope() != VersionUpgradeScope.WORKSPACE) {
            throw new MojoException(
                    "Plan scope is " + plan.scope()
                            + " — ws:versions-upgrade-publish only handles"
                            + " workspace-scope plans. Use the ike plugin's"
                            + " ike:versions-upgrade-publish for"
                            + " module-scope plans.");
        }

        WorkspaceGraph graph = loadGraph();
        Map<String, Path> nodePoms = collectNodePomsForPlan(
                plan, graph, workspaceRoot);

        verifyFingerprint(plan, nodePoms, planPath);

        List<NodeOutcome> outcomes = applyPlan(plan, nodePoms);

        if (deletePlan) {
            try {
                Files.deleteIfExists(planPath);
                getLog().info("Deleted plan: " + planPath);
            } catch (IOException e) {
                getLog().warn("Could not delete plan " + planPath
                        + ": " + e.getMessage());
            }
        }

        logSummary(outcomes, planPath);

        writeReport(WsGoal.VERSIONS_UPGRADE_PUBLISH,
                buildReport(plan, outcomes, planPath));
    }

    private Path resolvePlanPath(Path workspaceRootPath) {
        if (planFile != null && !planFile.isBlank()) {
            return Path.of(planFile);
        }
        return workspaceRootPath.resolve("versions-upgrade-plan.yaml");
    }

    private VersionUpgradePlan readPlan(Path planPath) {
        try {
            return VersionUpgradePlanReader.read(planPath);
        } catch (VersionUpgradePlanException e) {
            throw new MojoException(
                    "Cannot read plan " + planPath + ": "
                            + e.getMessage(), e);
        }
    }

    /**
     * Resolve each node named in the plan to its on-disk POM path.
     * Aborts when a plan node has no matching workspace subproject or
     * when a referenced subproject is not cloned — both indicate the
     * plan and workspace have drifted apart.
     */
    private Map<String, Path> collectNodePomsForPlan(
            VersionUpgradePlan plan, WorkspaceGraph graph,
            File workspaceRoot) {
        Map<String, Path> nodePoms = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> unCloned = new ArrayList<>();

        for (String nodeName : plan.nodes().keySet()) {
            Subproject subproject = graph.manifest().subprojects()
                    .get(nodeName);
            if (subproject == null) {
                missing.add(nodeName);
                continue;
            }
            File pom = new File(new File(workspaceRoot, nodeName), "pom.xml");
            if (!pom.isFile()) {
                unCloned.add(nodeName);
                continue;
            }
            nodePoms.put(nodeName, pom.toPath());
        }

        if (!missing.isEmpty() || !unCloned.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "Plan does not align with workspace.yaml.");
            if (!missing.isEmpty()) {
                msg.append("\n  Plan nodes not in workspace.yaml: ")
                        .append(missing);
            }
            if (!unCloned.isEmpty()) {
                msg.append("\n  Plan nodes not cloned on disk: ")
                        .append(unCloned)
                        .append("\n  Run ws:init to clone missing subprojects.");
            }
            msg.append("\n  Regenerate the plan with"
                    + " ws:versions-upgrade-draft.");
            throw new MojoException(msg.toString());
        }

        return nodePoms;
    }

    private void verifyFingerprint(VersionUpgradePlan plan,
                                   Map<String, Path> nodePoms,
                                   Path planPath) {
        String stamped = plan.pomFingerprint();
        if (stamped == null || stamped.isBlank()) {
            getLog().warn("Plan has no pom-fingerprint — staleness check"
                    + " skipped. The applier's per-entry from-version"
                    + " checks remain in force.");
            return;
        }
        // Insertion order of nodePoms matches plan.nodes() order, which
        // matches the order draft passed to buildWorkspacePlan; the
        // fingerprint is sensitive to that order.
        String current = VersionUpgradePlanBuilder.fingerprint(
                new ArrayList<>(nodePoms.values()));
        if (stamped.equals(current)) {
            return;
        }
        if (forceStale) {
            getLog().warn("Workspace POM fingerprint differs from plan ("
                    + "current=" + current + ", plan=" + stamped
                    + ") — proceeding because -DforceStale=true.");
            return;
        }
        throw new MojoException(
                "Workspace POM fingerprint differs from the plan."
                        + "\n  Plan:    " + stamped
                        + "\n  Current: " + current
                        + "\n  One or more subproject POMs have been edited"
                        + " since ws:versions-upgrade-draft was run."
                        + " Regenerate the plan and re-publish."
                        + "\n  Plan file: " + planPath
                        + "\n  Bypass: -DforceStale=true (per-entry"
                        + " checks still apply).");
    }

    private List<NodeOutcome> applyPlan(VersionUpgradePlan plan,
                                        Map<String, Path> nodePoms) {
        List<NodeOutcome> outcomes = new ArrayList<>();
        for (Map.Entry<String, NodeVersionUpgrade> entry
                : plan.nodes().entrySet()) {
            String nodeName = entry.getKey();
            NodeVersionUpgrade node = entry.getValue();
            Path pomPath = nodePoms.get(nodeName);
            int edits;
            try {
                edits = VersionUpgradePlanApplier.apply(pomPath, node);
            } catch (VersionUpgradeApplyException e) {
                throw new MojoException(
                        "Cannot apply plan to node '" + nodeName + "' ("
                                + pomPath + "): " + e.getMessage(), e);
            }
            outcomes.add(new NodeOutcome(nodeName, edits, countSkipped(node)));
        }
        return outcomes;
    }

    private static int countSkipped(NodeVersionUpgrade node) {
        int skipped = 0;
        if (node.parent() != null
                && node.parent().status() != VersionUpgradeStatus.READY) {
            skipped++;
        }
        for (PropertyVersionUpgrade p : node.properties()) {
            if (p.status() != VersionUpgradeStatus.READY) skipped++;
        }
        for (LiteralVersionUpgrade l : node.literals()) {
            if (l.status() != VersionUpgradeStatus.READY) skipped++;
        }
        return skipped;
    }

    private void logSummary(List<NodeOutcome> outcomes, Path planPath) {
        getLog().info("");
        getLog().info("ws:versions-upgrade-publish");
        getLog().info("  plan:    " + planPath);
        getLog().info("  nodes:   " + outcomes.size());
        getLog().info("");

        int totalEdits = 0;
        int totalSkipped = 0;
        for (NodeOutcome o : outcomes) {
            totalEdits += o.edits();
            totalSkipped += o.skipped();
            getLog().info("  " + o.nodeName() + ": " + o.edits()
                    + " edit(s)"
                    + (o.skipped() > 0 ? ", " + o.skipped()
                            + " non-ready skipped" : ""));
        }

        getLog().info("");
        if (totalSkipped > 0) {
            getLog().info("  " + totalSkipped + " non-ready entry/entries"
                    + " skipped (blocked or pending-upstream).");
        }
        if (totalEdits == 0) {
            getLog().info("  No changes applied.");
        } else {
            getLog().info("  Applied " + totalEdits
                    + " upgrade(s) across " + outcomes.size()
                    + " node(s).");
        }
    }

    private String buildReport(VersionUpgradePlan plan,
                               List<NodeOutcome> outcomes,
                               Path planPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Workspace:** ").append(workspaceName()).append("\n");
        sb.append("**Plan:** `").append(planPath).append("`\n");
        if (plan.planHash() != null) {
            sb.append("**Plan hash:** `").append(plan.planHash())
                    .append("`\n");
        }
        if (plan.ikeToolingVersion() != null) {
            sb.append("**ike-tooling.version:** `")
                    .append(plan.ikeToolingVersion()).append("`\n");
        }

        int totalEdits = outcomes.stream()
                .mapToInt(NodeOutcome::edits).sum();
        int totalSkipped = outcomes.stream()
                .mapToInt(NodeOutcome::skipped).sum();
        sb.append("**Edits applied:** ").append(totalEdits).append("\n");
        sb.append("**Non-ready skipped:** ").append(totalSkipped)
                .append("\n\n");

        sb.append("## Per-node outcomes\n");
        sb.append("| Node | Edits | Skipped |\n");
        sb.append("|------|------:|--------:|\n");
        for (NodeOutcome o : outcomes) {
            sb.append("| ").append(o.nodeName())
                    .append(" | ").append(o.edits())
                    .append(" | ").append(o.skipped())
                    .append(" |\n");
        }
        sb.append("\n");

        sb.append("## Applied details\n");
        for (Map.Entry<String, NodeVersionUpgrade> entry
                : plan.nodes().entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n");
            appendApplied(sb, entry.getValue());
            sb.append("\n");
        }

        return sb.toString();
    }

    private static void appendApplied(StringBuilder sb,
                                      NodeVersionUpgrade node) {
        boolean any = false;
        if (node.parent() != null
                && node.parent().status() == VersionUpgradeStatus.READY) {
            ParentVersionUpgrade p = node.parent();
            sb.append("- parent `").append(p.groupId()).append(":")
                    .append(p.artifactId()).append("`: ")
                    .append(p.fromVersion()).append(" → ")
                    .append(p.toVersion()).append("\n");
            any = true;
        }
        for (PropertyVersionUpgrade prop : node.properties()) {
            if (prop.status() != VersionUpgradeStatus.READY) continue;
            sb.append("- property `${").append(prop.propertyName())
                    .append("}`: ").append(prop.fromVersion())
                    .append(" → ").append(prop.toVersion()).append("\n");
            any = true;
        }
        for (LiteralVersionUpgrade lit : node.literals()) {
            if (lit.status() != VersionUpgradeStatus.READY) continue;
            sb.append("- literal `").append(lit.groupId()).append(":")
                    .append(lit.artifactId()).append("`: ")
                    .append(lit.fromVersion()).append(" → ")
                    .append(lit.toVersion()).append("\n");
            any = true;
        }
        if (!any) {
            sb.append("- _no ready upgrades_\n");
        }
    }

    /** Per-node application outcome for the summary table. */
    private record NodeOutcome(String nodeName, int edits, int skipped) {}
}
