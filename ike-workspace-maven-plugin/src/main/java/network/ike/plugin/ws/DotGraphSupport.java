package network.ike.plugin.ws;

import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Graphviz DOT rendering for the workspace dependency graph.
 *
 * <p>Pure, stateless helpers — no mojo state, no I/O. Shared by
 * {@link GraphWorkspaceMojo} ({@code ws:graph}) and
 * {@link OverviewWorkspaceMojo} ({@code ws:overview}), both of which
 * emit the graph as DOT on the console ({@code -Dformat=dot}) and as a
 * fenced {@code ```dot} block in their markdown reports.
 *
 * <p>Extracted from {@code GraphWorkspaceMojo} so diagram rendering
 * lives in a support class rather than on a mojo, and so
 * {@code ws:overview} no longer reaches into a sibling mojo statically
 * (IKE-Network/ike-issues#408, closing the #406 leftover).
 *
 * <p>IKE-DIAGRAMS.md mandates GraphViz for dependency graphs and
 * IKE-DOC.md discourages Mermaid; this class is the single place the
 * workspace graph becomes DOT.
 */
final class DotGraphSupport {

    private DotGraphSupport() {
    }

    /**
     * Build Graphviz DOT source for a whole workspace graph.
     *
     * <p>Extracts the subproject names and dependency edges from the
     * {@link WorkspaceGraph} and delegates to {@link #buildDotGraph}.
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
                    .map(dep -> new String[]{
                            dep.subproject(), dep.relationship()})
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
     * <p>The workspace reports embed the graph as a {@code ```dot}
     * block (IKE-Network/ike-issues#406).
     *
     * @param graph the workspace graph
     * @return a fenced {@code ```dot} block, newline-terminated
     */
    static String buildDotReportBlock(WorkspaceGraph graph) {
        return "```dot\n" + dotFromGraph(graph) + "```\n";
    }

    /**
     * Build the dependency-graph markdown section for a workspace
     * report. Renders a Kroki-served SVG image for any markdown
     * viewer that loads images (GitHub web, VS Code preview, Claude
     * Code's session viewer) and keeps the DOT source under a
     * collapsible {@code <details>} block for the IntelliJ Kroki /
     * Graphviz IDE plugins and for offline source-of-truth. Replaces
     * the bare {@code ```dot} fence that most markdown renderers
     * showed as a wall of unformatted source (#533).
     *
     * @param graph     the workspace graph
     * @param krokiBase Kroki server base URL (e.g. {@code https://kroki.io}),
     *                  trailing slash optional. When {@code null} or blank
     *                  the rendered image is omitted and only the DOT
     *                  block remains, matching the pre-#533 shape — useful
     *                  for fully air-gapped runs.
     * @return markdown for the graph section, newline-terminated
     */
    static String buildDotReportSection(WorkspaceGraph graph, String krokiBase) {
        String dot = dotFromGraph(graph);
        StringBuilder out = new StringBuilder();
        if (krokiBase != null && !krokiBase.isBlank()) {
            out.append("![workspace dependency graph](")
                    .append(krokiSvgUrl(dot, krokiBase))
                    .append(")\n\n");
            out.append("<details><summary>DOT source</summary>\n\n");
            out.append("```dot\n").append(dot).append("```\n\n");
            out.append("</details>\n");
        } else {
            out.append("```dot\n").append(dot).append("```\n");
        }
        return out.toString();
    }

    /**
     * Encode a DOT source string as a Kroki SVG image URL.
     *
     * <p>Kroki accepts the diagram source as a zlib-deflated, URL-safe-
     * base64-encoded path segment, so the URL itself carries the
     * complete diagram — no client-side state, no server-side
     * persistence. See <a href="https://docs.kroki.io/kroki/setup/encode-diagram/">
     * the Kroki encoding docs</a>.
     *
     * @param dot       Graphviz DOT source
     * @param krokiBase Kroki server base URL (trailing slash optional);
     *                  e.g. {@code https://kroki.io} or an internal
     *                  self-hosted endpoint
     * @return full image URL ready to drop into a markdown
     *         {@code ![alt](...)} link
     */
    static String krokiSvgUrl(String dot, String krokiBase) {
        String base = krokiBase.endsWith("/")
                ? krokiBase.substring(0, krokiBase.length() - 1)
                : krokiBase;
        return base + "/graphviz/svg/" + krokiEncode(dot);
    }

    /**
     * Encode a diagram source string the way Kroki expects: zlib
     * deflate at best compression, then URL-safe base64 without
     * padding.
     *
     * @param source diagram source text
     * @return encoded path segment for the Kroki URL
     */
    static String krokiEncode(String source) {
        byte[] input = source.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(out.toByteArray());
    }

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
    static String buildDotGraph(String title,
                                List<String> subprojectNames,
                                Map<String, List<String[]>> edges) {
        StringBuilder dot = new StringBuilder(1024);
        dot.append("digraph ").append(title).append(" {\n");
        dot.append("    rankdir=BT;\n");
        dot.append("    node [shape=box, style=rounded, "
                + "fontname=\"Helvetica\"];\n");
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
                   .append(target).append("\"").append(style)
                   .append(";\n");
            }
        }

        dot.append("}\n");
        return dot.toString();
    }
}
