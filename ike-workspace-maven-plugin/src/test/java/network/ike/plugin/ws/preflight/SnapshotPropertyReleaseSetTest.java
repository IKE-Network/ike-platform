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
 * Release-set awareness of {@link PreflightCondition#NO_SNAPSHOT_PROPERTIES}
 * (ike-issues#981): a SNAPSHOT property that is the manifest-declared
 * {@code version-property} hint of an edge whose target releases in the
 * current mission is resolved by the loop's catch-up alignment and must not
 * fail the gate; every other SNAPSHOT property keeps failing.
 */
class SnapshotPropertyReleaseSetTest {

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
                  doc-example:
                    repo: https://example.com/doc-example.git
                    version: "34-SNAPSHOT"
                    groupId: network.ike.examples
                  project-example:
                    repo: https://example.com/project-example.git
                    version: "34-SNAPSHOT"
                    groupId: network.ike.examples
                    depends-on:
                      - subproject: doc-example
                        relationship: build
                        version-property: doc-example.version
                """, StandardCharsets.UTF_8);
        graph = new WorkspaceGraph(
                ManifestReader.read(tempDir.resolve("workspace.yaml")));
        writePom("doc-example", "<properties/>");
        writePom("project-example", """
                <properties>
                    <doc-example.version>34-SNAPSHOT</doc-example.version>
                </properties>
                """);
    }

    @Test
    void property_tracking_in_set_member_is_exempt() {
        Optional<String> failure = PreflightCondition.NO_SNAPSHOT_PROPERTIES
                .check(ctx(Set.of("doc-example", "project-example")));
        assertThat(failure).isEmpty();
    }

    @Test
    void property_tracking_out_of_set_member_still_fails() {
        Optional<String> failure = PreflightCondition.NO_SNAPSHOT_PROPERTIES
                .check(ctx(Set.of("project-example")));
        assertThat(failure).isPresent();
        assertThat(failure.get()).contains("doc-example.version");
    }

    @Test
    void unrelated_snapshot_property_still_fails_with_release_set()
            throws Exception {
        writePom("project-example", """
                <properties>
                    <doc-example.version>34-SNAPSHOT</doc-example.version>
                    <stray.version>9-SNAPSHOT</stray.version>
                </properties>
                """);
        Optional<String> failure = PreflightCondition.NO_SNAPSHOT_PROPERTIES
                .check(ctx(Set.of("doc-example", "project-example")));
        assertThat(failure).isPresent();
        assertThat(failure.get())
                .contains("stray.version")
                .doesNotContain("doc-example.version");
    }

    @Test
    void without_release_set_the_gate_keeps_todays_behavior() {
        Optional<String> failure = PreflightCondition.NO_SNAPSHOT_PROPERTIES
                .check(PreflightContext.of(tempDir.toFile(), graph,
                        List.of("doc-example", "project-example")));
        assertThat(failure).isPresent();
        assertThat(failure.get()).contains("doc-example.version");
    }

    @Test
    void profile_scoped_tracked_property_is_exempt() throws Exception {
        writePom("project-example", """
                <properties/>
                <profiles>
                    <profile>
                        <id>docs</id>
                        <properties>
                            <doc-example.version>34-SNAPSHOT</doc-example.version>
                        </properties>
                    </profile>
                </profiles>
                """);
        Optional<String> failure = PreflightCondition.NO_SNAPSHOT_PROPERTIES
                .check(ctx(Set.of("doc-example", "project-example")));
        assertThat(failure).isEmpty();
    }

    private PreflightContext ctx(Set<String> releaseSet) {
        return PreflightContext.of(tempDir.toFile(), graph,
                List.of("doc-example", "project-example"), releaseSet);
    }

    private void writePom(String subproject, String body) throws Exception {
        Path dir = tempDir.resolve(subproject);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.examples</groupId>
                    <artifactId>%s</artifactId>
                    <version>34-SNAPSHOT</version>
                %s
                </project>
                """.formatted(subproject, body), StandardCharsets.UTF_8);
    }
}
