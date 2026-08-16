package network.ike.plugin.ws.preflight;

import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The external-snapshot gate
 * {@link PreflightCondition#NO_EXTERNAL_SNAPSHOT_DEPENDENCIES}
 * (ike-issues#1022): a releasing member's literal {@code -SNAPSHOT}
 * reference refuses the release unless the cycle itself resolves it —
 * the coordinate is a plan-released artifact, or one the member's own
 * tree produces. Modeled on the defect that shipped twice: an OS
 * profile pinning a never-released external snapshot
 * (ike-issues#1021).
 */
class ExternalSnapshotDependencyTest {

    @TempDir
    Path tempDir;

    private WorkspaceGraph graph;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  app:
                    repo: https://example.com/app.git
                    version: "2.0.1-SNAPSHOT"
                    groupId: com.test
                  helper:
                    repo: https://example.com/helper.git
                    version: "1.4.1-SNAPSHOT"
                    groupId: com.test
                """, StandardCharsets.UTF_8);
        graph = new WorkspaceGraph(
                ManifestReader.read(tempDir.resolve("workspace.yaml")));
        pom("helper", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>helper</artifactId>
                    <version>1.4.1-SNAPSHOT</version>
                </project>
                """);
        cleanAppPom();
    }

    /** The defect that shipped: an external snapshot in an OS profile. */
    @Test
    void external_snapshot_in_a_profile_refuses_and_names_the_site()
            throws Exception {
        pom("app", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>app</artifactId>
                    <version>2.0.1-SNAPSHOT</version>
                    <profiles>
                        <profile>
                            <id>mac</id>
                            <dependencies>
                                <dependency>
                                    <groupId>dev.ikm.jpms</groupId>
                                    <artifactId>rocksdbjni-jpms</artifactId>
                                    <version>10.4.2-r1-SNAPSHOT</version>
                                    <classifier>osx</classifier>
                                </dependency>
                            </dependencies>
                        </profile>
                    </profiles>
                </project>
                """);
        Optional<String> failure = check(Set.of("app", "helper"));
        assertThat(failure).isPresent();
        assertThat(failure.get())
                .contains("dev.ikm.jpms:rocksdbjni-jpms")
                .contains("10.4.2-r1-SNAPSHOT")
                .contains("profiles/mac")
                .contains("ike-issues#1022");
    }

    /**
     * A literal snapshot naming an artifact the cycle releases is
     * retargeted by the version pass — never refused.
     */
    @Test
    void reference_to_a_plan_released_artifact_is_exempt()
            throws Exception {
        pom("app", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>app</artifactId>
                    <version>2.0.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.test</groupId>
                            <artifactId>helper</artifactId>
                            <version>1.4.1-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Optional<String> failure = check(Set.of("app", "helper"));
        assertThat(failure).isEmpty();
    }

    /**
     * A multi-module member's sub-module parents to its own aggregator
     * at the development version. The member's own version pass moves
     * those — intermediate aggregators are nobody's plan artifact but
     * are its own coordinates all the same.
     */
    @Test
    void intra_repository_parent_snapshot_is_exempt() throws Exception {
        Path group = Files.createDirectories(
                tempDir.resolve("app").resolve("group"));
        Files.writeString(group.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>app</artifactId>
                        <version>2.0.1-SNAPSHOT</version>
                    </parent>
                    <artifactId>group</artifactId>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        Optional<String> failure = check(Set.of("app", "helper"));
        assertThat(failure).isEmpty();
    }

    /**
     * A Maven 4.1 intermediate aggregator — empty {@code <parent/>}, no
     * groupId, both inferred — whose child spells the full parent GA at
     * the development version. tinkar-core's reasoner aggregator is
     * this exact shape; the member's own version pass moves it, so the
     * gate must resolve the inferred groupId through the directory
     * tree and exempt it.
     */
    @Test
    void inferred_aggregator_parent_snapshot_is_exempt() throws Exception {
        Path agg = Files.createDirectories(
                tempDir.resolve("app").resolve("agg"));
        Files.writeString(agg.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <parent/>
                    <artifactId>agg</artifactId>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        Path sub = Files.createDirectories(agg.resolve("sub"));
        Files.writeString(sub.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>agg</artifactId>
                        <version>2.0.1-SNAPSHOT</version>
                    </parent>
                    <artifactId>sub</artifactId>
                </project>
                """, StandardCharsets.UTF_8);
        Optional<String> failure = check(Set.of("app", "helper"));
        assertThat(failure).isEmpty();
    }

    /**
     * The {@code ${project.groupId}} sibling idiom: identity falls back
     * to the artifactId, mirroring the workspace extension's rule.
     */
    @Test
    void sibling_idiom_reference_to_own_module_is_exempt()
            throws Exception {
        Path lib = Files.createDirectories(
                tempDir.resolve("app").resolve("lib"));
        Files.writeString(lib.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>app</artifactId>
                        <version>2.0.1-SNAPSHOT</version>
                    </parent>
                    <artifactId>lib</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>${project.groupId}</groupId>
                            <artifactId>group</artifactId>
                            <version>2.0.1-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
        Path group = Files.createDirectories(
                tempDir.resolve("app").resolve("group"));
        Files.writeString(group.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>app</artifactId>
                        <version>2.0.1-SNAPSHOT</version>
                    </parent>
                    <artifactId>group</artifactId>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        Optional<String> failure = check(Set.of("app", "helper"));
        assertThat(failure).isEmpty();
    }

    /** Bystanders' POMs do not deploy this cycle — never scanned. */
    @Test
    void bystander_member_with_external_snapshot_is_not_scanned()
            throws Exception {
        pom("helper", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>helper</artifactId>
                    <version>1.4.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.external</groupId>
                            <artifactId>thing</artifactId>
                            <version>9-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Optional<String> failure = check(Set.of("app"));
        assertThat(failure).isEmpty();
    }

    /** Outside a release cycle the condition is inert. */
    @Test
    void without_a_release_set_the_condition_passes() throws Exception {
        pom("app", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>app</artifactId>
                    <version>2.0.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.external</groupId>
                            <artifactId>thing</artifactId>
                            <version>9-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Optional<String> failure =
                PreflightCondition.NO_EXTERNAL_SNAPSHOT_DEPENDENCIES.check(
                        PreflightContext.of(tempDir.toFile(), graph,
                                List.of("app", "helper")));
        assertThat(failure).isEmpty();
    }

    /** Released external pins are exactly what the gate asks for. */
    @Test
    void released_external_versions_pass() throws Exception {
        pom("app", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>app</artifactId>
                    <version>2.0.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>dev.ikm.jpms</groupId>
                            <artifactId>rocksdbjni</artifactId>
                            <version>10.4.2-r2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Optional<String> failure = check(Set.of("app", "helper"));
        assertThat(failure).isEmpty();
    }

    // ── fixture plumbing ─────────────────────────────────────────────

    private void cleanAppPom() throws Exception {
        pom("app", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>app</artifactId>
                    <version>2.0.1-SNAPSHOT</version>
                </project>
                """);
    }

    private void pom(String member, String content) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve(member));
        Files.writeString(dir.resolve("pom.xml"), content,
                StandardCharsets.UTF_8);
    }

    /**
     * Run the gate with both members releasing and the cycle's plan
     * releasing {@code com.test:app} and {@code com.test:helper} — the
     * shape the release mojo passes after plan compute.
     */
    private Optional<String> check(Set<String> releaseSet) {
        return PreflightCondition.NO_EXTERNAL_SNAPSHOT_DEPENDENCIES.check(
                PreflightContext.of(tempDir.toFile(), graph,
                        List.of("app", "helper"), releaseSet,
                        Set.of(),
                        Set.of("com.test:app", "com.test:helper")));
    }
}
