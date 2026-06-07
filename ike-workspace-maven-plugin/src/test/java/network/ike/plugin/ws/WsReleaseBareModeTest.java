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
 * Tests for {@link WsReleaseDraftMojo} bare mode (ike-issues#601): in a
 * single repo with no workspace.yaml, {@code ws:release} delegates to the
 * per-repo {@code ike:release} engine — {@code ws:release} == {@code
 * ike:release} for a working set of one.
 *
 * <p>The actual {@code ike:release-*} subprocess is exercised by a real
 * single-repo release (manual / container), not here. These cover the
 * routing (bare mode is selected when there's no workspace.yaml), the
 * Maven-project validation, and the delegated command lines.
 */
class WsReleaseBareModeTest {

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
        // is selected (not the workspace-required path), and it validates
        // the Maven project before delegating.
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("pom.xml")
                .hasMessageContaining("workspace.yaml");
    }

    @Test
    void draftCommand_delegatesToIkeReleaseDraft() {
        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        assertThat(mojo.draftCommand("mvn"))
                .containsExactly("mvn", "ike:release-draft", "-B");
    }

    @Test
    void draftCommand_threadsIgnoreWarnings() {
        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.ignoreWarnings = true;
        assertThat(mojo.draftCommand("mvn"))
                .containsExactly("mvn", "ike:release-draft",
                        "-Dike.release.ignoreWarnings=true", "-B");
    }

    @Test
    void releaseCommand_publish_delegatesToIkeReleasePublishWithPush() {
        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.push = true;
        assertThat(mojo.releaseCommand("mvn"))
                .containsExactly("mvn", "ike:release-publish",
                        "-DpushRelease=true", "-B");
    }

    @Test
    void releaseCommand_publish_threadsIgnoreWarnings() {
        WsReleaseDraftMojo mojo = TestLog.createMojo(WsReleaseDraftMojo.class);
        mojo.push = false;
        mojo.ignoreWarnings = true;
        assertThat(mojo.releaseCommand("mvn"))
                .containsExactly("mvn", "ike:release-publish",
                        "-DpushRelease=false",
                        "-Dike.release.ignoreWarnings=true", "-B");
    }
}
