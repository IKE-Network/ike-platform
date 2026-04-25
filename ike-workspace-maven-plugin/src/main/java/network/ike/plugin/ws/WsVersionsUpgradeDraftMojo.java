package network.ike.plugin.ws;

import network.ike.plugin.support.upgrade.SessionCandidateVersionResolver;
import network.ike.plugin.support.upgrade.VersionUpgradePlanBuilder;
import network.ike.workspace.LiteralVersionUpgrade;
import network.ike.workspace.NodeVersionUpgrade;
import network.ike.workspace.ParentVersionUpgrade;
import network.ike.workspace.PropertyVersionUpgrade;
import network.ike.workspace.Subproject;
import network.ike.workspace.VersionUpgradePlan;
import network.ike.workspace.VersionUpgradePlanWriter;
import network.ike.workspace.VersionUpgradeRules;
import network.ike.workspace.VersionUpgradeRulesException;
import network.ike.workspace.VersionUpgradeRulesReader;
import network.ike.workspace.VersionUpgradeStatus;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preview the version upgrades that
 * {@code ws:versions-upgrade-publish} would apply across every
 * subproject in the workspace.
 *
 * <p>Walks {@code workspace.yaml} in topological order, scans each
 * cloned subproject's root {@code pom.xml} for {@code <parent>},
 * version properties, and literal plugin/dependency versions, and
 * consults the workspace-level
 * {@code versions-upgrade-rules.yaml} to decide which coordinates are
 * eligible. The result is serialized as a
 * workspace-scope {@code versions-upgrade-plan.yaml} at the workspace
 * root, ready for human review.
 *
 * <p>This goal is read-only: it never modifies any POM. The companion
 * {@code ws:versions-upgrade-publish} consumes the plan file and
 * applies the {@code READY} entries via OpenRewrite (preserving
 * comments and formatting). Edit the plan between draft and publish
 * to remove entries you don't want or to change a {@code to:} value
 * to pin a specific target.
 *
 * <p>If {@code versions-upgrade-rules.yaml} is absent the goal aborts
 * — there is no safe default. The {@code default-action: block}
 * convention means an empty ruleset would propose nothing, which is
 * indistinguishable from "everything is up to date" and would mask a
 * misconfigured ruleset. Create the file at the workspace root with
 * at minimum:
 * <pre>
 * schema-version: "1.0"
 * default-action: block
 * rules:
 *   - match: "network.ike.*"
 *     action: allow
 * </pre>
 *
 * <p><strong>PENDING_UPSTREAM</strong> marking — not yet applied here.
 * Each per-node plan is built independently against whatever the
 * resolver finds on Nexus today. If the workspace is mid-cascade
 * (an upstream subproject has a tag staged but not yet released),
 * re-draft after each release so consumers pick up the new version.
 *
 * @see WsVersionsUpgradePublishMojo
 */
@Mojo(name = "versions-upgrade-draft", projectRequired = false,
        aggregator = true)
public class WsVersionsUpgradeDraftMojo extends AbstractWorkspaceMojo {

    /** The current Maven session — provides the version resolver. */
    @Inject
    private Session session;

    /**
     * Path to the workspace-level ruleset that controls which
     * coordinates may be upgraded across all subprojects. Defaults to
     * {@code versions-upgrade-rules.yaml} at the workspace root.
     */
    @Parameter(property = "rulesFile")
    String rulesFile;

    /**
     * Path the generated plan is written to. Defaults to
     * {@code versions-upgrade-plan.yaml} at the workspace root.
     */
    @Parameter(property = "outputFile")
    String outputFile;

    /**
     * The {@code ike-tooling.version} value at draft time, surfaced
     * in the plan header for human review. Defaults to the property
     * of the same name from the workspace root POM if present; pass
     * {@code -Dike-tooling.version=<value>} to override.
     */
    @Parameter(property = "ike-tooling.version",
               defaultValue = "${ike-tooling.version}")
    String ikeToolingVersion;

    /** Creates this goal instance. */
    public WsVersionsUpgradeDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        File workspaceRoot = workspaceRoot();
        Path workspaceRootPath = workspaceRoot.toPath();
        Path rulesPath = resolveRulesPath(workspaceRootPath);
        Path planPath = resolveOutputPath(workspaceRootPath);

        WorkspaceGraph graph = loadGraph();
        VersionUpgradeRules rules = loadRules(rulesPath);

        Map<String, Path> nodePoms = collectNodePoms(graph, workspaceRoot);
        if (nodePoms.isEmpty()) {
            throw new MojoException(
                    "No cloned subprojects found in workspace "
                            + workspaceRoot
                            + ".\n  Run ws:init first, or check that"
                            + " each subproject directory contains a"
                            + " pom.xml.");
        }

        VersionUpgradePlanBuilder builder = new VersionUpgradePlanBuilder(
                rules, new SessionCandidateVersionResolver(session));
        VersionUpgradePlan plan = builder.buildWorkspacePlan(
                nodePoms, normalizeIkeToolingVersion());

        VersionUpgradePlanWriter.write(plan, planPath);

        logSummary(plan, rulesPath, planPath, nodePoms.size());

        writeReport(WsGoal.VERSIONS_UPGRADE_DRAFT,
                buildReport(plan, rulesPath, planPath));
    }

    /**
     * Resolve the ruleset path from the {@code -DrulesFile} parameter
     * or the workspace-root default.
     */
    private Path resolveRulesPath(Path workspaceRootPath) {
        if (rulesFile != null && !rulesFile.isBlank()) {
            return Path.of(rulesFile);
        }
        return workspaceRootPath.resolve("versions-upgrade-rules.yaml");
    }

    /**
     * Resolve the output plan path from the {@code -DoutputFile}
     * parameter or the workspace-root default.
     */
    private Path resolveOutputPath(Path workspaceRootPath) {
        if (outputFile != null && !outputFile.isBlank()) {
            return Path.of(outputFile);
        }
        return workspaceRootPath.resolve("versions-upgrade-plan.yaml");
    }

    private VersionUpgradeRules loadRules(Path rulesPath) {
        if (!Files.isRegularFile(rulesPath)) {
            throw new MojoException(
                    "Workspace ruleset not found: " + rulesPath
                            + "\n  Create this file at the workspace"
                            + " root with at minimum:\n"
                            + "    schema-version: \"1.0\"\n"
                            + "    default-action: block\n"
                            + "    rules:\n"
                            + "      - match: \"network.ike.*\"\n"
                            + "        action: allow\n"
                            + "  Or set -DrulesFile=<path> to point at"
                            + " a shared ruleset.");
        }
        try {
            return VersionUpgradeRulesReader.read(rulesPath);
        } catch (VersionUpgradeRulesException e) {
            throw new MojoException(
                    "Cannot read ruleset " + rulesPath + ": "
                            + e.getMessage(), e);
        }
    }

    /**
     * Walk subprojects in topological order and collect each one's
     * root {@code pom.xml}. Subprojects without a {@code pom.xml} on
     * disk (uncloned, or non-Maven) are skipped with a debug log line.
     */
    private Map<String, Path> collectNodePoms(WorkspaceGraph graph,
                                              File workspaceRoot) {
        Map<String, Path> nodePoms = new LinkedHashMap<>();
        for (String name : graph.topologicalSort()) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            if (subproject == null) continue;
            File subprojectDir = new File(workspaceRoot, name);
            File pom = new File(subprojectDir, "pom.xml");
            if (!pom.isFile()) {
                getLog().debug("  " + name
                        + ": no pom.xml on disk — skipping");
                continue;
            }
            nodePoms.put(name, pom.toPath());
        }
        return nodePoms;
    }

    private String normalizeIkeToolingVersion() {
        if (ikeToolingVersion == null) return null;
        // When the property is undeclared at the workspace root, Maven
        // leaves the literal ${ike-tooling.version} unresolved.
        if (ikeToolingVersion.startsWith("${")) return null;
        if (ikeToolingVersion.isBlank()) return null;
        return ikeToolingVersion;
    }

    private void logSummary(VersionUpgradePlan plan, Path rulesPath,
                            Path planPath, int nodeCount) {
        getLog().info("");
        getLog().info("ws:versions-upgrade-draft");
        getLog().info("  ruleset: " + rulesPath);
        getLog().info("  plan:    " + planPath);
        getLog().info("  nodes:   " + nodeCount);
        if (plan.ikeToolingVersion() != null) {
            getLog().info("  ike-tooling.version: "
                    + plan.ikeToolingVersion());
        }
        getLog().info("");

        for (Map.Entry<String, NodeVersionUpgrade> entry
                : plan.nodes().entrySet()) {
            logNode(entry.getKey(), entry.getValue());
        }

        Counts counts = countActions(plan);
        getLog().info("");
        getLog().info("Summary: " + counts.summary());
        getLog().info("");
        getLog().info("Edit " + planPath.getFileName()
                + " to refine, then run ws:versions-upgrade-publish.");
    }

    private void logNode(String nodeName, NodeVersionUpgrade node) {
        getLog().info("Node: " + nodeName);
        if (node.parent() != null) {
            ParentVersionUpgrade p = node.parent();
            getLog().info("  parent " + p.groupId() + ":"
                    + p.artifactId() + ": " + p.fromVersion()
                    + " -> " + p.toVersion()
                    + "  [" + statusLabel(p.status()) + "]"
                    + reasonSuffix(p.reason()));
        }
        for (PropertyVersionUpgrade prop : node.properties()) {
            getLog().info("  property ${" + prop.propertyName() + "}: "
                    + prop.fromVersion() + " -> " + prop.toVersion()
                    + "  [" + statusLabel(prop.status()) + "]"
                    + reasonSuffix(prop.reason()));
        }
        for (LiteralVersionUpgrade lit : node.literals()) {
            getLog().info("  literal " + lit.groupId() + ":"
                    + lit.artifactId() + ": "
                    + lit.fromVersion() + " -> " + lit.toVersion()
                    + "  [" + statusLabel(lit.status()) + "]"
                    + reasonSuffix(lit.reason()));
        }
        if (node.parent() == null
                && node.properties().isEmpty()
                && node.literals().isEmpty()) {
            getLog().info("  (no upgrades proposed)");
        }
    }

    private static String statusLabel(VersionUpgradeStatus status) {
        return status.name().toLowerCase().replace('_', '-');
    }

    private static String reasonSuffix(String reason) {
        return reason == null ? "" : "  — " + reason;
    }

    private static Counts countActions(VersionUpgradePlan plan) {
        int ready = 0;
        int blocked = 0;
        int pending = 0;
        for (NodeVersionUpgrade node : plan.nodes().values()) {
            if (node.parent() != null) {
                switch (node.parent().status()) {
                    case READY -> ready++;
                    case BLOCKED -> blocked++;
                    case PENDING_UPSTREAM -> pending++;
                }
            }
            for (PropertyVersionUpgrade p : node.properties()) {
                switch (p.status()) {
                    case READY -> ready++;
                    case BLOCKED -> blocked++;
                    case PENDING_UPSTREAM -> pending++;
                }
            }
            for (LiteralVersionUpgrade l : node.literals()) {
                switch (l.status()) {
                    case READY -> ready++;
                    case BLOCKED -> blocked++;
                    case PENDING_UPSTREAM -> pending++;
                }
            }
        }
        return new Counts(ready, blocked, pending);
    }

    private record Counts(int ready, int blocked, int pending) {
        String summary() {
            return ready + " ready, " + blocked + " blocked, "
                    + pending + " pending-upstream";
        }
    }

    private String buildReport(VersionUpgradePlan plan, Path rulesPath,
                               Path planPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Workspace:** ").append(workspaceName()).append("\n");
        sb.append("**Scope:** workspace\n");
        sb.append("**Ruleset:** `").append(rulesPath).append("`\n");
        sb.append("**Plan:** `").append(planPath).append("`\n");
        if (plan.ikeToolingVersion() != null) {
            sb.append("**ike-tooling.version:** `")
                    .append(plan.ikeToolingVersion()).append("`\n");
        }
        sb.append("**Generated:** ").append(plan.generated()).append("\n");
        sb.append("**Nodes:** ").append(plan.nodes().size()).append("\n\n");

        for (Map.Entry<String, NodeVersionUpgrade> entry
                : plan.nodes().entrySet()) {
            appendNodeSection(sb, entry.getKey(), entry.getValue());
        }

        Counts counts = countActions(plan);
        sb.append("## Summary\n");
        sb.append("- ready:            ").append(counts.ready()).append("\n");
        sb.append("- blocked:          ").append(counts.blocked()).append("\n");
        sb.append("- pending-upstream: ").append(counts.pending()).append("\n");
        sb.append("\n");
        sb.append("Edit `").append(planPath.getFileName())
                .append("` to refine, then run "
                        + "`ws:versions-upgrade-publish`.\n");
        return sb.toString();
    }

    private static void appendNodeSection(StringBuilder sb, String nodeName,
                                          NodeVersionUpgrade node) {
        sb.append("## ").append(nodeName).append("\n");
        if (node.parent() != null) {
            ParentVersionUpgrade p = node.parent();
            sb.append("- parent `").append(p.groupId()).append(":")
                    .append(p.artifactId()).append("`: ")
                    .append(p.fromVersion()).append(" → ")
                    .append(p.toVersion()).append(" [")
                    .append(statusLabel(p.status())).append("]")
                    .append(reasonSuffix(p.reason())).append("\n");
        }
        for (PropertyVersionUpgrade prop : node.properties()) {
            sb.append("- property `${").append(prop.propertyName())
                    .append("}`: ").append(prop.fromVersion())
                    .append(" → ").append(prop.toVersion()).append(" [")
                    .append(statusLabel(prop.status())).append("]")
                    .append(reasonSuffix(prop.reason())).append("\n");
        }
        for (LiteralVersionUpgrade lit : node.literals()) {
            sb.append("- literal `").append(lit.groupId()).append(":")
                    .append(lit.artifactId()).append("`: ")
                    .append(lit.fromVersion()).append(" → ")
                    .append(lit.toVersion()).append(" [")
                    .append(statusLabel(lit.status())).append("]")
                    .append(reasonSuffix(lit.reason())).append("\n");
        }
        if (node.parent() == null
                && node.properties().isEmpty()
                && node.literals().isEmpty()) {
            sb.append("- _no upgrades proposed_\n");
        }
        sb.append("\n");
    }
}
