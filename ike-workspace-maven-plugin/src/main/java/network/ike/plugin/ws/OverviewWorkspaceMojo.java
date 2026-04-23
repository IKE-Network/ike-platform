package network.ike.plugin.ws;

import network.ike.workspace.Subproject;
import network.ike.workspace.Dependency;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consolidated workspace overview — manifest, graph, status, cascade.
 *
 * <p>Replaces the former separate {@code ws:dashboard}, {@code ws:status},
 * and {@code ws:graph} goals with a single command. Loads the manifest
 * once and presents four sections:
 *
 * <ol>
 *   <li><b>Manifest</b> — subproject count, consistency check</li>
 *   <li><b>Graph</b> — dependency order with direct dependencies</li>
 *   <li><b>Status</b> — branch, SHA, clean/uncommitted per subproject</li>
 *   <li><b>Cascade</b> — downstream rebuild impact of components with
 *       uncommitted changes</li>
 * </ol>
 *
 * <p>Use {@code -Dformat=dot} to output Graphviz DOT format instead
 * of the overview (delegates to graph rendering).
 *
 * <pre>{@code
 * mvn ws:overview
 * mvn ws:overview -Dformat=dot
 * }</pre>
 */
@Mojo(name = "overview", projectRequired = false)
public class OverviewWorkspaceMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public OverviewWorkspaceMojo() {}

    /** Output format: "overview" (default) or "dot" (Graphviz DOT). */
    @Parameter(property = "format", defaultValue = "overview")
    String format;

    @Override
    public void execute() throws MojoException {
        WorkspaceGraph graph = loadGraph();

        // DOT mode — delegate to graph-only output
        if ("dot".equalsIgnoreCase(format)) {
            printDot(graph);
            return;
        }

        File root = workspaceRoot();

        getLog().info("");
        getLog().info(header("Overview"));
        getLog().info("══════════════════════════════════════════════════════════════");

        // ── Section 1: Manifest ─────────────────────────────────────
        List<String> errors = graph.verify();
        getLog().info("");
        if (errors.isEmpty()) {
            getLog().info(Ansi.green("  ✓ ") + "Manifest: "
                    + graph.manifest().subprojects().size()
                    + " components — consistent");
        } else {
            getLog().warn(Ansi.red("  ✗ ") + "Manifest: "
                    + errors.size() + " error(s)");
            for (String error : errors) {
                getLog().warn("    " + error);
            }
        }

        // ── Section 2: Dependency Graph ─────────────────────────────
        List<String> sorted = graph.topologicalSort();
        getLog().info("");
        getLog().info("  Graph (dependency order)");
        getLog().info("  ──────────────────────────────────────────────────────");

        List<String[]> graphRows = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            String name = sorted.get(i);
            Subproject sub = graph.manifest().subprojects().get(name);
            String deps = sub.dependsOn().isEmpty() ? "—"
                    : sub.dependsOn().stream()
                        .map(Dependency::subproject)
                        .collect(Collectors.joining(", "));

            getLog().info(String.format("  %2d. %-24s → %s",
                    i + 1, name, deps));
            graphRows.add(new String[]{String.valueOf(i + 1), name,
                    sub.type().yamlName(), deps});
        }

        // ── Section 3: Subproject Status ─────────────────────────────
        Set<String> targets = graph.manifest().subprojects().keySet();

        getLog().info("");
        getLog().info("  Status");
        getLog().info("  ──────────────────────────────────────────────────────");
        getLog().info(String.format("  %-24s %-24s %-8s %s",
                "COMPONENT", "BRANCH", "SHA", ""));

        List<String> modifiedComponents = new ArrayList<>();
        List<String[]> statusRows = new ArrayList<>();
        int cloned = 0;
        int notCloned = 0;

        for (String name : targets) {
            Subproject sub = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);

            if (!dir.exists()) {
                notCloned++;
                getLog().info(String.format("  %-24s %-24s %-8s %s",
                        name, "—", "—", "not cloned"));
                statusRows.add(new String[]{name, "—", "—", "not cloned"});
                continue;
            }

            cloned++;
            String branch = gitBranch(dir);
            String sha = gitShortSha(dir);
            String status = gitStatus(dir);

            String marker;
            if (status.isEmpty()) {
                marker = Ansi.green("✓");
            } else {
                long count = status.lines().count();
                marker = Ansi.red("✗") + " " + count + " changed";
                modifiedComponents.add(name);
            }

            String branchCol = branch;
            if (sub.branch() != null && !branch.equals(sub.branch())) {
                branchCol = branch + Ansi.yellow(" ⚠");
            }

            getLog().info(String.format("  %-24s %-24s %-8s %s",
                    name, branchCol, sha, marker));
            statusRows.add(new String[]{name, branch, sha,
                    status.isEmpty() ? "clean"
                            : "uncommitted (" + status.lines().count() + " files)"});
        }

        getLog().info("");
        getLog().info("  " + cloned + " cloned, " + notCloned + " not cloned, "
                + modifiedComponents.size() + " with uncommitted changes");

        // ── Section 4: Feature Branch Divergence ────────────────────
        // If any subproject is on a feature branch, show how far it has
        // diverged from main — helps developers keep long-lived branches
        // up to date.
        List<String[]> divergenceRows = new ArrayList<>();
        boolean anyOnFeature = false;
        int warnThreshold = 20;

        for (String name : targets) {
            File dir = new File(root, name);
            if (!dir.exists() || !new File(dir, ".git").exists()) continue;

            String branch = gitBranch(dir);
            if (!branch.startsWith("feature/")) continue;

            anyOnFeature = true;
            try {
                List<String> behind = VcsOperations.commitLog(dir, branch, "main");
                List<String> ahead = VcsOperations.commitLog(dir, "main", branch);

                String divergence;
                String marker;
                if (behind.isEmpty()) {
                    divergence = "up to date";
                    marker = Ansi.green("✓");
                } else if (behind.size() >= warnThreshold) {
                    divergence = behind.size() + " behind, "
                            + ahead.size() + " ahead";
                    marker = Ansi.red("⚠") + " consider ws:update-feature";
                } else {
                    divergence = behind.size() + " behind, "
                            + ahead.size() + " ahead";
                    marker = Ansi.yellow("·");
                }

                divergenceRows.add(new String[]{name, branch,
                        String.valueOf(behind.size()),
                        String.valueOf(ahead.size()), divergence});

                if (!anyOnFeature) continue; // skip printing header until first
            } catch (MojoException e) {
                // Can't determine divergence (no main branch, etc.) — skip
                divergenceRows.add(new String[]{name, branch,
                        "?", "?", "unknown"});
            }
        }

        if (anyOnFeature) {
            getLog().info("");
            getLog().info("  Feature Branch Divergence (from main)");
            getLog().info("  ──────────────────────────────────────────────────────");

            for (String[] row : divergenceRows) {
                String name = row[0];
                int behind = "?".equals(row[2]) ? -1 : Integer.parseInt(row[2]);
                int ahead = "?".equals(row[3]) ? -1 : Integer.parseInt(row[3]);

                String marker;
                String detail;
                if (behind == 0) {
                    marker = Ansi.green("✓");
                    detail = "up to date";
                } else if (behind < 0) {
                    marker = "?";
                    detail = "unknown";
                } else if (behind >= warnThreshold) {
                    marker = Ansi.red("⚠");
                    detail = behind + " commit(s) behind main, "
                            + ahead + " ahead — consider ws:update-feature";
                } else {
                    marker = Ansi.yellow("·");
                    detail = behind + " commit(s) behind main, " + ahead + " ahead";
                }

                getLog().info(String.format("  %-24s %s %s",
                        name, marker, detail));
            }
        }

        // ── Section 5: Cascade ──────────────────────────────────────
        List<String[]> cascadeRows = new ArrayList<>();
        if (!modifiedComponents.isEmpty()) {
            Set<String> allAffected = new LinkedHashSet<>();
            for (String sub : modifiedComponents) {
                allAffected.addAll(graph.cascade(sub));
            }
            allAffected.removeAll(modifiedComponents);

            if (!allAffected.isEmpty()) {
                getLog().info("");
                getLog().info("  Cascade — components needing rebuild:");
                getLog().info("  ──────────────────────────────────────────────────────");

                List<String> buildOrder = graph.topologicalSort(
                        new LinkedHashSet<>(allAffected));
                for (String name : buildOrder) {
                    List<String> triggeredBy = new ArrayList<>();
                    Subproject sub = graph.manifest().subprojects().get(name);
                    if (sub != null) {
                        for (Dependency dep : sub.dependsOn()) {
                            if (modifiedComponents.contains(dep.subproject())
                                    || allAffected.contains(dep.subproject())) {
                                triggeredBy.add(dep.subproject());
                            }
                        }
                    }
                    String triggers = String.join(", ", triggeredBy);
                    getLog().info("    " + name + " ← " + triggers);
                    cascadeRows.add(new String[]{name, triggers});
                }
            }
        }

        getLog().info("");

        // Structured markdown report
        writeReport(WsGoal.OVERVIEW, buildMarkdownReport(
                errors, graphRows, statusRows, divergenceRows, cascadeRows,
                cloned, notCloned, modifiedComponents.size(), graph));
    }

    // ── Markdown report ─────────────────────────────────────────────

    private String buildMarkdownReport(List<String> manifestErrors,
                                        List<String[]> graphRows,
                                        List<String[]> statusRows,
                                        List<String[]> divergenceRows,
                                        List<String[]> cascadeRows,
                                        int cloned, int notCloned,
                                        int modified,
                                        WorkspaceGraph graph) {
        var sb = new StringBuilder();

        // Manifest
        if (manifestErrors.isEmpty()) {
            sb.append("**Manifest:** consistent.\n\n");
        } else {
            sb.append("**Manifest:** ").append(manifestErrors.size())
              .append(" error(s).\n\n");
        }

        // Graph table
        sb.append("### Dependency Graph\n\n");
        sb.append("| # | Subproject | Type | Dependencies |\n");
        sb.append("|---|-----------|------|--------------|\n");
        for (String[] row : graphRows) {
            sb.append("| ").append(row[0])
              .append(" | ").append(row[1])
              .append(" | ").append(row[2])
              .append(" | ").append(row[3])
              .append(" |\n");
        }

        // Mermaid graph
        sb.append('\n');
        sb.append(buildMermaidGraph(graph));

        // Status table
        sb.append("\n### Status\n\n");
        sb.append(cloned).append(" cloned, ").append(notCloned)
          .append(" not cloned, ").append(modified).append(" with uncommitted changes.\n\n");
        sb.append("| Subproject | Branch | SHA | Status |\n");
        sb.append("|-----------|--------|-----|--------|\n");
        for (String[] row : statusRows) {
            sb.append("| ").append(row[0])
              .append(" | ").append(row[1])
              .append(" | ").append(row[2])
              .append(" | ").append(row[3])
              .append(" |\n");
        }

        // Divergence
        if (!divergenceRows.isEmpty()) {
            sb.append("\n### Feature Branch Divergence\n\n");
            sb.append("| Subproject | Branch | Behind | Ahead | Status |\n");
            sb.append("|-----------|--------|--------|-------|--------|\n");
            for (String[] row : divergenceRows) {
                sb.append("| ").append(row[0])
                  .append(" | ").append(row[1])
                  .append(" | ").append(row[2])
                  .append(" | ").append(row[3])
                  .append(" | ").append(row[4])
                  .append(" |\n");
            }
        }

        // Cascade
        if (!cascadeRows.isEmpty()) {
            sb.append("\n### Cascade\n\n");
            sb.append("| Subproject | Triggered By |\n");
            sb.append("|-----------|-------------|\n");
            for (String[] row : cascadeRows) {
                sb.append("| ").append(row[0])
                  .append(" | ").append(row[1])
                  .append(" |\n");
            }
        }

        return sb.toString();
    }

    // ── Mermaid graph ───────────────────────────────────────────────

    private String buildMermaidGraph(WorkspaceGraph graph) {
        List<String> sorted = graph.topologicalSort();
        var sb = new StringBuilder();
        sb.append("```mermaid\ngraph TD\n");

        for (String name : sorted) {
            String id = name.replace("-", "_");
            sb.append("    ").append(id)
              .append("[\"").append(name).append("\"]\n");
        }
        sb.append('\n');

        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            String sourceId = name.replace("-", "_");
            for (Dependency dep : sub.dependsOn()) {
                String targetId = dep.subproject().replace("-", "_");
                if ("content".equals(dep.relationship())) {
                    sb.append("    ").append(sourceId)
                      .append(" -.-> ").append(targetId).append('\n');
                } else {
                    sb.append("    ").append(sourceId)
                      .append(" --> ").append(targetId).append('\n');
                }
            }
        }

        sb.append("```\n");
        return sb.toString();
    }

    // ── DOT output ──────────────────────────────────────────────────

    private void printDot(WorkspaceGraph graph) {
        String dot = GraphWorkspaceMojo.buildDotGraph("workspace",
                graph.manifest().subprojects().values().stream()
                        .collect(Collectors.toMap(Subproject::name,
                                c -> c.type().yamlName())),
                graph.manifest().subprojects().values().stream()
                        .filter(c -> !c.dependsOn().isEmpty())
                        .collect(Collectors.toMap(Subproject::name,
                                c -> c.dependsOn().stream()
                                        .map(d -> new String[]{d.subproject(), d.relationship()})
                                        .toList())));
        for (String line : dot.split("\n")) {
            getLog().info(line);
        }
    }
}
