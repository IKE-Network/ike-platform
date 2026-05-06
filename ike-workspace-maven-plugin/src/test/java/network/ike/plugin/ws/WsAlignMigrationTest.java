package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration tests for the ike-issues#200 split: {@code ws:align}
 * is now POM-only, and any invocation that asks for branch
 * reconciliation must throw with a pointer to the new goal.
 *
 * <p>Both {@code -Dscope=branches} and {@code -Dscope=all} (the
 * old default) are caught here; only {@code -Dscope=poms} (the new
 * default) is permitted.
 */
class WsAlignMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void scope_branches_throws_with_pointer_to_reconcile() throws Exception {
        scaffoldWorkspace();

        WsAlignDraftMojo mojo = TestLog.createMojo(WsAlignDraftMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.scope = "branches";
        mojo.publish = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("ws:align no longer supports -Dscope=branches")
                .hasMessageContaining("ws:reconcile-branches-draft");
    }

    @Test
    void scope_all_also_throws_with_pointer() throws Exception {
        // -Dscope=all was the old default — now equally invalid.
        scaffoldWorkspace();

        WsAlignDraftMojo mojo = TestLog.createMojo(WsAlignDraftMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.scope = "all";
        mojo.publish = false;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("ws:align no longer supports -Dscope=all")
                .hasMessageContaining("ws:reconcile-branches-draft");
    }

    @Test
    void publish_path_points_at_publish_goal_in_pointer() throws Exception {
        scaffoldWorkspace();

        WsAlignDraftMojo mojo = TestLog.createMojo(WsAlignDraftMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.scope = "branches";
        mojo.publish = true;

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("ws:reconcile-branches-publish");
    }

    /** Minimal workspace.yaml — enough for loadGraph to succeed. */
    private void scaffoldWorkspace() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>scaffold</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
    }
}
