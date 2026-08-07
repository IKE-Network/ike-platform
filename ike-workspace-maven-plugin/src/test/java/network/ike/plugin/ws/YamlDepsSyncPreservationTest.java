package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@code depends-on} re-derivation merges instead of
 * replacing (IKE-Network/ike-issues#964) and never downgrades a
 * hand-declared {@code relationship: bundle} edge
 * (IKE-Network/ike-issues#963).
 *
 * <p>The old behavior rewrote the whole block from derivation output:
 * a manual edit — a bundle annotation, a content edge, an explanatory
 * comment — disappeared into the next routine re-derivation commit.
 */
class YamlDepsSyncPreservationTest {

    @TempDir Path tempDir;
    private final YamlDepsSyncCycleGateTest.RecordingLog log =
            new YamlDepsSyncCycleGateTest.RecordingLog();

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
    void bundle_edge_is_preserved_and_not_downgraded_to_build()
            throws Exception {
        // lib-b's POM references lib-a, so derivation proposes a build
        // edge — but the manifest declares the edge as bundle, with a
        // rationale comment. Both must survive, and no build duplicate
        // may appear.
        createLib("lib-a", "com.example.a", null, null);
        createLib("lib-b", "com.example.b", "com.example.a", "lib-a");
        addToManifest("lib-a", "com.example.a", "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", """
                    depends-on:
                      # lib-a is packaged into lib-b's assembly at package
                      # time and resolved from the repository (#963).
                      - subproject: lib-a
                        relationship: bundle
                """);

        boolean changed = YamlDepsSync.run(tempDir.toFile(), log).changed();

        String manifest = manifest();
        assertThat(manifest).contains("relationship: bundle");
        assertThat(manifest)
                .as("no derived build duplicate for the bundled target")
                .doesNotContain("relationship: build");
        assertThat(manifest)
                .as("the rationale comment survives the round-trip")
                .contains("resolved from the repository (#963)");
        // The only change is the managed-ownership annotation.
        assertThat(changed).isTrue();
        assertThat(manifest).contains("# ── managed: depends-on");
    }

    @Test
    void content_edge_without_pom_counterpart_is_preserved()
            throws Exception {
        // lib-b's POM references only lib-a; the manifest also declares
        // a hand-authored content edge on lib-c. The content edge stays,
        // the build edge is derived alongside it.
        createLib("lib-a", "com.example.a", null, null);
        createLib("lib-c", "com.example.c", null, null);
        createLib("lib-b", "com.example.b", "com.example.a", "lib-a");
        addToManifest("lib-a", "com.example.a", "    depends-on: []\n");
        addToManifest("lib-c", "com.example.c", "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", """
                    depends-on:
                      - subproject: lib-c
                        relationship: content
                """);

        YamlDepsSync.run(tempDir.toFile(), log);

        String manifest = manifest();
        assertThat(manifest).contains("subproject: lib-c");
        assertThat(manifest).contains("relationship: content");
        assertThat(manifest).contains("subproject: lib-a");
        assertThat(manifest).contains("relationship: build");
    }

    @Test
    void surviving_build_edge_keeps_its_comment_and_text()
            throws Exception {
        createLib("lib-a", "com.example.a", null, null);
        createLib("lib-b", "com.example.b", "com.example.a", "lib-a");
        addToManifest("lib-a", "com.example.a", "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", """
                    depends-on:
                      # pinned during the v3 migration
                      - subproject: lib-a
                        relationship: build
                """);

        YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(manifest())
                .contains("# pinned during the v3 migration");
    }

    @Test
    void removed_build_edge_is_warned_about_and_enumerated()
            throws Exception {
        // lib-b's manifest claims a build edge on lib-x, but its POM
        // references nothing — stale drift. The removal must happen and
        // be called out as potentially discarding a hand edit.
        createLib("lib-x", "com.example.x", null, null);
        createLib("lib-b", "com.example.b", null, null);
        addToManifest("lib-x", "com.example.x", "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", """
                    depends-on:
                      - subproject: lib-x
                        relationship: build
                """);

        YamlDepsSync.SyncResult result =
                YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(result.changed()).isTrue();
        assertThat(manifest()).doesNotContain("subproject: lib-x");
        assertThat(String.join("\n", log.warns))
                .contains("removed [lib-x]")
                .contains("non-build");
        assertThat(String.join("\n", result.changeLines()))
                .contains("lib-b")
                .contains("removed [lib-x]");
    }

    @Test
    void second_run_is_idempotent() throws Exception {
        createLib("lib-a", "com.example.a", null, null);
        createLib("lib-b", "com.example.b", "com.example.a", "lib-a");
        addToManifest("lib-a", "com.example.a", "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", "    depends-on: []\n");

        assertThat(YamlDepsSync.run(tempDir.toFile(), log).changed())
                .isTrue();
        String afterFirst = manifest();

        assertThat(YamlDepsSync.run(tempDir.toFile(), log).changed())
                .as("second run makes no further change")
                .isFalse();
        assertThat(manifest()).isEqualTo(afterFirst);
    }

    @Test
    void bundle_annotation_resolves_the_contraction_cycle()
            throws Exception {
        // The full #962/#963 story: the komet shape is cyclic when both
        // edges are build, and the gate blocks it. With the bundling
        // side annotated bundle, derivation completes — the annotation
        // is preserved, the genuine build edge is written, and the
        // ordering graph stays acyclic.
        createAppRepo();
        createPluginRepo();
        addToManifest("app-repo", "com.example.app", """
                    depends-on:
                      # application bundles plugin-core at package time;
                      # resolved from the repository, not built here.
                      - subproject: plugin-repo
                        relationship: bundle
                """);
        addToManifest("plugin-repo", "com.example.plugin",
                "    depends-on: []\n");

        YamlDepsSync.SyncResult result =
                YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(log.errors).as("no cycle with the bundle annotation")
                .isEmpty();
        assertThat(result.changed()).isTrue();
        String manifest = manifest();
        assertThat(manifest).contains("relationship: bundle");
        // plugin-repo's genuine build edge on app-repo was derived.
        assertThat(manifest).contains("subproject: app-repo");
        assertThat(manifest).contains("relationship: build");
    }

    // ── Fixture ──────────────────────────────────────────────────

    private void createLib(String name, String groupId,
                           String depGroupId, String depArtifact)
            throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        String deps = depGroupId == null ? "" : """
                    <dependencies>
                        <dependency>
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                """.formatted(depGroupId, depArtifact);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                %s</project>
                """.formatted(groupId, name, deps), StandardCharsets.UTF_8);
    }

    /** The two-repo komet shape from {@link YamlDepsSyncCycleGateTest}. */
    private void createAppRepo() throws Exception {
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
        Files.writeString(repo.resolve("application/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.app</groupId>
                    <artifactId>application</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example.plugin</groupId>
                            <artifactId>plugin-core</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
    }

    private void createPluginRepo() throws Exception {
        Path repo = tempDir.resolve("plugin-repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.plugin</groupId>
                    <artifactId>plugin-core</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example.app</groupId>
                            <artifactId>framework</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
    }

    private void addToManifest(String name, String groupId, String depsBlock)
            throws Exception {
        String entry = "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + "    groupId: " + groupId + "\n"
                + depsBlock;
        Files.writeString(tempDir.resolve("workspace.yaml"),
                manifest() + entry, StandardCharsets.UTF_8);
    }

    private String manifest() throws Exception {
        return Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
    }
}
