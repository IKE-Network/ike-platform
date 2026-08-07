package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@code ws:add}'s post-write acyclicity gate
 * ({@link WsAddMojo#failOnDependsOnCycle} — IKE-Network/ike-issues#962).
 *
 * <p>{@code ws:add} writes derived forward edges and backfilled reverse
 * edges directly, so a contraction-induced cycle can reach
 * {@code workspace.yaml} without passing through {@link YamlDepsSync}.
 * The gate re-reads the written manifest, and on a cycle restores the
 * pre-add files and fails the goal.
 */
class WsAddCycleGateTest {

    @TempDir Path tempDir;
    private final YamlDepsSyncCycleGateTest.RecordingLog log =
            new YamlDepsSyncCycleGateTest.RecordingLog();

    private Path manifestPath;
    private Path pomPath;

    private static final String PRE_ADD_MANIFEST = """
            schema-version: "1.0"
            generated: "2026-01-01"

            defaults:
              branch: main

            subprojects:
            """;
    private static final String PRE_ADD_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example.ws</groupId>
                <artifactId>ws-root</artifactId>
                <version>1-SNAPSHOT</version>
                <packaging>pom</packaging>
            </project>
            """;

    @BeforeEach
    void setUp() throws Exception {
        manifestPath = tempDir.resolve("workspace.yaml");
        pomPath = tempDir.resolve("pom.xml");
        Files.writeString(manifestPath, PRE_ADD_MANIFEST, StandardCharsets.UTF_8);
        Files.writeString(pomPath, PRE_ADD_POM, StandardCharsets.UTF_8);
    }

    @Test
    void cyclic_written_manifest_restores_pre_add_files_and_fails()
            throws Exception {
        // Simulate the state right after ws:add's writes: both
        // subprojects registered with mutually-cyclic build edges.
        String written = PRE_ADD_MANIFEST
                + entry("app-repo", "com.example.app", "plugin-repo")
                + entry("plugin-repo", "com.example.plugin", "app-repo");
        Files.writeString(manifestPath, written, StandardCharsets.UTF_8);
        Files.writeString(pomPath,
                PRE_ADD_POM.replace("</project>",
                        "    <subprojects/>\n</project>"),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> WsAddMojo.failOnDependsOnCycle(
                log, tempDir, manifestPath, pomPath,
                PRE_ADD_MANIFEST, PRE_ADD_POM))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("app-repo")
                .hasMessageContaining("plugin-repo");

        assertThat(Files.readString(manifestPath, StandardCharsets.UTF_8))
                .as("workspace.yaml restored to pre-add content")
                .isEqualTo(PRE_ADD_MANIFEST);
        assertThat(Files.readString(pomPath, StandardCharsets.UTF_8))
                .as("reactor POM restored to pre-add content")
                .isEqualTo(PRE_ADD_POM);
    }

    @Test
    void acyclic_written_manifest_passes_and_keeps_the_writes()
            throws Exception {
        String written = PRE_ADD_MANIFEST
                + entry("app-repo", "com.example.app", "plugin-repo")
                + entry("plugin-repo", "com.example.plugin", null);
        Files.writeString(manifestPath, written, StandardCharsets.UTF_8);

        assertThatCode(() -> WsAddMojo.failOnDependsOnCycle(
                log, tempDir, manifestPath, pomPath,
                PRE_ADD_MANIFEST, PRE_ADD_POM))
                .doesNotThrowAnyException();

        assertThat(Files.readString(manifestPath, StandardCharsets.UTF_8))
                .as("acyclic writes are kept")
                .isEqualTo(written);
    }

    @Test
    void bundle_edges_do_not_count_toward_the_cycle() throws Exception {
        // The forward edge is a package-time bundle (#963): excluded
        // from the ordering graph, so no cycle — the add stands.
        String written = PRE_ADD_MANIFEST
                + entry("app-repo", "com.example.app", null)
                    .replace("    depends-on: []\n",
                        """
                            depends-on:
                              - subproject: plugin-repo
                                relationship: bundle
                        """)
                + entry("plugin-repo", "com.example.plugin", "app-repo");
        Files.writeString(manifestPath, written, StandardCharsets.UTF_8);

        assertThatCode(() -> WsAddMojo.failOnDependsOnCycle(
                log, tempDir, manifestPath, pomPath,
                PRE_ADD_MANIFEST, PRE_ADD_POM))
                .doesNotThrowAnyException();

        assertThat(Files.readString(manifestPath, StandardCharsets.UTF_8))
                .isEqualTo(written);
    }

    private static String entry(String name, String groupId, String dependsOn) {
        String deps = dependsOn == null
                ? "    depends-on: []\n"
                : "    depends-on:\n"
                        + "      - subproject: " + dependsOn + "\n"
                        + "        relationship: build\n";
        return "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + "    groupId: " + groupId + "\n"
                + deps;
    }
}
