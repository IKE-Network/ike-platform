package network.ike.plugin.ws;

import network.ike.workspace.Subproject;
import network.ike.workspace.Dependency;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.util.List;

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
        writeReport(WsGoal.GRAPH,
                DotGraphSupport.buildDotReportBlock(graph));
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
        for (String line
                : DotGraphSupport.dotFromGraph(graph).split("\n")) {
            getLog().info(line);
        }
    }
}
