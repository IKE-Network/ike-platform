package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link WsAdoptRootMojo} (ike-issues#184).
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code -Dgroup} required (no default)</li>
 *   <li>POM rewrite via {@link PomRewriter#updateProjectCoordinates}
 *       (semantic LST, not regex)</li>
 *   <li>{@code workspace-root:} insertion + schema 1.0 → 1.1 bump</li>
 *   <li>Idempotent re-run (no-op when block already present)</li>
 *   <li>artifactId default — falls back to existing pom.xml artifactId</li>
 * </ul>
 */
class WsAdoptRootMojoTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void seedWorkspace() throws Exception {
        // Pre-#183 placeholder workspace
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <parent>
                        <groupId>network.ike.platform</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>15</version>
                    </parent>
                    <groupId>local.aggregate</groupId>
                    <artifactId>my-ws</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);

        Files.writeString(tempDir.resolve("workspace.yaml"), """
                # workspace.yaml — my-ws
                schema-version: "1.0"
                generated: 2026-04-01

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
    }

    @Test
    void execute_without_group_throws_with_pointer_to_184() throws Exception {
        WsAdoptRootMojo mojo = configured(null, null, null);
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("ws:adopt-root requires -Dgroup")
                .hasMessageContaining("ike-issues#184");
    }

    @Test
    void rewrites_pom_coordinates_via_pomrewriter() throws Exception {
        WsAdoptRootMojo mojo = configured("network.ike.workspace",
                "1-SNAPSHOT", null);

        mojo.execute();

        String pom = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pom)
                .contains("<groupId>network.ike.workspace</groupId>")
                .contains("<artifactId>my-ws</artifactId>")
                .contains("<version>1-SNAPSHOT</version>")
                .doesNotContain("local.aggregate")
                .doesNotContain("1.0.0-SNAPSHOT")
                // Parent block must remain untouched — only top-level
                // /project/groupId etc. should be rewritten.
                .contains("<groupId>network.ike.platform</groupId>")
                .contains("<artifactId>ike-parent</artifactId>");
    }

    @Test
    void inserts_workspace_root_block_and_bumps_schema() throws Exception {
        WsAdoptRootMojo mojo = configured("network.ike.workspace",
                "1-SNAPSHOT", null);

        mojo.execute();

        String yaml = Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
        assertThat(yaml)
                .contains("schema-version: \"1.1\"")
                .doesNotContain("schema-version: \"1.0\"")
                .contains("workspace-root:")
                .contains("  groupId: network.ike.workspace")
                .contains("  artifactId: my-ws")
                .contains("  version: 1-SNAPSHOT");

        // The original `defaults:` block and `subprojects:` key must
        // still be there — we only inserted, never deleted.
        assertThat(yaml).contains("defaults:").contains("subprojects:");
    }

    @Test
    void honors_explicit_artifactId() throws Exception {
        WsAdoptRootMojo mojo = configured("org.example", "1-SNAPSHOT",
                "alt-name");

        mojo.execute();

        assertThat(Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .contains("<artifactId>alt-name</artifactId>");
        assertThat(Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8))
                .contains("artifactId: alt-name");
    }

    @Test
    void rerun_is_idempotent_no_op() throws Exception {
        WsAdoptRootMojo first = configured("network.ike.workspace",
                "1-SNAPSHOT", null);
        first.execute();

        String pomAfterFirst = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);
        String yamlAfterFirst = Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);

        // Run again — the workspace-root block is already there.
        WsAdoptRootMojo second = configured("org.different",
                "9-SNAPSHOT", null);
        second.execute();

        // Files unchanged on the second run.
        assertThat(Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .isEqualTo(pomAfterFirst);
        assertThat(Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8))
                .isEqualTo(yamlAfterFirst);
    }

    // ── insertWorkspaceRootBlock — pure-string unit tests ────────

    @Test
    void insertion_after_generated_line() {
        String yaml = """
                schema-version: "1.0"
                generated: 2026-04-01

                defaults:
                  branch: main
                """;
        String out = WsAdoptRootMojo.insertWorkspaceRootBlock(
                yaml, "g", "a", "v");
        // workspace-root must appear after `generated:` and before
        // `defaults:`, and the schema bump must have applied.
        int generatedIdx = out.indexOf("generated:");
        int rootIdx = out.indexOf("workspace-root:");
        int defaultsIdx = out.indexOf("defaults:");
        assertThat(generatedIdx).isLessThan(rootIdx);
        assertThat(rootIdx).isLessThan(defaultsIdx);
        assertThat(out).contains("schema-version: \"1.1\"");
    }

    @Test
    void insertion_falls_back_to_schema_version_when_generated_absent() {
        String yaml = """
                schema-version: "1.0"

                defaults:
                  branch: main
                """;
        String out = WsAdoptRootMojo.insertWorkspaceRootBlock(
                yaml, "g", "a", "v");
        int schemaIdx = out.indexOf("schema-version:");
        int rootIdx = out.indexOf("workspace-root:");
        int defaultsIdx = out.indexOf("defaults:");
        assertThat(schemaIdx).isLessThan(rootIdx);
        assertThat(rootIdx).isLessThan(defaultsIdx);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private WsAdoptRootMojo configured(String group, String version,
                                       String artifactId) throws Exception {
        WsAdoptRootMojo mojo = TestLog.createMojo(WsAdoptRootMojo.class);
        setField(mojo, "group", group);
        setField(mojo, "version", version);
        setField(mojo, "artifactId", artifactId);
        // Tell the AbstractWorkspaceMojo where the manifest lives so
        // workspaceRoot() resolves correctly.
        setField(mojo, "manifest",
                tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        return mojo;
    }

    private static void setField(Object target, String fieldName, Object value)
            throws Exception {
        setField(target, fieldName, value, target.getClass());
    }

    private static void setField(Object target, String fieldName, Object value,
                                 Class<?> clazz) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
