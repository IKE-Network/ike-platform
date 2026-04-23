package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GraphWorkspaceMojo#buildDotGraph}.
 *
 * <p>The mojo no longer type-colors nodes (subprojects are all Maven
 * projects — there is no type distinction). These tests exercise the
 * pure DOT-emission function against subproject names and edges only.
 */
class GraphSupportTest {

    // ── buildDotGraph ────────────────────────────────────────────────

    @Test
    void buildDotGraph_emptyGraph() {
        String dot = GraphWorkspaceMojo.buildDotGraph(
                "test", List.of(), Map.of());

        assertThat(dot)
                .startsWith("digraph test {")
                .contains("rankdir=BT")
                .contains("node [shape=box")
                .endsWith("}\n");
    }

    @Test
    void buildDotGraph_singleNode_noEdges() {
        List<String> names = List.of("ike-platform");

        String dot = GraphWorkspaceMojo.buildDotGraph(
                "ws", names, Map.of());

        assertThat(dot)
                .contains("\"ike-platform\"");
    }

    @Test
    void buildDotGraph_edgesPresent() {
        List<String> names = List.of("app", "lib");

        Map<String, List<String[]>> edges = Map.of(
                "app", List.<String[]>of(new String[]{"lib", "build"}));

        String dot = GraphWorkspaceMojo.buildDotGraph("ws", names, edges);

        assertThat(dot)
                .contains("\"app\" -> \"lib\"");
    }

    @Test
    void buildDotGraph_contentRelationship_dashed() {
        List<String> names = List.of("guide", "topics");

        Map<String, List<String[]>> edges = Map.of(
                "guide", List.<String[]>of(new String[]{"topics", "content"}));

        String dot = GraphWorkspaceMojo.buildDotGraph("ws", names, edges);

        assertThat(dot)
                .contains("\"guide\" -> \"topics\" [style=dashed]");
    }

    @Test
    void buildDotGraph_buildRelationship_noStyle() {
        List<String> names = List.of("a", "b");

        Map<String, List<String[]>> edges = Map.of(
                "a", List.<String[]>of(new String[]{"b", "build"}));

        String dot = GraphWorkspaceMojo.buildDotGraph("ws", names, edges);

        // Edge should not have [style=dashed]
        assertThat(dot)
                .contains("\"a\" -> \"b\";")
                .doesNotContain("\"a\" -> \"b\" [style=dashed]");
    }

    @Test
    void buildDotGraph_multipleEdgesFromOneNode() {
        List<String> names = List.of("app", "lib1", "lib2");

        Map<String, List<String[]>> edges = new LinkedHashMap<>();
        edges.put("app", List.of(
                new String[]{"lib1", "build"},
                new String[]{"lib2", "content"}));

        String dot = GraphWorkspaceMojo.buildDotGraph("ws", names, edges);

        assertThat(dot)
                .contains("\"app\" -> \"lib1\"")
                .contains("\"app\" -> \"lib2\"");
    }
}
