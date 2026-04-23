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
@Mojo(name = "graph", projectRequired = false)
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

        // Append Mermaid graph to ws-report.md
        writeReport(WsGoal.GRAPH, buildMermaidGraph(graph));
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

            getLog().info(String.format("  %2d. %-28s [%s]",
                    i + 1, name, sub.type().yamlName()));

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
        // Build data structures for the pure function
        Map<String, String> componentTypes = new LinkedHashMap<>();
        for (Subproject sub : graph.manifest().subprojects().values()) {
            componentTypes.put(sub.name(), sub.type().yamlName());
        }

        Map<String, List<String[]>> edges = new LinkedHashMap<>();
        for (Subproject sub : graph.manifest().subprojects().values()) {
            List<String[]> compEdges = sub.dependsOn().stream()
                    .map(dep -> new String[]{dep.subproject(), dep.relationship()})
                    .toList();
            if (!compEdges.isEmpty()) {
                edges.put(sub.name(), compEdges);
            }
        }

        String dot = buildDotGraph("workspace", componentTypes, edges);
        for (String line : dot.split("\n")) {
            getLog().info(line);
        }
    }

    /**
     * Build a Mermaid graph block for the markdown report.
     */
    private String buildMermaidGraph(WorkspaceGraph graph) {
        List<String> sorted = graph.topologicalSort();
        var sb = new StringBuilder();
        sb.append("```mermaid\ngraph TD\n");

        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            String id = mermaidId(name);
            sb.append("    ").append(id)
              .append("[\"").append(name).append("\"]\n");
        }

        sb.append('\n');

        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            String sourceId = mermaidId(name);
            for (Dependency dep : sub.dependsOn()) {
                String targetId = mermaidId(dep.subproject());
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

    private static String mermaidId(String name) {
        return name.replace("-", "_");
    }

    // ── DOT generation (pure, static, testable) ─────────────────────

    /**
     * Return the fill color for a subproject type name.
     *
     * @param typeName subproject type (e.g., "infrastructure", "software")
     * @return hex color string
     */
    public static String componentColor(String typeName) {
        return switch (typeName) {
            case "infrastructure"   -> "#e8d5b7";
            case "software"         -> "#b7d5e8";
            case "document"         -> "#b7e8c4";
            case "knowledge-source" -> "#e8b7d5";
            case "template"         -> "#d5d5d5";
            default                 -> "#ffffff";
        };
    }

    /**
     * Build a Graphviz DOT graph from subproject types and edges.
     *
     * <p>This is a pure function with no workspace-model dependencies,
     * suitable for direct unit testing.
     *
     * @param title          graph name used in {@code digraph <title>}
     * @param componentTypes map of subproject name to type name
     * @param edges          map of source subproject to list of
     *                       {@code [target, relationship]} pairs
     * @return complete DOT source
     */
    public static String buildDotGraph(String title,
                                        Map<String, String> componentTypes,
                                        Map<String, List<String[]>> edges) {
        StringBuilder dot = new StringBuilder(1024);
        dot.append("digraph ").append(title).append(" {\n");
        dot.append("    rankdir=BT;\n");
        dot.append("    node [shape=box, style=rounded, fontname=\"Helvetica\"];\n");
        dot.append("\n");

        // Node declarations with colors
        for (var entry : componentTypes.entrySet()) {
            String subName = entry.getKey();
            String color = componentColor(entry.getValue());
            dot.append("    \"").append(subName)
               .append("\" [fillcolor=\"").append(color)
               .append("\", style=\"rounded,filled\"];\n");
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
