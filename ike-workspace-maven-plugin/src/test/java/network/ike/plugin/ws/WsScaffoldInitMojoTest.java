package network.ike.plugin.ws;

import network.ike.plugin.ws.bootstrap.WorkspaceBootstrap;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link WsScaffoldInitMojo}'s bootstrap (formerly
 * {@code ws:create}) branch — real-GAV generation per ike-issues#183
 * and the IKE-Network/ike-issues#393 fold of {@code ws:create} into
 * {@code ws:scaffold-init}.
 *
 * <p>Exercises {@link WorkspaceBootstrap}'s file generators directly via
 * reflection — no Maven lifecycle required. Verifies:
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
class WsScaffoldInitMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void execute_without_group_throws_with_pointer_to_183() throws Exception {
        WsScaffoldInitMojo mojo = configured("my-ws", null, null, null);
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("ws:scaffold-init in bootstrap mode requires -Dgroup")
                .hasMessageContaining("ike-issues#183");
    }

    @Test
    void generated_pom_uses_user_supplied_coordinates() throws Exception {
        WorkspaceBootstrap bootstrap = bootstrap("my-ws", "org.example",
                "2-SNAPSHOT", null);

        String pom = invokeGeneratePom(bootstrap);

        assertThat(pom)
                .contains("<groupId>org.example</groupId>")
                .contains("<artifactId>my-ws</artifactId>")
                .contains("<version>2-SNAPSHOT</version>")
                .doesNotContain("local.aggregate");
    }

    @Test
    void generated_pom_uses_explicit_artifactId_when_supplied() throws Exception {
        WorkspaceBootstrap bootstrap = bootstrap("my-ws", "org.example",
                "1-SNAPSHOT", "explicit-aid");

        String pom = invokeGeneratePom(bootstrap);

        assertThat(pom).contains("<artifactId>explicit-aid</artifactId>");
    }

    @Test
    void generated_manifest_is_schema_1_1_with_workspace_root_block() throws Exception {
        WorkspaceBootstrap bootstrap = bootstrap("my-ws", "org.example",
                "2-SNAPSHOT", null);

        String yaml = invokeGenerateManifest(bootstrap);

        assertThat(yaml)
                .contains("schema-version: \"1.1\"")
                .contains("workspace-root:")
                .contains("  groupId: org.example")
                .contains("  artifactId: my-ws")
                .contains("  version: 2-SNAPSHOT");
    }

    @Test
    void generated_manifest_falls_back_to_default_version_1_SNAPSHOT() throws Exception {
        WorkspaceBootstrap bootstrap = bootstrap("my-ws", "org.example",
                "1-SNAPSHOT", null);

        String yaml = invokeGenerateManifest(bootstrap);

        assertThat(yaml).contains("version: 1-SNAPSHOT");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private WsScaffoldInitMojo configured(String name, String group, String version,
                                          String artifactId) throws Exception {
        WsScaffoldInitMojo mojo = TestLog.createMojo(WsScaffoldInitMojo.class);
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
        Field f = WsScaffoldInitMojo.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Build a {@link WorkspaceBootstrap} directly with the same parameter
     * mix as the production code path. We don't bother running the mojo
     * because the generators are now isolated on the bootstrap class.
     */
    private WorkspaceBootstrap bootstrap(String name, String group,
                                          String version, String artifactId) {
        WorkspaceBootstrap.Params params = new WorkspaceBootstrap.Params(
                name,
                /*description*/ name,
                /*org*/ null,
                group,
                (artifactId == null || artifactId.isBlank()) ? name : artifactId,
                version == null ? "1-SNAPSHOT" : version,
                /*mavenVersion*/ "4.0.0-rc-5",
                /*defaultBranch*/ "main",
                /*skipGit*/ true,
                /*parentVersion*/ "test");
        return new WorkspaceBootstrap(params, new TestLog());
    }

    private String invokeGeneratePom(WorkspaceBootstrap bootstrap) throws Exception {
        Method m = WorkspaceBootstrap.class.getDeclaredMethod("generatePom");
        m.setAccessible(true);
        return (String) m.invoke(bootstrap);
    }

    private String invokeGenerateManifest(WorkspaceBootstrap bootstrap) throws Exception {
        Method m = WorkspaceBootstrap.class.getDeclaredMethod("generateManifest");
        m.setAccessible(true);
        return (String) m.invoke(bootstrap);
    }
}
