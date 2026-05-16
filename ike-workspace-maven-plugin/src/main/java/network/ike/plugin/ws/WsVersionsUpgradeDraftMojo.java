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
import network.ike.workspace.VersionUpgradeNoise;
import network.ike.workspace.VersionUpgradeRule;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    protected WorkspaceReportSpec runGoal() throws MojoException {
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
                            + ".\n  Run ws:scaffold-init first, or check that"
                            + " each subproject directory contains a"
                            + " pom.xml.");
        }

        VersionUpgradePlanBuilder builder = new VersionUpgradePlanBuilder(
                rules, new SessionCandidateVersionResolver(session));
        VersionUpgradePlan plan = builder.buildWorkspacePlan(
                nodePoms, normalizeIkeToolingVersion());

        VersionUpgradePlanWriter.write(plan, planPath);

        logSummary(plan, rulesPath, planPath, nodePoms.size());

        return new WorkspaceReportSpec(WsGoal.VERSIONS_UPGRADE_DRAFT,
                buildReport(plan, rulesPath, planPath, rules));
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
                               Path planPath, VersionUpgradeRules rules) {
        // Bucket every entry into one of three categories (#384):
        //   READY     status=ready,   from!=to  → apply via publish
        //   BLOCKED   status=blocked, from!=to  → user may add an
        //                                         allow-rule to upgrade
        //   WARNING   status=blocked, from==to,
        //             reason != default-action  → conflict / ambiguity
        // Everything else (status=ready/blocked with from==to AND
        // default-action reason) is pure noise and filtered out.
        List<ActionableEntry> ready = new ArrayList<>();
        List<ActionableEntry> blocked = new ArrayList<>();
        List<ActionableEntry> pending = new ArrayList<>();
        List<ActionableEntry> warnings = new ArrayList<>();
        for (Map.Entry<String, NodeVersionUpgrade> nodeEntry
                : plan.nodes().entrySet()) {
            collectActionable(nodeEntry.getKey(), nodeEntry.getValue(),
                    ready, blocked, pending, warnings);
        }

        // Relative paths render as clickable markdown links in IntelliJ,
        // GitHub, and most editors. Render relative to the workspace
        // root (the report itself sits at the workspace root).
        Path reportDir = planPath.getParent();
        String planLink = reportDir == null ? planPath.toString()
                : reportDir.relativize(planPath).toString();
        String rulesLink = reportDir == null ? rulesPath.toString()
                : reportDir.relativize(rulesPath).toString();

        StringBuilder sb = new StringBuilder();
        sb.append("**Workspace:** ").append(workspaceName()).append("\n");
        sb.append("**Scope:** workspace\n");
        sb.append("**Files to edit:** [`")
                .append(planLink).append("`](").append(planLink)
                .append(") · [`")
                .append(rulesLink).append("`](").append(rulesLink)
                .append(")\n");
        if (plan.ikeToolingVersion() != null) {
            sb.append("**ike-tooling.version:** `")
                    .append(plan.ikeToolingVersion()).append("`\n");
        }
        sb.append("**Generated:** ").append(plan.generated()).append("\n");
        sb.append("**Nodes:** ").append(plan.nodes().size())
                .append("  ·  **Ready:** ").append(ready.size())
                .append("  ·  **Blocked:** ").append(blocked.size())
                .append("  ·  **Pending upstream:** ").append(pending.size())
                .append("  ·  **Warnings:** ").append(warnings.size())
                .append("\n\n");

        appendNextSteps(sb, planLink, rulesLink,
                ready.size(), blocked.size(), warnings.size());
        appendActiveRules(sb, rules);
        appendWarnings(sb, warnings);
        appendReady(sb, ready);
        appendBlockedGrouped(sb, blocked);
        appendPending(sb, pending);
        appendStandardsLink(sb);
        return sb.toString();
    }

    /** ike-issues#384: top-of-report numbered next-steps section. */
    private static void appendNextSteps(StringBuilder sb,
                                         String planLink, String rulesLink,
                                         int readyCount, int blockedCount,
                                         int warningCount) {
        sb.append("## Next steps\n\n");
        if (warningCount > 0) {
            sb.append("1. **Resolve the ").append(warningCount)
                    .append(" warning")
                    .append(warningCount == 1 ? "" : "s")
                    .append(" below** — conflicts / ambiguities that")
                    .append(" block an upgrade even though no version")
                    .append(" change is proposed.\n");
        }
        sb.append(warningCount > 0 ? "2." : "1.")
                .append(" **Review the ").append(readyCount)
                .append(" ready upgrade")
                .append(readyCount == 1 ? "" : "s")
                .append("** in the Ready section.\n");
        if (blockedCount > 0) {
            sb.append(warningCount > 0 ? "3." : "2.")
                    .append(" **(Optional) Allow more coordinates** —")
                    .append(" the Blocked section groups ").append(blockedCount)
                    .append(" entr").append(blockedCount == 1 ? "y" : "ies")
                    .append(" by groupId with a copy-paste-ready")
                    .append(" allow-rule. Paste any you want into")
                    .append(" `").append(rulesLink).append("` and")
                    .append(" re-draft to pick them up.\n");
        }
        int step = (warningCount > 0 ? 3 : 2) + (blockedCount > 0 ? 1 : 0);
        sb.append(step).append(". **Edit the plan** to remove or re-pin")
                .append(" specific entries: [`").append(planLink)
                .append("`](").append(planLink).append(").\n");
        sb.append(step + 1).append(". **Apply** with")
                .append(" `mvn ws:versions-upgrade-publish`.\n\n");
    }

    /** ike-issues#384: inline summary of the active rules. */
    private static void appendActiveRules(StringBuilder sb,
                                           VersionUpgradeRules rules) {
        if (rules == null) return;
        sb.append("## Active rules\n\n");
        sb.append("From the ruleset, in declaration order (first match wins):\n\n");
        for (VersionUpgradeRule rule : rules.rules()) {
            sb.append("- `").append(rule.groupIdPattern()).append(":")
                    .append(rule.artifactIdPattern()).append("`")
                    .append(" → **").append(rule.action().name()
                            .toLowerCase(Locale.ROOT))
                    .append("**");
            if (rule.pinnedVersion() != null
                    && !rule.pinnedVersion().isEmpty()) {
                sb.append(" (pin to `").append(rule.pinnedVersion())
                        .append("`)");
            }
            if (rule.reason() != null && !rule.reason().isEmpty()) {
                sb.append(" — ").append(rule.reason());
            }
            sb.append("\n");
        }
        sb.append("- _(default)_ → **")
                .append(rules.defaultAction().name()
                        .toLowerCase(Locale.ROOT))
                .append("**\n\n");
    }

    /** ike-issues#384: from==to entries with a meaningful reason. */
    private static void appendWarnings(StringBuilder sb,
                                        List<ActionableEntry> warnings) {
        if (warnings.isEmpty()) return;
        sb.append("## Warnings (").append(warnings.size()).append(")\n\n");
        sb.append("These coordinates did **not** get an upgrade proposal,")
                .append(" but the resolver flagged a real problem worth")
                .append(" investigating.\n\n");
        for (ActionableEntry w : warnings) {
            sb.append("- **").append(w.node()).append("** · ")
                    .append(w.coordLabel()).append(" stays at `")
                    .append(w.fromVersion()).append("` — ")
                    .append(w.reason()).append("\n");
        }
        sb.append("\n");
    }

    /** ike-issues#384: ready upgrades, one row per node. */
    private static void appendReady(StringBuilder sb,
                                     List<ActionableEntry> ready) {
        if (ready.isEmpty()) return;
        sb.append("## Ready (").append(ready.size()).append(")\n\n");
        sb.append("These will be applied by `ws:versions-upgrade-publish`")
                .append(". Edit the plan file to drop or re-pin any.\n\n");
        sb.append("| Node | Coordinate | From → To |\n");
        sb.append("|---|---|---|\n");
        for (ActionableEntry r : ready) {
            sb.append("| `").append(r.node()).append("` | ")
                    .append(r.coordLabel()).append(" | `")
                    .append(r.fromVersion()).append("` → `")
                    .append(r.toVersion()).append("` |\n");
        }
        sb.append("\n");
    }

    /**
     * ike-issues#384: blocked entries (newer version available, but
     * ruleset blocks). Grouped by groupId with a suggested allow-rule
     * snippet ready to paste into the ruleset.
     */
    private static void appendBlockedGrouped(StringBuilder sb,
                                              List<ActionableEntry> blocked) {
        if (blocked.isEmpty()) return;
        sb.append("## Blocked — newer available (")
                .append(blocked.size()).append(")\n\n");
        sb.append("Newer versions exist but the ruleset doesn't allow")
                .append(" the groupId. To allow a group, paste its")
                .append(" suggested rule into `versions-upgrade-rules.yaml`")
                .append(" and re-draft.\n\n");

        // Group by groupId, preserving first-seen order.
        Map<String, List<ActionableEntry>> byGroup =
                new LinkedHashMap<>();
        for (ActionableEntry b : blocked) {
            byGroup.computeIfAbsent(b.groupId(),
                    k -> new ArrayList<>()).add(b);
        }

        sb.append("| GroupId | Coords in this workspace | Suggested rule |\n");
        sb.append("|---|---|---|\n");
        for (Map.Entry<String, List<ActionableEntry>> g
                : byGroup.entrySet()) {
            String groupId = g.getKey();
            List<ActionableEntry> entries = g.getValue();
            sb.append("| `").append(groupId).append("` | ")
                    .append(coordSummary(entries)).append(" | ")
                    .append("`- match: \"").append(groupId)
                    .append(":*\"`<br>`  action: allow` |\n");
        }
        sb.append("\n");

        // Collapsible detail per group for transparency.
        sb.append("<details><summary>Detail — every blocked entry,")
                .append(" grouped</summary>\n\n");
        for (Map.Entry<String, List<ActionableEntry>> g
                : byGroup.entrySet()) {
            sb.append("**`").append(g.getKey()).append("`**\n");
            for (ActionableEntry b : g.getValue()) {
                sb.append("- `").append(b.node()).append("` · ")
                        .append(b.coordLabel()).append(": `")
                        .append(b.fromVersion()).append("` → `")
                        .append(b.toVersion()).append("`");
                if (b.reason() != null) {
                    sb.append(" — ").append(b.reason());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        sb.append("</details>\n\n");
    }

    /** ike-issues#384: pending-upstream entries (waiting on an upstream release). */
    private static void appendPending(StringBuilder sb,
                                       List<ActionableEntry> pending) {
        if (pending.isEmpty()) return;
        sb.append("## Pending upstream (").append(pending.size())
                .append(")\n\n");
        sb.append("Re-draft after the upstream releases — the resolver")
                .append(" will pick up the new version.\n\n");
        for (ActionableEntry p : pending) {
            sb.append("- `").append(p.node()).append("` · ")
                    .append(p.coordLabel()).append(": `")
                    .append(p.fromVersion()).append("` → `")
                    .append(p.toVersion()).append("`");
            if (p.reason() != null) sb.append(" — ").append(p.reason());
            sb.append("\n");
        }
        sb.append("\n");
    }

    /** ike-issues#384: link to the standards doc for conceptual context. */
    private static void appendStandardsLink(StringBuilder sb) {
        sb.append("---\n\n");
        sb.append("See [`IKE-WORKSPACE.md`](https://github.com/IKE-Network/")
                .append("ike-tooling/blob/main/ike-build-standards/")
                .append("src/main/standards/IKE-WORKSPACE.md) for the")
                .append(" workspace and versions-upgrade conventions.\n");
    }

    private static String coordSummary(List<ActionableEntry> entries) {
        // Distinct artifact-or-property labels, joined; show a count
        // suffix when there are many.
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (ActionableEntry e : entries) {
            seen.add(e.coordLabel());
        }
        int max = 3;
        StringBuilder out = new StringBuilder();
        int i = 0;
        for (String label : seen) {
            if (i > 0) out.append(", ");
            out.append(label);
            if (++i >= max && seen.size() > max) {
                out.append(", … (")
                        .append(seen.size() - max)
                        .append(" more)");
                break;
            }
        }
        return out.toString();
    }

    /**
     * ike-issues#384: classify each entry into ready / blocked /
     * pending / warnings buckets, dropping pure-noise entries entirely.
     */
    private static void collectActionable(String nodeName,
                                           NodeVersionUpgrade node,
                                           List<ActionableEntry> ready,
                                           List<ActionableEntry> blocked,
                                           List<ActionableEntry> pending,
                                           List<ActionableEntry> warnings) {
        if (node.parent() != null) {
            ParentVersionUpgrade p = node.parent();
            classify(nodeName, p.groupId(),
                    "parent `" + p.groupId() + ":" + p.artifactId() + "`",
                    p.fromVersion(), p.toVersion(),
                    p.status(), p.reason(),
                    ready, blocked, pending, warnings);
        }
        for (PropertyVersionUpgrade prop : node.properties()) {
            classify(nodeName, "<property>",
                    "property `${" + prop.propertyName() + "}`",
                    prop.fromVersion(), prop.toVersion(),
                    prop.status(), prop.reason(),
                    ready, blocked, pending, warnings);
        }
        for (LiteralVersionUpgrade lit : node.literals()) {
            classify(nodeName, lit.groupId(),
                    "literal `" + lit.groupId() + ":" + lit.artifactId() + "`",
                    lit.fromVersion(), lit.toVersion(),
                    lit.status(), lit.reason(),
                    ready, blocked, pending, warnings);
        }
    }

    private static void classify(String nodeName, String groupId,
                                  String coordLabel,
                                  String fromVersion, String toVersion,
                                  VersionUpgradeStatus status, String reason,
                                  List<ActionableEntry> ready,
                                  List<ActionableEntry> blocked,
                                  List<ActionableEntry> pending,
                                  List<ActionableEntry> warnings) {
        if (VersionUpgradeNoise.isPureNoise(status, fromVersion,
                toVersion, reason)) {
            return;
        }
        ActionableEntry entry = new ActionableEntry(nodeName, groupId,
                coordLabel, fromVersion, toVersion, status, reason);
        if (VersionUpgradeNoise.isInformationalSameVersion(status,
                fromVersion, toVersion, reason)) {
            warnings.add(entry);
            return;
        }
        switch (status) {
            case READY -> ready.add(entry);
            case BLOCKED -> blocked.add(entry);
            case PENDING_UPSTREAM -> pending.add(entry);
        }
    }

    /** Display record used by the redesigned report builder. */
    private record ActionableEntry(
            String node,
            String groupId,
            String coordLabel,
            String fromVersion,
            String toVersion,
            VersionUpgradeStatus status,
            String reason
    ) {}
}
