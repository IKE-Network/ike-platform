package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code version-property} on a re-derived {@code depends-on} edge
 * is the property the consumer's POM actually versions the upstream
 * through, never another artifact's property that happens to hold the
 * same value (IKE-Network/ike-issues#1352).
 *
 * <p>The fixture is the {@code ike-komet-wsr} shape after the stamped
 * document was lifted into its own plugin (#1351): every member is
 * {@code 1-SNAPSHOT}, and each consumer also declares
 * {@code ike-starter-set.version} — valued {@code 1-SNAPSHOT} too, and
 * declared <em>before</em> the property that versions the upstream.
 * Value matching took that first equal-valued property, so a release
 * would have bumped the starter set's pin instead of the plugin's.
 * The POMs are real files on disk; derivation reads them as it does a
 * working set.
 */
class YamlDepsSyncVersionPropertyTest {

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
        createProducer("komet-stamped-document-plugin");
        createProducer("komet-sequential-store-plugin");
        createClaudePlugin();
        createDesktop();
        createService();
        addToManifest("komet-stamped-document-plugin", "    depends-on: []\n");
        addToManifest("komet-sequential-store-plugin", "    depends-on: []\n");
    }

    @Test
    void wrong_recorded_properties_are_replaced_by_the_referenced_ones()
            throws Exception {
        // The manifest as the faulty re-derive left it: both edges to
        // the stamped-document plugin carry the starter set's property,
        // and the service's edge carries it too (it flips run to run).
        addToManifest("komet-claude-plugin", """
                    depends-on:
                      - subproject: komet-stamped-document-plugin
                        relationship: build
                        version-property: ike-starter-set.version
                """);
        addToManifest("komet-desktop", """
                    depends-on:
                      - subproject: komet-sequential-store-plugin
                        relationship: bundle
                        version-property: ike-starter-set.version
                      - subproject: komet-stamped-document-plugin
                        relationship: bundle
                        version-property: ike-starter-set.version
                """);
        addToManifest("tinkar-service", """
                    depends-on:
                      - subproject: komet-sequential-store-plugin
                        relationship: build
                        version-property: ike-starter-set.version
                """);

        YamlDepsSync.SyncResult result =
                YamlDepsSync.run(tempDir.toFile(), new TestLog());

        assertThat(result.changed()).isTrue();
        assertThat(block("komet-claude-plugin")).contains("""
                      - subproject: komet-stamped-document-plugin
                        relationship: build
                        version-property: komet-stamped-document-plugin.version
                """);
        assertThat(block("komet-desktop"))
                .as("a surviving bundle edge takes the referenced property "
                        + "and keeps its relationship")
                .contains("""
                      - subproject: komet-sequential-store-plugin
                        relationship: bundle
                        version-property: komet-sequential-store-plugin.version
                      - subproject: komet-stamped-document-plugin
                        relationship: bundle
                        version-property: komet-stamped-document-plugin.version
                """);
        assertThat(block("tinkar-service")).contains("""
                      - subproject: komet-sequential-store-plugin
                        relationship: build
                        version-property: komet-sequential-store-plugin.version
                """);
        assertThat(manifest()).doesNotContain("ike-starter-set.version");
        assertThat(YamlDepsSync.run(tempDir.toFile(), new TestLog()).changed())
                .as("the derivation is stable: a second run changes nothing")
                .isFalse();
    }

    @Test
    void new_edges_take_the_referenced_property() throws Exception {
        addToManifest("komet-claude-plugin", "    depends-on: []\n");
        addToManifest("komet-desktop", "    depends-on: []\n");
        addToManifest("tinkar-service", "    depends-on: []\n");

        YamlDepsSync.run(tempDir.toFile(), new TestLog());

        assertThat(block("komet-claude-plugin")).contains(
                "version-property: komet-stamped-document-plugin.version");
        assertThat(block("komet-desktop"))
                .contains("version-property: komet-stamped-document-plugin.version")
                .contains("version-property: komet-sequential-store-plugin.version");
        assertThat(block("tinkar-service")).contains(
                "version-property: komet-sequential-store-plugin.version");
        assertThat(manifest()).doesNotContain("ike-starter-set.version");
    }

    @Test
    void an_equal_value_alone_is_never_a_version_property() throws Exception {
        // The consumer versions the upstream literally and declares no
        // property named for it; the starter set's property holds the
        // same value. That coincidence is not a hint.
        Path dir = tempDir.resolve("literal-consumer");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.komet</groupId>
                    <artifactId>literal-consumer</artifactId>
                    <version>1-SNAPSHOT</version>
                    <properties>
                        <ike-starter-set.version>1-SNAPSHOT</ike-starter-set.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>network.ike.komet</groupId>
                            <artifactId>komet-stamped-document-plugin</artifactId>
                            <version>1-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
        addToManifest("literal-consumer", "    depends-on: []\n");

        YamlDepsSync.run(tempDir.toFile(), new TestLog());

        assertThat(block("literal-consumer"))
                .contains("- subproject: komet-stamped-document-plugin")
                .doesNotContain("version-property");
    }

    @Test
    void a_bom_managed_upstream_takes_the_property_named_for_it()
            throws Exception {
        // No <version> beside the reference (a BOM manages it), so no
        // reference names a property; the one named for the upstream
        // is the hint, declared after the equal-valued starter set.
        Path dir = tempDir.resolve("bom-consumer");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.komet</groupId>
                    <artifactId>bom-consumer</artifactId>
                    <version>1-SNAPSHOT</version>
                    <properties>
                        <ike-starter-set.version>1-SNAPSHOT</ike-starter-set.version>
                        <komet-stamped-document-plugin.version>1-SNAPSHOT</komet-stamped-document-plugin.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>network.ike.komet</groupId>
                            <artifactId>komet-stamped-document-plugin</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
        addToManifest("bom-consumer", "    depends-on: []\n");

        YamlDepsSync.run(tempDir.toFile(), new TestLog());

        assertThat(block("bom-consumer")).contains(
                "version-property: komet-stamped-document-plugin.version");
    }

    // ── Fixture: the ike-komet-wsr POM shapes ─────────────────────

    /** A single-module plugin producing {@code network.ike.komet:<name>}. */
    private void createProducer(String name) throws Exception {
        Path dir = tempDir.resolve(name);
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

    /**
     * {@code komet-claude-plugin}: a true dependency on the stamped
     * document plugin, versioned through its property; the starter
     * set's property, equal in value, is declared alongside and names
     * an artifact outside the workspace.
     */
    private void createClaudePlugin() throws Exception {
        Path dir = tempDir.resolve("komet-claude-plugin");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.komet</groupId>
                    <artifactId>komet-claude-plugin</artifactId>
                    <version>7-SNAPSHOT</version>
                    <properties>
                        <!-- The IKE starter set the tests load. -->
                        <ike-starter-set.version>1-SNAPSHOT</ike-starter-set.version>
                        <komet-stamped-document-plugin.version>1-SNAPSHOT</komet-stamped-document-plugin.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>network.ike.komet</groupId>
                            <artifactId>komet-stamped-document-plugin</artifactId>
                            <version>${komet-stamped-document-plugin.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>network.ike.starter</groupId>
                            <artifactId>ike-starter-set</artifactId>
                            <version>${ike-starter-set.version}</version>
                            <type>zip</type>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
    }

    /**
     * {@code komet-desktop}: an aggregator whose properties declare the
     * starter set first; its {@code application} module stages both
     * plugins into {@code plugins/} as {@code artifactItem}s (bundle).
     */
    private void createDesktop() throws Exception {
        Path dir = tempDir.resolve("komet-desktop");
        Files.createDirectories(dir.resolve("application"));
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.komet</groupId>
                    <artifactId>komet-desktop</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <properties>
                        <ike-starter-set.version>1-SNAPSHOT</ike-starter-set.version>
                        <komet-sequential-store-plugin.version>1-SNAPSHOT</komet-sequential-store-plugin.version>
                        <komet-stamped-document-plugin.version>1-SNAPSHOT</komet-stamped-document-plugin.version>
                    </properties>
                    <modules>
                        <module>application</module>
                    </modules>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("application/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>network.ike.komet</groupId>
                        <artifactId>komet-desktop</artifactId>
                        <version>1-SNAPSHOT</version>
                    </parent>
                    <artifactId>application</artifactId>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-dependency-plugin</artifactId>
                                <executions>
                                    <execution>
                                        <id>stage-plugins</id>
                                        <goals><goal>copy</goal></goals>
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
                                    </execution>
                                </executions>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """, StandardCharsets.UTF_8);
    }

    /**
     * {@code tinkar-service}: the sequential store's property declared
     * at the root, referenced from a module's dependency; the starter
     * set's equal-valued property is there too.
     */
    private void createService() throws Exception {
        Path dir = tempDir.resolve("tinkar-service");
        Files.createDirectories(dir.resolve("service"));
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.tinkar</groupId>
                    <artifactId>tinkar-service</artifactId>
                    <version>1.0.5-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <properties>
                        <ike-starter-set.version>1-SNAPSHOT</ike-starter-set.version>
                        <komet-sequential-store-plugin.version>1-SNAPSHOT</komet-sequential-store-plugin.version>
                    </properties>
                    <modules>
                        <module>service</module>
                    </modules>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("service/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>dev.ikm.tinkar</groupId>
                        <artifactId>tinkar-service</artifactId>
                        <version>1.0.5-SNAPSHOT</version>
                    </parent>
                    <artifactId>service</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>network.ike.komet</groupId>
                            <artifactId>komet-sequential-store-plugin</artifactId>
                            <version>${komet-sequential-store-plugin.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
    }

    private void addToManifest(String name, String depsBlock) throws Exception {
        String groupId = name.equals("tinkar-service")
                ? "dev.ikm.tinkar" : "network.ike.komet";
        String entry = "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + "    groupId: " + groupId + "\n"
                + "    version: \"1-SNAPSHOT\"\n"
                + depsBlock;
        Files.writeString(tempDir.resolve("workspace.yaml"),
                manifest() + entry, StandardCharsets.UTF_8);
    }

    private String manifest() throws Exception {
        return Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
    }

    /** One subproject's section of the manifest, up to the next one. */
    private String block(String name) throws Exception {
        String yaml = manifest();
        int start = yaml.indexOf("\n  " + name + ":\n");
        int end = yaml.indexOf("\n  ", start + name.length() + 4);
        while (end >= 0 && yaml.charAt(end + 3) == ' ') {
            end = yaml.indexOf("\n  ", end + 1);
        }
        return end < 0 ? yaml.substring(start) : yaml.substring(start, end);
    }
}
