package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link WsScaffoldDraftMojo} bare mode (ike-issues#601): in a
 * single repo with no workspace.yaml, {@code ws:scaffold} delegates to the
 * per-repo {@code ike:scaffold} engine — {@code ws:scaffold} ==
 * {@code ike:scaffold} for a working set of one.
 *
 * <p>The real {@code ike:scaffold-*} subprocess runs through a real Maven
 * (container/manual). These cover the routing (bare selected when there's
 * no workspace.yaml), the Maven-project validation, and the delegated
 * command line via {@link WsScaffoldDraftMojo#bareScaffoldArgs}.
 */
class WsScaffoldBareModeTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void setUp() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void bareMode_selected_whenNoWorkspace_andRequiresMavenProject() {
        // No workspace.yaml above tempDir and no pom.xml in it: bare mode
        // is selected (not the workspace-required path) and validates the
        // Maven project before delegating.
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

        WsScaffoldDraftMojo mojo = TestLog.createMojo(WsScaffoldDraftMojo.class);

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("pom.xml")
                .hasMessageContaining("workspace.yaml");
    }

    @Test
    void bareScaffoldArgs_draft_delegatesToIkeScaffoldDraft() {
        WsScaffoldDraftMojo mojo = TestLog.createMojo(WsScaffoldDraftMojo.class);
        assertThat(mojo.bareScaffoldArgs(tempDir.toFile()))
                .containsExactly("mvn", "validate", "ike:scaffold-draft",
                        "-B", "-Dike.scaffold.skip-parent=true");
    }

    @Test
    void bareScaffoldArgs_publish_delegatesToIkeScaffoldPublish() {
        WsScaffoldDraftMojo mojo = TestLog.createMojo(WsScaffoldDraftMojo.class);
        mojo.publish = true;
        assertThat(mojo.bareScaffoldArgs(tempDir.toFile()))
                .containsExactly("mvn", "validate", "ike:scaffold-publish",
                        "-B", "-Dike.scaffold.skip-parent=true");
    }
}
