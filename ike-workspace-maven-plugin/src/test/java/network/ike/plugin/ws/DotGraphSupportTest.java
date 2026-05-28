package network.ike.plugin.ws;

import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DotGraphSupport}.
 *
 * <p>DOT rendering does not type-color nodes (subprojects are all
 * Maven projects — there is no type distinction). These tests
 * exercise the pure DOT-emission functions against subproject names
 * and edges only.
 */
class DotGraphSupportTest {

    // ── buildDotGraph ────────────────────────────────────────────────

    @Test
    void buildDotGraph_emptyGraph() {
        String dot = DotGraphSupport.buildDotGraph(
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

        String dot = DotGraphSupport.buildDotGraph(
                "ws", names, Map.of());

        assertThat(dot)
                .contains("\"ike-platform\"");
    }

    @Test
    void buildDotGraph_edgesPresent() {
        List<String> names = List.of("app", "lib");

        Map<String, List<String[]>> edges = Map.of(
                "app", List.<String[]>of(new String[]{"lib", "build"}));

        String dot = DotGraphSupport.buildDotGraph("ws", names, edges);

        assertThat(dot)
                .contains("\"app\" -> \"lib\"");
    }

    @Test
    void buildDotGraph_contentRelationship_dashed() {
        List<String> names = List.of("guide", "topics");

        Map<String, List<String[]>> edges = Map.of(
                "guide", List.<String[]>of(new String[]{"topics", "content"}));

        String dot = DotGraphSupport.buildDotGraph("ws", names, edges);

        assertThat(dot)
                .contains("\"guide\" -> \"topics\" [style=dashed]");
    }

    @Test
    void buildDotGraph_buildRelationship_noStyle() {
        List<String> names = List.of("a", "b");

        Map<String, List<String[]>> edges = Map.of(
                "a", List.<String[]>of(new String[]{"b", "build"}));

        String dot = DotGraphSupport.buildDotGraph("ws", names, edges);

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

        String dot = DotGraphSupport.buildDotGraph("ws", names, edges);

        assertThat(dot)
                .contains("\"app\" -> \"lib1\"")
                .contains("\"app\" -> \"lib2\"");
    }

    // ── Kroki encoding + report section (#533) ────────────────────

    @Test
    void krokiEncode_roundTripsThroughDeflateBase64() throws Exception {
        String dot = "digraph G { a -> b; }\n";
        String encoded = DotGraphSupport.krokiEncode(dot);

        // URL-safe base64 (no '+' or '/' or '=')
        assertThat(encoded)
                .as("Kroki path segments use URL-safe base64 without padding")
                .doesNotContain("+")
                .doesNotContain("/")
                .doesNotContain("=");

        // Decode and inflate must yield the original DOT verbatim.
        byte[] raw = java.util.Base64.getUrlDecoder().decode(encoded);
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        inflater.setInput(raw);
        byte[] out = new byte[1024];
        int n = inflater.inflate(out);
        inflater.end();
        String decoded = new String(out, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(decoded)
                .as("DOT source must round-trip through deflate + base64")
                .isEqualTo(dot);
    }

    @Test
    void krokiSvgUrl_includesGraphvizPathAndEncodedSource() {
        String url = DotGraphSupport.krokiSvgUrl(
                "digraph G {}", "https://kroki.io");
        assertThat(url)
                .startsWith("https://kroki.io/graphviz/svg/")
                .doesNotContain("//graphviz");
    }

    @Test
    void krokiSvgUrl_stripsTrailingSlashFromBase() {
        String withSlash = DotGraphSupport.krokiSvgUrl(
                "digraph G {}", "https://kroki.example.com/");
        String withoutSlash = DotGraphSupport.krokiSvgUrl(
                "digraph G {}", "https://kroki.example.com");
        assertThat(withSlash).isEqualTo(withoutSlash);
    }

    @Test
    void buildDotReportSection_withKroki_emitsImageAndCollapsedSource() {
        Manifest manifest = ManifestReader.read(new StringReader("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  alpha:
                    repo: https://example.com/alpha.git
                    version: "1.0.0"
                """));

        String section = DotGraphSupport.buildDotReportSection(
                new WorkspaceGraph(manifest), "https://kroki.io");

        assertThat(section)
                .as("Kroki image line so GitHub/IDE/markdown viewers render the graph")
                .contains("![workspace dependency graph](https://kroki.io/graphviz/svg/");
        assertThat(section)
                .as("source remains accessible to IDE plugins and offline readers")
                .contains("<details><summary>DOT source</summary>")
                .contains("```dot\n")
                .contains("</details>");
    }

    @Test
    void buildDotReportSection_emptyKroki_falsBackToBareDotBlock() {
        Manifest manifest = ManifestReader.read(new StringReader("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  alpha:
                    repo: https://example.com/alpha.git
                    version: "1.0.0"
                """));

        String empty = DotGraphSupport.buildDotReportSection(
                new WorkspaceGraph(manifest), "");
        String nullBase = DotGraphSupport.buildDotReportSection(
                new WorkspaceGraph(manifest), null);

        for (String s : List.of(empty, nullBase)) {
            assertThat(s)
                    .as("air-gapped path keeps the raw DOT block without a Kroki image")
                    .doesNotContain("kroki.io")
                    .doesNotContain("![workspace dependency graph]")
                    .startsWith("```dot\n");
        }
    }

    // ── buildDotReportBlock (IKE-Network/ike-issues#406) ─────────────

    @Test
    void buildDotReportBlock_emitsGraphvizFence_notMermaid() {
        Manifest manifest = ManifestReader.read(new StringReader("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  alpha:
                    repo: https://example.com/alpha.git
                    version: "1.0.0"
                  beta:
                    repo: https://example.com/beta.git
                    version: "1.0.0"
                    depends-on:
                      - subproject: alpha
                        relationship: build
                """));

        String block = DotGraphSupport.buildDotReportBlock(
                new WorkspaceGraph(manifest));

        // GraphViz, per IKE-DIAGRAMS.md — not Mermaid.
        assertThat(block).startsWith("```dot\n");
        assertThat(block).contains("digraph workspace {");
        assertThat(block).contains("\"beta\" -> \"alpha\"");
        assertThat(block.stripTrailing()).endsWith("```");
        assertThat(block).doesNotContain("mermaid");
        assertThat(block).doesNotContain("graph TD");
    }
}
