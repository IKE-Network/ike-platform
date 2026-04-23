package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the pure functions extracted from {@link GraphWorkspaceMojo}:
 * {@code componentColor()} and {@code buildDotGraph()}.
 */
class GraphSupportTest {

    // ── componentColor ───────────────────────────────────────────────

    @Test
    void componentColor_infrastructure() {
        assertThat(GraphWorkspaceMojo.componentColor("infrastructure"))
                .isEqualTo("#e8d5b7");
    }

    @Test
    void componentColor_software() {
        assertThat(GraphWorkspaceMojo.componentColor("software"))
                .isEqualTo("#b7d5e8");
    }

    @Test
    void componentColor_document() {
        assertThat(GraphWorkspaceMojo.componentColor("document"))
                .isEqualTo("#b7e8c4");
    }

    @Test
    void componentColor_knowledgeSource() {
        assertThat(GraphWorkspaceMojo.componentColor("knowledge-source"))
                .isEqualTo("#e8b7d5");
    }

    @Test
    void componentColor_template() {
        assertThat(GraphWorkspaceMojo.componentColor("template"))
                .isEqualTo("#d5d5d5");
    }

    @Test
    void componentColor_unknown_white() {
        assertThat(GraphWorkspaceMojo.componentColor("something-else"))
                .isEqualTo("#ffffff");
    }

    // ── buildDotGraph ────────────────────────────────────────────────

    @Test
    void buildDotGraph_emptyGraph() {
        String dot = GraphWorkspaceMojo.buildDotGraph(
                "test", Map.of(), Map.of());

        assertThat(dot)
                .startsWith("digraph test {")
                .contains("rankdir=BT")
                .contains("node [shape=box")
                .endsWith("}\n");
    }

    @Test
    void buildDotGraph_singleNode_noEdges() {
        Map<String, String> types = Map.of("ike-platform", "infrastructure");

        String dot = GraphWorkspaceMojo.buildDotGraph(
                "ws", types, Map.of());

        assertThat(dot)
                .contains("\"ike-platform\" [fillcolor=\"#e8d5b7\"")
                .contains("style=\"rounded,filled\"");
    }

    @Test
    void buildDotGraph_edgesPresent() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("app", "software");
        types.put("lib", "software");

        Map<String, List<String[]>> edges = Map.of(
                "app", List.<String[]>of(new String[]{"lib", "build"}));

        String dot = GraphWorkspaceMojo.buildDotGraph("ws", types, edges);

        assertThat(dot)
                .contains("\"app\" -> \"lib\"");
    }

    @Test
    void buildDotGraph_contentRelationship_dashed() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("guide", "document");
        types.put("topics", "document");

        Map<String, List<String[]>> edges = Map.of(
                "guide", List.<String[]>of(new String[]{"topics", "content"}));

        String dot = GraphWorkspaceMojo.buildDotGraph("ws", types, edges);

        assertThat(dot)
                .contains("\"guide\" -> \"topics\" [style=dashed]");
    }

    @Test
    void buildDotGraph_buildRelationship_noStyle() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("a", "software");
        types.put("b", "software");

        Map<String, List<String[]>> edges = Map.of(
                "a", List.<String[]>of(new String[]{"b", "build"}));

        String dot = GraphWorkspaceMojo.buildDotGraph("ws", types, edges);

        // Edge should not have [style=dashed]
        assertThat(dot)
                .contains("\"a\" -> \"b\";")
                .doesNotContain("\"a\" -> \"b\" [style=dashed]");
    }

    @Test
    void buildDotGraph_multipleEdgesFromOneNode() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("app", "software");
        types.put("lib1", "software");
        types.put("lib2", "software");

        Map<String, List<String[]>> edges = Map.of(
                "app", List.of(
                        new String[]{"lib1", "build"},
                        new String[]{"lib2", "content"}));

        String dot = GraphWorkspaceMojo.buildDotGraph("ws", types, edges);

        assertThat(dot)
                .contains("\"app\" -> \"lib1\"")
                .contains("\"app\" -> \"lib2\"");
    }
}
