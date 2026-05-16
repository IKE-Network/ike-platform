package network.ike.plugin.ws;

import network.ike.workspace.Subproject;
import network.ike.workspace.Dependency;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Print the workspace dependency graph.
 *
 * <p>Displays all subprojects in topological order with their
 * dependencies. Optionally outputs DOT format for Graphviz rendering.
 *
 * <pre>{@code
 * mvn ike:graph
 * mvn ike:graph -Dformat=dot
 * }</pre>
 */
@Mojo(name = "graph", projectRequired = false, aggregator = true)
public class GraphWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * Output format: "text" (default) or "dot" (Graphviz DOT).
     */
    @Parameter(property = "format", defaultValue = "text")
    String format;

    /** Creates this goal instance. */
    public GraphWorkspaceMojo() {}

    @Override
    public void execute() throws MojoException {
        WorkspaceGraph graph = loadGraph();

        if ("dot".equalsIgnoreCase(format)) {
            printDot(graph);
        } else {
            printText(graph);
        }

        // Append the GraphViz dependency graph to the report.
        // IKE-DIAGRAMS.md mandates GraphViz for dependency graphs;
        // Mermaid is discouraged (IKE-Network/ike-issues#406).
        writeReport(WsGoal.GRAPH, buildDotReportBlock(graph));
    }

    private void printText(WorkspaceGraph graph) {
        getLog().info("");
        getLog().info(header("Dependency Graph"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        List<String> sorted = graph.topologicalSort();

        for (int i = 0; i < sorted.size(); i++) {
            String name = sorted.get(i);
            Subproject sub = graph.manifest().subprojects().get(name);

            getLog().info(String.format("  %2d. %s", i + 1, name));

            if (!sub.dependsOn().isEmpty()) {
                for (int j = 0; j < sub.dependsOn().size(); j++) {
                    Dependency dep = sub.dependsOn().get(j);
                    boolean last = (j == sub.dependsOn().size() - 1);
                    String connector = last ? "└─" : "├─";
                    getLog().info(String.format("        %s %s (%s)",
                            connector, dep.subproject(), dep.relationship()));
                    // Show transitive dependencies
                    Subproject depComp = graph.manifest().subprojects()
                            .get(dep.subproject());
                    if (depComp != null && !depComp.dependsOn().isEmpty()) {
                        String prefix = last ? "           " : "        │  ";
                        printTransitiveDeps(graph, depComp, prefix, name);
                    }
                }
            }
        }

        getLog().info("");
        getLog().info("  " + sorted.size() + " components in dependency order.");
        getLog().info("");
    }

    /**
     * Recursively print transitive dependencies with tree indentation.
     *
     * @param graph   the workspace graph
     * @param sub    the subproject whose dependencies to print
     * @param prefix  indentation prefix for this level
     * @param root    the root subproject name (to prevent cycles)
     */
    private void printTransitiveDeps(WorkspaceGraph graph, Subproject sub,
                                      String prefix, String root) {
        for (int i = 0; i < sub.dependsOn().size(); i++) {
            Dependency dep = sub.dependsOn().get(i);
            // Prevent infinite recursion if there's a cycle
            if (dep.subproject().equals(root)) continue;

            boolean last = (i == sub.dependsOn().size() - 1);
            String connector = last ? "└─" : "├─";
            getLog().info(String.format("%s%s %s (%s)",
                    prefix, connector, dep.subproject(), dep.relationship()));

            Subproject depComp = graph.manifest().subprojects()
                    .get(dep.subproject());
            if (depComp != null && !depComp.dependsOn().isEmpty()) {
                String childPrefix = prefix + (last ? "   " : "│  ");
                printTransitiveDeps(graph, depComp, childPrefix, root);
            }
        }
    }

    private void printDot(WorkspaceGraph graph) {
        for (String line : dotFromGraph(graph).split("\n")) {
            getLog().info(line);
        }
    }

    /**
     * Build Graphviz DOT source for a whole workspace graph.
     *
     * <p>Extracts the subproject names and dependency edges from the
     * {@link WorkspaceGraph} and delegates to {@link #buildDotGraph}.
     * Shared by the {@code -Dformat=dot} console output (here and in
     * {@code ws:overview}) and by {@link #buildDotReportBlock}.
     *
     * @param graph the workspace graph
     * @return complete DOT source (a {@code digraph} block)
     */
    static String dotFromGraph(WorkspaceGraph graph) {
        List<String> subprojectNames = graph.manifest().subprojects()
                .values().stream()
                .map(Subproject::name)
                .toList();

        Map<String, List<String[]>> edges = new LinkedHashMap<>();
        for (Subproject sub : graph.manifest().subprojects().values()) {
            List<String[]> compEdges = sub.dependsOn().stream()
                    .map(dep -> new String[]{dep.subproject(), dep.relationship()})
                    .toList();
            if (!compEdges.isEmpty()) {
                edges.put(sub.name(), compEdges);
            }
        }
        return buildDotGraph("workspace", subprojectNames, edges);
    }

    /**
     * Build a fenced Graphviz DOT block for a markdown report.
     *
     * <p>IKE-DIAGRAMS.md mandates GraphViz for dependency graphs and
     * IKE-DOC.md discourages Mermaid; the workspace reports embed the
     * graph as a {@code ```dot} block accordingly
     * (IKE-Network/ike-issues#406).
     *
     * @param graph the workspace graph
     * @return a fenced {@code ```dot} block, newline-terminated
     */
    static String buildDotReportBlock(WorkspaceGraph graph) {
        return "```dot\n" + dotFromGraph(graph) + "```\n";
    }

    // ── DOT generation (pure, static, testable) ─────────────────────

    /**
     * Build a Graphviz DOT graph from subproject names and edges.
     *
     * <p>This is a pure function with no workspace-model dependencies,
     * suitable for direct unit testing.
     *
     * @param title           graph name used in {@code digraph <title>}
     * @param subprojectNames names of subprojects to include as nodes
     * @param edges           map of source subproject to list of
     *                        {@code [target, relationship]} pairs
     * @return complete DOT source
     */
    public static String buildDotGraph(String title,
                                        List<String> subprojectNames,
                                        Map<String, List<String[]>> edges) {
        StringBuilder dot = new StringBuilder(1024);
        dot.append("digraph ").append(title).append(" {\n");
        dot.append("    rankdir=BT;\n");
        dot.append("    node [shape=box, style=rounded, fontname=\"Helvetica\"];\n");
        dot.append("\n");

        // Node declarations
        for (String subName : subprojectNames) {
            dot.append("    \"").append(subName).append("\";\n");
        }

        dot.append("\n");

        // Edges
        for (var entry : edges.entrySet()) {
            String source = entry.getKey();
            for (String[] edge : entry.getValue()) {
                String target = edge[0];
                String relationship = edge[1];
                String style = "content".equals(relationship)
                        ? " [style=dashed]" : "";
                dot.append("    \"").append(source).append("\" -> \"")
                   .append(target).append("\"").append(style).append(";\n");
            }
        }

        dot.append("}\n");
        return dot.toString();
    }
}
