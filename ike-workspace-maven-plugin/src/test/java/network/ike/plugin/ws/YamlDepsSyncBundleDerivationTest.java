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
 * Tests bundle derivation from plugin-staged references
 * (IKE-Network/ike-issues#965).
 *
 * <p>The reported gap: {@code scanPomForArtifacts} read only plugin
 * GAVs from {@code <build><plugins>}, so the structurally-differentiated
 * staging idiom ({@code ike-komet-wsr}'s komet-desktop: a
 * {@code maven-dependency-plugin} {@code artifactItem} staged into
 * {@code plugins/}, plus a plugin-level dependency serving as a
 * reactor-ordering hint) derived <em>no edge at all</em> — no cycle,
 * but also no cascade reach for the staged artifact's version pin.
 *
 * <p>The fixture mirrors the shape: {@code app-repo}'s reactor leaf
 * stages {@code plugin-repo}'s artifact via plugin-level references
 * while {@code plugin-repo} genuinely builds against
 * {@code app-repo}'s {@code framework} module. Derivation must emit
 * {@code relationship: bundle} for the staging direction — excluded
 * from the ordering graph (#963), so the repo-level contraction stays
 * acyclic — while the reverse direction derives {@code build}.
 */
class YamlDepsSyncBundleDerivationTest {

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
    void plugin_staged_reference_derives_bundle_and_dissolves_the_cycle()
            throws Exception {
        createStagingAppRepo(true, true, false);
        createPluginRepo(true);
        addToManifest("app-repo", "com.example.app");
        addToManifest("plugin-repo", "com.example.plugin");

        boolean changed = YamlDepsSync.run(tempDir.toFile(), log).changed();

        assertThat(changed).isTrue();
        assertThat(log.errors)
                .as("bundle edges are non-ordering — the contraction "
                        + "must not read as a cycle")
                .isEmpty();
        String appBlock = appRepoBlock();
        assertThat(appBlock).contains("- subproject: plugin-repo");
        assertThat(appBlock).contains("relationship: bundle");
        assertThat(appBlock).doesNotContain("relationship: build\n");
        assertThat(appBlock)
                .as("staged pin participates in cascade via the "
                        + "value-matched version property")
                .contains("version-property: plugin-core.version");
        assertThat(pluginRepoBlock())
                .contains("- subproject: app-repo")
                .contains("relationship: build");
    }

    @Test
    void plugin_level_dependency_alone_derives_bundle() throws Exception {
        createStagingAppRepo(false, true, false);
        createPluginRepo(false);
        addToManifest("app-repo", "com.example.app");
        addToManifest("plugin-repo", "com.example.plugin");

        YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(appRepoBlock())
                .contains("- subproject: plugin-repo")
                .contains("relationship: bundle");
    }

    @Test
    void project_dependency_outranks_plugin_staging() throws Exception {
        // The same artifact referenced both ways in the repo: a true
        // module dependency and an artifactItem. Build strength wins.
        createStagingAppRepo(true, true, true);
        createPluginRepo(false);
        addToManifest("app-repo", "com.example.app");
        addToManifest("plugin-repo", "com.example.plugin");

        YamlDepsSync.run(tempDir.toFile(), log);

        String appBlock = appRepoBlock();
        assertThat(appBlock).contains("- subproject: plugin-repo");
        assertThat(appBlock).contains("relationship: build");
        assertThat(appBlock).doesNotContain("relationship: bundle");
    }

    @Test
    void stale_build_edge_supersedes_to_bundle_on_idiom_migration()
            throws Exception {
        // The manifest carries a build edge from before the POM moved
        // to artifactItem staging. Re-derivation must replace it with
        // bundle, not keep it and not merely drop it.
        createStagingAppRepo(true, false, false);
        createPluginRepo(false);
        addToManifest("app-repo", "com.example.app",
                "      - subproject: plugin-repo\n"
                        + "        relationship: build\n");
        addToManifest("plugin-repo", "com.example.plugin");

        boolean changed = YamlDepsSync.run(tempDir.toFile(), log).changed();

        assertThat(changed).isTrue();
        String appBlock = appRepoBlock();
        assertThat(appBlock).contains("relationship: bundle");
        assertThat(appBlock).doesNotContain("relationship: build");
        assertThat(countOccurrences(appBlock, "subproject: plugin-repo"))
                .isEqualTo(1);
    }

    @Test
    void hand_bundle_entry_with_comment_survives_staged_derivation()
            throws Exception {
        createStagingAppRepo(true, false, false);
        createPluginRepo(false);
        addToManifest("app-repo", "com.example.app",
                "      # staged into plugins/ at package time\n"
                        + "      - subproject: plugin-repo\n"
                        + "        relationship: bundle\n");
        addToManifest("plugin-repo", "com.example.plugin");

        YamlDepsSync.run(tempDir.toFile(), log);

        String appBlock = appRepoBlock();
        assertThat(appBlock)
                .as("hand-authored entry text is preserved verbatim")
                .contains("# staged into plugins/ at package time");
        assertThat(countOccurrences(appBlock, "subproject: plugin-repo"))
                .as("no derived duplicate for a covered target")
                .isEqualTo(1);
    }

    // ── Fixture ──────────────────────────────────────────────────

    /**
     * {@code app-repo}: aggregator with {@code framework} and
     * {@code application} modules. The {@code application} leaf stages
     * {@code com.example.plugin:plugin-core} through
     * {@code maven-dependency-plugin} — an {@code artifactItem} under an
     * execution configuration when {@code withArtifactItem}, a
     * plugin-level {@code <dependencies>} entry when
     * {@code withPluginLevelDep} — and additionally declares it as a
     * true project dependency when {@code withProjectDep}.
     */
    private void createStagingAppRepo(boolean withArtifactItem,
                                      boolean withPluginLevelDep,
                                      boolean withProjectDep)
            throws Exception {
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
                    <properties>
                        <plugin-core.version>1.0.0-SNAPSHOT</plugin-core.version>
                    </properties>
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

        String projectDep = withProjectDep ? """
                    <dependencies>
                        <dependency>
                            <groupId>com.example.plugin</groupId>
                            <artifactId>plugin-core</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                """ : "";
        String pluginLevelDep = withPluginLevelDep ? """
                                <dependencies>
                                    <dependency>
                                        <groupId>com.example.plugin</groupId>
                                        <artifactId>plugin-core</artifactId>
                                        <version>${plugin-core.version}</version>
                                    </dependency>
                                </dependencies>
                """ : "";
        String artifactItem = withArtifactItem ? """
                                <executions>
                                    <execution>
                                        <goals><goal>copy</goal></goals>
                                        <configuration>
                                            <artifactItems>
                                                <artifactItem>
                                                    <groupId>com.example.plugin</groupId>
                                                    <artifactId>plugin-core</artifactId>
                                                    <version>${plugin-core.version}</version>
                                                </artifactItem>
                                            </artifactItems>
                                        </configuration>
                                    </execution>
                                </executions>
                """ : "";
        Files.writeString(repo.resolve("application/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.app</groupId>
                    <artifactId>application</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                %s    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-dependency-plugin</artifactId>
                %s%s            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(projectDep, pluginLevelDep, artifactItem),
                StandardCharsets.UTF_8);
    }

    /**
     * {@code plugin-repo}: single module producing
     * {@code com.example.plugin:plugin-core}. When
     * {@code dependsOnFramework} it builds against
     * {@code com.example.app:framework} (the genuine build edge that
     * would contract into a cycle if staging derived as build).
     */
    private void createPluginRepo(boolean dependsOnFramework)
            throws Exception {
        Path repo = tempDir.resolve("plugin-repo");
        Files.createDirectories(repo);
        String frameworkDep = dependsOnFramework ? """
                    <dependencies>
                        <dependency>
                            <groupId>com.example.app</groupId>
                            <artifactId>framework</artifactId>
                            <version>1.0.0-SNAPSHOT</version>
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

    private void addToManifest(String name, String groupId)
            throws Exception {
        addToManifest(name, groupId, null);
    }

    private void addToManifest(String name, String groupId,
                               String dependsOnEntries) throws Exception {
        String deps = dependsOnEntries == null
                ? "    depends-on: []\n"
                : "    depends-on:\n" + dependsOnEntries;
        // version: present so detectVersionProperty can value-match the
        // consumer's ${plugin-core.version} property (#965 cascade pin).
        String entry = "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + "    groupId: " + groupId + "\n"
                + "    version: 1.0.0-SNAPSHOT\n"
                + deps;
        Files.writeString(tempDir.resolve("workspace.yaml"),
                manifest() + entry, StandardCharsets.UTF_8);
    }

    private String manifest() throws Exception {
        return Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
    }

    /** The app-repo section of the manifest (up to plugin-repo's). */
    private String appRepoBlock() throws Exception {
        String yaml = manifest();
        int start = yaml.indexOf("  app-repo:");
        int end = yaml.indexOf("  plugin-repo:");
        return yaml.substring(start, end);
    }

    /** The plugin-repo section of the manifest (to end of file). */
    private String pluginRepoBlock() throws Exception {
        String yaml = manifest();
        return yaml.substring(yaml.indexOf("  plugin-repo:"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ── Log capture ──────────────────────────────────────────────

    static final class RecordingLog extends TestLog {
        final List<String> errors = new ArrayList<>();

        @Override public void error(CharSequence c) {
            errors.add(String.valueOf(c));
            super.error(c);
        }
    }
}
