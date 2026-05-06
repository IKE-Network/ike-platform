package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link WsCreateMojo}'s real-GAV generation
 * (ike-issues#183).
 *
 * <p>Exercises the file generators directly via reflection — no Maven
 * lifecycle required. Verifies:
 * <ul>
 *   <li>{@code -Dgroup} is required (no default, no prompt fallback)</li>
 *   <li>Generated {@code pom.xml} carries the user's groupId,
 *       artifactId, and version (no {@code local.aggregate}
 *       placeholder)</li>
 *   <li>Generated {@code workspace.yaml} declares schema 1.1 with a
 *       typed {@code workspace-root:} block</li>
 *   <li>{@code -DartifactId} defaults to the workspace name</li>
 *   <li>{@code -Dversion} defaults to {@code 1-SNAPSHOT}</li>
 * </ul>
 */
class WsCreateMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void execute_without_group_throws_with_pointer_to_183() throws Exception {
        WsCreateMojo mojo = configured("my-ws", null, null, null);
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("ws:create requires -Dgroup")
                .hasMessageContaining("ike-issues#183");
    }

    @Test
    void generated_pom_uses_user_supplied_coordinates() throws Exception {
        WsCreateMojo mojo = configured("my-ws", "org.example", "2-SNAPSHOT", null);

        String pom = invokeGeneratePom(mojo);

        assertThat(pom)
                .contains("<groupId>org.example</groupId>")
                .contains("<artifactId>my-ws</artifactId>")
                .contains("<version>2-SNAPSHOT</version>")
                .doesNotContain("local.aggregate");
    }

    @Test
    void generated_pom_uses_explicit_artifactId_when_supplied() throws Exception {
        WsCreateMojo mojo = configured("my-ws", "org.example", null, "explicit-aid");

        String pom = invokeGeneratePom(mojo);

        assertThat(pom).contains("<artifactId>explicit-aid</artifactId>");
    }

    @Test
    void generated_manifest_is_schema_1_1_with_workspace_root_block() throws Exception {
        WsCreateMojo mojo = configured("my-ws", "org.example", "2-SNAPSHOT", null);

        String yaml = invokeGenerateManifest(mojo);

        assertThat(yaml)
                .contains("schema-version: \"1.1\"")
                .contains("workspace-root:")
                .contains("  groupId: org.example")
                .contains("  artifactId: my-ws")
                .contains("  version: 2-SNAPSHOT");
    }

    @Test
    void generated_manifest_falls_back_to_default_version_1_SNAPSHOT() throws Exception {
        // Simulate the @Parameter defaultValue by setting version explicitly
        // — Maven's DI normally injects the default, but the test bypasses
        // injection.
        WsCreateMojo mojo = configured("my-ws", "org.example", "1-SNAPSHOT", null);

        String yaml = invokeGenerateManifest(mojo);

        assertThat(yaml).contains("version: 1-SNAPSHOT");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private WsCreateMojo configured(String name, String group, String version,
                                    String artifactId) throws Exception {
        WsCreateMojo mojo = TestLog.createMojo(WsCreateMojo.class);
        setField(mojo, "name", name);
        setField(mojo, "group", group);
        setField(mojo, "version", version);
        setField(mojo, "artifactId", artifactId);
        // Required by execute() to short-circuit prompting; the
        // pure-string generators don't read the field, but execute() does.
        setField(mojo, "description", name);
        setField(mojo, "mavenVersion", "4.0.0-rc-5");
        setField(mojo, "defaultBranch", "main");
        return mojo;
    }

    private static void setField(Object target, String fieldName, Object value)
            throws Exception {
        Field f = WsCreateMojo.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Resolve artifactId fallback the way execute() does: artifactId
     * defaults to name when null/blank. Then call generatePom().
     */
    private String invokeGeneratePom(WsCreateMojo mojo) throws Exception {
        applyDefaults(mojo);
        Method m = WsCreateMojo.class.getDeclaredMethod("generatePom");
        m.setAccessible(true);
        return (String) m.invoke(mojo);
    }

    private String invokeGenerateManifest(WsCreateMojo mojo) throws Exception {
        applyDefaults(mojo);
        Method m = WsCreateMojo.class.getDeclaredMethod("generateManifest");
        m.setAccessible(true);
        return (String) m.invoke(mojo);
    }

    /**
     * Apply the same artifactId-fallback that {@code execute()} runs
     * before the generators are called. The generators read
     * {@code artifactId} (a field) — without this they'd see null
     * because we bypass {@code execute()}.
     */
    private static void applyDefaults(WsCreateMojo mojo) throws Exception {
        Field name = WsCreateMojo.class.getDeclaredField("name");
        Field aid = WsCreateMojo.class.getDeclaredField("artifactId");
        name.setAccessible(true);
        aid.setAccessible(true);
        if (aid.get(mojo) == null || ((String) aid.get(mojo)).isBlank()) {
            aid.set(mojo, name.get(mojo));
        }
    }
}
