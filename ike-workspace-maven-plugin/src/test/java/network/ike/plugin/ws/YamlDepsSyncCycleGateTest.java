package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the acyclicity gate on {@code depends-on} re-derivation
 * (IKE-Network/ike-issues#962).
 *
 * <p>The reported failure: derivation contracts the module graph to repo
 * granularity, and the contraction can manufacture a cycle that no module
 * edge actually forms. The observed shape ({@code ike-komet-wsr}):
 * {@code komet:application} (a reactor leaf) bundles
 * {@code komet-claude-plugin}, while {@code komet-claude-plugin} builds
 * against {@code komet:framework}. At module granularity the graph is
 * acyclic; contracted to repo nodes it reads
 * {@code komet ⇄ komet-claude-plugin}. The old sync wrote that manifest
 * anyway, and every subsequent {@code ws:} goal rejected it.
 *
 * <p>The fixture reproduces the shape as {@code app-repo}
 * (modules {@code framework} + {@code application}, where
 * {@code application} depends on the plugin repo's artifact) and
 * {@code plugin-repo} (depends on {@code app-repo}'s
 * {@code framework} module).
 */
class YamlDepsSyncCycleGateTest {

    @TempDir Path tempDir;
    private final RecordingLog log = new RecordingLog();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
    }

    @Test
    void contraction_cycle_aborts_without_writing_the_manifest()
            throws Exception {
        createAppRepo(true);
        createPluginRepo(true);
        addToManifest("app-repo", "com.example.app");
        addToManifest("plugin-repo", "com.example.plugin");
        String before = manifest();

        boolean changed = YamlDepsSync.run(tempDir.toFile(), log).changed();

        assertThat(changed)
                .as("derivation must not report a rewrite on cycle")
                .isFalse();
        assertThat(manifest())
                .as("workspace.yaml must not be written when derivation "
                        + "would produce a cycle")
                .isEqualTo(before);
        String errors = String.join("\n", log.errors);
        assertThat(errors).contains("cycle");
        assertThat(errors).contains("app-repo");
        assertThat(errors).contains("plugin-repo");
    }

    @Test
    void cycle_diagnostic_names_contributing_module_edges_with_file_and_line()
            throws Exception {
        createAppRepo(true);
        createPluginRepo(true);
        addToManifest("app-repo", "com.example.app");
        addToManifest("plugin-repo", "com.example.plugin");

        YamlDepsSync.run(tempDir.toFile(), log);

        String errors = String.join("\n", log.errors);
        // The repo-level edge must be traced to the module POM that
        // creates it, with a line number — not just repo names.
        assertThat(errors).contains("app-repo/application/pom.xml:");
        assertThat(errors).contains("plugin-repo/pom.xml:");
        assertThat(errors).contains("com.example.plugin:plugin-core");
        assertThat(errors).contains("com.example.app:framework");
        // Line numbers point at the referencing <artifactId> lines.
        assertThat(errors).matches(
                "(?s).*application/pom\\.xml:\\d+.*");
    }

    @Test
    void acyclic_derivation_still_rewrites_the_manifest() throws Exception {
        // Same shape minus the plugin's dependency back into app-repo:
        // only app-repo -> plugin-repo is derived. No cycle, normal sync.
        createAppRepo(true);
        createPluginRepo(false);
        addToManifest("app-repo", "com.example.app");
        addToManifest("plugin-repo", "com.example.plugin");

        boolean changed = YamlDepsSync.run(tempDir.toFile(), log).changed();

        assertThat(changed).isTrue();
        assertThat(manifest()).contains("subproject: plugin-repo");
        assertThat(log.errors).isEmpty();
    }

    @Test
    void existing_cyclic_manifest_heals_when_poms_are_acyclic()
            throws Exception {
        // The manifest carries a stale hand-written cycle, but the POMs
        // only support app-repo -> plugin-repo. The prospective (derived)
        // graph is acyclic, so the gate must let the healing rewrite pass.
        createAppRepo(true);
        createPluginRepo(false);
        addToManifest("app-repo", "com.example.app",
                "      - subproject: plugin-repo\n"
                        + "        relationship: build\n");
        addToManifest("plugin-repo", "com.example.plugin",
                "      - subproject: app-repo\n"
                        + "        relationship: build\n");

        boolean changed = YamlDepsSync.run(tempDir.toFile(), log).changed();

        assertThat(changed).isTrue();
        assertThat(log.errors).isEmpty();
        // The stale reverse edge is gone.
        assertThat(manifest()).doesNotContain("subproject: app-repo");
    }

    // ── Fixture ──────────────────────────────────────────────────

    /**
     * {@code app-repo}: aggregator with {@code framework} and
     * {@code application} modules. {@code application} is the reactor
     * leaf; when {@code withBundledPlugin} it depends on
     * {@code com.example.plugin:plugin-core} (the bundling edge that
     * contracts into the false cycle).
     */
    private void createAppRepo(boolean withBundledPlugin) throws Exception {
        Path repo = tempDir.resolve("app-repo");
        Files.createDirectories(repo.resolve("framework"));
        Files.createDirectories(repo.resolve("application"));
        Files.writeString(repo.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.app</groupId>
                    <artifactId>app-repo</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>framework</module>
                        <module>application</module>
                    </modules>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("framework/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.app</groupId>
                    <artifactId>framework</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);
        String pluginDep = withBundledPlugin ? """
                    <dependencies>
                        <dependency>
                            <groupId>com.example.plugin</groupId>
                            <artifactId>plugin-core</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                """ : "";
        Files.writeString(repo.resolve("application/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.app</groupId>
                    <artifactId>application</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                %s</project>
                """.formatted(pluginDep), StandardCharsets.UTF_8);
    }

    /**
     * {@code plugin-repo}: single module producing
     * {@code com.example.plugin:plugin-core}. When
     * {@code dependsOnFramework} it builds against
     * {@code com.example.app:framework} (the genuine build edge).
     */
    private void createPluginRepo(boolean dependsOnFramework) throws Exception {
        Path repo = tempDir.resolve("plugin-repo");
        Files.createDirectories(repo);
        String frameworkDep = dependsOnFramework ? """
                    <dependencies>
                        <dependency>
                            <groupId>com.example.app</groupId>
                            <artifactId>framework</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                """ : "";
        Files.writeString(repo.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.plugin</groupId>
                    <artifactId>plugin-core</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                %s</project>
                """.formatted(frameworkDep), StandardCharsets.UTF_8);
    }

    private void addToManifest(String name, String groupId) throws Exception {
        addToManifest(name, groupId, null);
    }

    private void addToManifest(String name, String groupId, String dependsOn)
            throws Exception {
        String deps = dependsOn == null
                ? "    depends-on: []\n"
                : "    depends-on:\n" + dependsOn;
        String entry = "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + "    groupId: " + groupId + "\n"
                + deps;
        Files.writeString(tempDir.resolve("workspace.yaml"),
                manifest() + entry, StandardCharsets.UTF_8);
    }

    private String manifest() throws Exception {
        return Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
    }

    /** TestLog that records error/warn lines for assertions. */
    static final class RecordingLog extends TestLog {
        final List<String> errors = new ArrayList<>();
        final List<String> warns = new ArrayList<>();

        @Override public void error(CharSequence c) {
            errors.add(String.valueOf(c));
            super.error(c);
        }

        @Override public void warn(CharSequence c) {
            warns.add(String.valueOf(c));
            super.warn(c);
        }
    }
}
