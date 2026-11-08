package network.ike.plugin.ws;

import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A feature start never moves a {@code version-property} that versions
 * another artifact (IKE-Network/ike-issues#1352).
 *
 * <p>The reported failure: {@code ike-komet-wsr}'s manifest carried a
 * stale edge {@code komet-desktop → komet-stamped-document-plugin} with
 * {@code version-property: ike-starter-set.version}. Starting a sibling
 * followed the edge and rewrote that property to
 * {@code 1-<feature>-SNAPSHOT}, retargeting the external
 * {@code network.ike.foundation:ike-bindings} to a version nothing
 * publishes, and the sibling's build could not resolve it. The POMs and
 * the repository here are real: the cascade runs as it does at
 * {@code ws:feature-start-sibling-publish}.
 */
class FeatureStartForeignPropertyTest {

    private static final String BRANCH = "feature/parallel-plugin-tests";

    @TempDir Path ws;
    private final WarnLog log = new WarnLog();

    @BeforeEach
    void setUp() throws Exception {
        producer("komet-stamped-document-plugin");
        producer("komet-sequential-store-plugin");
        Path desktop = ws.resolve("komet-desktop");
        Files.createDirectories(desktop);
        Files.writeString(desktop.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.komet</groupId>
                    <artifactId>komet-desktop</artifactId>
                    <version>1-SNAPSHOT</version>
                    <properties>
                        <ike-starter-set.version>1-SNAPSHOT</ike-starter-set.version>
                        <komet-sequential-store-plugin.version>1-SNAPSHOT</komet-sequential-store-plugin.version>
                        <komet-stamped-document-plugin.version>1-SNAPSHOT</komet-stamped-document-plugin.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>network.ike.foundation</groupId>
                            <artifactId>ike-bindings</artifactId>
                            <version>${ike-starter-set.version}</version>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-dependency-plugin</artifactId>
                                <configuration>
                                    <artifactItems>
                                        <artifactItem>
                                            <groupId>network.ike.komet</groupId>
                                            <artifactId>komet-sequential-store-plugin</artifactId>
                                            <version>${komet-sequential-store-plugin.version}</version>
                                        </artifactItem>
                                        <artifactItem>
                                            <groupId>network.ike.komet</groupId>
                                            <artifactId>komet-stamped-document-plugin</artifactId>
                                            <version>${komet-stamped-document-plugin.version}</version>
                                        </artifactItem>
                                    </artifactItems>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """, StandardCharsets.UTF_8);
        git(desktop, "init", "-b", "main");
        git(desktop, "config", "user.email", "test@ike.test");
        git(desktop, "config", "user.name", "Test");
        git(desktop, "config", "commit.gpgsign", "false");
        git(desktop, "add", ".");
        git(desktop, "commit", "-m", "init");

        // The manifest as the faulty re-derive left it: the stamped
        // document edge names the starter set's property.
        Files.writeString(ws.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:

                  komet-stamped-document-plugin:
                    type: software
                    description: stamped document
                    repo: https://example.com/komet-stamped-document-plugin.git
                    groupId: network.ike.komet
                    version: "1-SNAPSHOT"
                    depends-on: []

                  komet-sequential-store-plugin:
                    type: software
                    description: sequential store
                    repo: https://example.com/komet-sequential-store-plugin.git
                    groupId: network.ike.komet
                    version: "1-SNAPSHOT"
                    depends-on: []

                  komet-desktop:
                    type: software
                    description: desktop
                    repo: https://example.com/komet-desktop.git
                    groupId: network.ike.komet
                    version: "1-SNAPSHOT"
                    depends-on:
                      - subproject: komet-sequential-store-plugin
                        relationship: bundle
                        version-property: komet-sequential-store-plugin.version
                      - subproject: komet-stamped-document-plugin
                        relationship: bundle
                        version-property: ike-starter-set.version
                """, StandardCharsets.UTF_8);
    }

    @Test
    void a_property_versioning_an_external_artifact_is_left_alone()
            throws Exception {
        WorkspaceGraph graph = new WorkspaceGraph(
                ManifestReader.read(ws.resolve("workspace.yaml")));
        Map<String, String> base = new LinkedHashMap<>();
        base.put("komet-stamped-document-plugin", "1-SNAPSHOT");
        base.put("komet-sequential-store-plugin", "1-SNAPSHOT");
        base.put("komet-desktop", "1-SNAPSHOT");

        new FeatureStartSupport(log).cascadeVersionProperties(graph,
                ws.toFile(),
                List.of("komet-stamped-document-plugin",
                        "komet-sequential-store-plugin", "komet-desktop"),
                base, BRANCH);

        String pom = Files.readString(ws.resolve("komet-desktop/pom.xml"),
                StandardCharsets.UTF_8);
        assertThat(pom)
                .as("the starter set's pin is not the stamped document's")
                .contains("<ike-starter-set.version>1-SNAPSHOT</ike-starter-set.version>")
                .as("a property the edge rightly names still moves")
                .contains("<komet-sequential-store-plugin.version>"
                        + "1-parallel-plugin-tests-SNAPSHOT"
                        + "</komet-sequential-store-plugin.version>");
        assertThat(log.warnings)
                .anySatisfy(w -> assertThat(w)
                        .contains("ike-starter-set.version")
                        .contains("network.ike.foundation:ike-bindings")
                        .contains("ike-issues#1352"));
    }

    @Test
    void foreign_uses_names_the_other_artifact_and_clears_the_upstream()
            throws Exception {
        WorkspaceGraph graph = new WorkspaceGraph(
                ManifestReader.read(ws.resolve("workspace.yaml")));
        SubprojectResolver resolver =
                SubprojectResolver.scan(ws, graph.manifest());
        WsAddMojo.VersionPropertyReferences refs =
                WsAddMojo.scanVersionPropertyReferences(
                        ws.resolve("komet-desktop/pom.xml"));

        assertThat(FeatureStartSupport.foreignUses("ike-starter-set.version",
                "komet-stamped-document-plugin", refs, resolver))
                .containsExactly("network.ike.foundation:ike-bindings");
        assertThat(FeatureStartSupport.foreignUses(
                "komet-stamped-document-plugin.version",
                "komet-stamped-document-plugin", refs, resolver))
                .isEmpty();
        assertThat(FeatureStartSupport.foreignUses("unused.version",
                "komet-stamped-document-plugin", refs, resolver))
                .as("a property no <version> uses moves harmlessly")
                .isEmpty();
    }

    private void producer(String name) throws Exception {
        Path dir = ws.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.komet</groupId>
                    <artifactId>%s</artifactId>
                    <version>1-SNAPSHOT</version>
                </project>
                """.formatted(name), StandardCharsets.UTF_8);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(
                    String.join(" ", command) + "\n" + out);
        }
    }

    /** A test log that keeps its warnings. */
    static final class WarnLog extends TestLog {
        final List<String> warnings = new ArrayList<>();

        @Override public void warn(CharSequence c) {
            warnings.add(String.valueOf(c));
            super.warn(c);
        }
    }
}
