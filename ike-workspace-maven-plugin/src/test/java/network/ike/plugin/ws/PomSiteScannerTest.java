package network.ike.plugin.ws;

import network.ike.plugin.ws.PomSiteScanner.PomSiteSurvey;
import network.ike.plugin.ws.ReleasePlan.GA;
import network.ike.plugin.ws.ReleasePlan.ReferenceKind;
import network.ike.plugin.ws.ReleasePlan.ReferenceSite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PomSiteScanner#scan} — the single-POM reference
 * site extractor used by {@link ReleasePlan} compute.
 *
 * <p>Exercises verbatim preservation of version text (property
 * references like {@code ${ike-tooling.version}} stay unresolved),
 * detection of sites in both direct and management sections, and the
 * {@code org.apache.maven.plugins} default groupId for plugin
 * entries without an explicit groupId.
 */
class PomSiteScannerTest {

    @Test
    void scan_empty_returnsNoSites(@TempDir Path tmp) throws IOException {
        Path pom = writePom(tmp, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>empty</artifactId>
                    <version>1</version>
                </project>
                """);

        PomSiteSurvey survey = PomSiteScanner.scan(pom);

        assertThat(survey.pomPath()).isEqualTo(pom);
        assertThat(survey.sites()).isEmpty();
        assertThat(survey.propertyDeclarations()).isEmpty();
    }

    @Test
    void scan_parent_recordsParentSite(@TempDir Path tmp) throws IOException {
        Path pom = writePom(tmp, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>network.ike.platform</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>child</artifactId>
                </project>
                """);

        PomSiteSurvey survey = PomSiteScanner.scan(pom);

        assertThat(survey.sites()).containsExactly(
                new ReferenceSite(
                        pom,
                        ReferenceKind.PARENT,
                        new GA("network.ike.platform", "ike-parent"),
                        "1"));
    }

    @Test
    void scan_dependencies_recordsBothSections(@TempDir Path tmp)
            throws IOException {
        Path pom = writePom(tmp, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>deps</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>network.ike.tooling</groupId>
                                <artifactId>ike-build-standards</artifactId>
                                <version>${ike-tooling.version}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        PomSiteSurvey survey = PomSiteScanner.scan(pom);

        assertThat(survey.sites()).containsExactlyInAnyOrder(
                new ReferenceSite(
                        pom,
                        ReferenceKind.DEPENDENCY,
                        new GA("org.junit.jupiter", "junit-jupiter"),
                        "5.11.0"),
                new ReferenceSite(
                        pom,
                        ReferenceKind.DEPENDENCY,
                        new GA("network.ike.tooling", "ike-build-standards"),
                        "${ike-tooling.version}"));
    }

    @Test
    void scan_plugins_recordsBothSections(@TempDir Path tmp)
            throws IOException {
        Path pom = writePom(tmp, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>plugins</artifactId>
                    <version>1</version>
                    <build>
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <groupId>network.ike.tooling</groupId>
                                    <artifactId>ike-maven-plugin</artifactId>
                                    <version>${ike-tooling.version}</version>
                                    <extensions>true</extensions>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                        <plugins>
                            <plugin>
                                <groupId>org.jacoco</groupId>
                                <artifactId>jacoco-maven-plugin</artifactId>
                                <version>0.8.12</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);

        PomSiteSurvey survey = PomSiteScanner.scan(pom);

        assertThat(survey.sites()).containsExactlyInAnyOrder(
                new ReferenceSite(
                        pom,
                        ReferenceKind.PLUGIN,
                        new GA("org.jacoco", "jacoco-maven-plugin"),
                        "0.8.12"),
                new ReferenceSite(
                        pom,
                        ReferenceKind.PLUGIN,
                        new GA("network.ike.tooling", "ike-maven-plugin"),
                        "${ike-tooling.version}"));
    }

    @Test
    void scan_pluginWithoutGroupId_defaultsToApachePlugins(@TempDir Path tmp)
            throws IOException {
        Path pom = writePom(tmp, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>apache-defaults</artifactId>
                    <version>1</version>
                    <build>
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <artifactId>maven-clean-plugin</artifactId>
                                    <version>3.4.0</version>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                    </build>
                </project>
                """);

        PomSiteSurvey survey = PomSiteScanner.scan(pom);

        assertThat(survey.sites()).containsExactly(
                new ReferenceSite(
                        pom,
                        ReferenceKind.PLUGIN,
                        new GA("org.apache.maven.plugins", "maven-clean-plugin"),
                        "3.4.0"));
    }

    @Test
    void scan_propertyReference_preservedVerbatim(@TempDir Path tmp)
            throws IOException {
        // Source POMs with the refactor use ${prop} even at
        // extensions=true sites (the 2026-04-22 finding). The scanner
        // must preserve the expression — plan compute is what resolves
        // it against the reactor's declaration set.
        Path pom = writePom(tmp, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>prop-ref</artifactId>
                    <version>1</version>
                    <properties>
                        <ike-tooling.version>124</ike-tooling.version>
                    </properties>
                    <build>
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <groupId>network.ike.tooling</groupId>
                                    <artifactId>ike-maven-plugin</artifactId>
                                    <version>${ike-tooling.version}</version>
                                    <extensions>true</extensions>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                    </build>
                </project>
                """);

        PomSiteSurvey survey = PomSiteScanner.scan(pom);

        ReferenceSite pluginSite = survey.sites().stream()
                .filter(s -> s.kind() == ReferenceKind.PLUGIN)
                .findFirst()
                .orElseThrow();
        assertThat(pluginSite.textAtSite())
                .isEqualTo("${ike-tooling.version}");
        assertThat(survey.propertyDeclarations())
                .containsEntry("ike-tooling.version", "124");
    }

    @Test
    void scan_dependencyWithoutVersion_recordsSiteWithNullText(
            @TempDir Path tmp) throws IOException {
        // A child POM may reference a dependency without <version>,
        // inheriting the version from ancestor dependencyManagement.
        // Plan compute treats null textAtSite as "version inherited
        // from ancestor" — the scanner just records what it sees.
        Path pom = writePom(tmp, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>no-version</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>network.ike.docs</groupId>
                            <artifactId>ike-doc-resources</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        PomSiteSurvey survey = PomSiteScanner.scan(pom);

        assertThat(survey.sites()).containsExactly(
                new ReferenceSite(
                        pom,
                        ReferenceKind.DEPENDENCY,
                        new GA("network.ike.docs", "ike-doc-resources"),
                        null));
    }

    @Test
    void scan_properties_captured(@TempDir Path tmp) throws IOException {
        Path pom = writePom(tmp, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>props</artifactId>
                    <version>1</version>
                    <properties>
                        <ike-tooling.version>124</ike-tooling.version>
                        <junit.version>5.11.0</junit.version>
                        <some.derived>${junit.version}</some.derived>
                    </properties>
                </project>
                """);

        PomSiteSurvey survey = PomSiteScanner.scan(pom);

        assertThat(survey.propertyDeclarations())
                .containsEntry("ike-tooling.version", "124")
                .containsEntry("junit.version", "5.11.0")
                .containsEntry("some.derived", "${junit.version}");
    }

    @Test
    void scan_fullMix_recordsAllKinds(@TempDir Path tmp)
            throws IOException {
        // Mirrors the shape of ike-platform/ike-parent/pom.xml:
        // properties, parent, plugin management (with a
        // property-referencing extensions plugin), dependency
        // management, dependencies, and plugins all present.
        Path pom = writePom(tmp, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>network.ike.platform</groupId>
                        <artifactId>ike-platform</artifactId>
                        <version>1-SNAPSHOT</version>
                    </parent>
                    <artifactId>ike-parent</artifactId>
                    <packaging>pom</packaging>
                    <properties>
                        <ike-tooling.version>124</ike-tooling.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>network.ike.tooling</groupId>
                                <artifactId>ike-build-standards</artifactId>
                                <version>${ike-tooling.version}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>network.ike.tooling</groupId>
                            <artifactId>ike-build-standards</artifactId>
                            <classifier>claude</classifier>
                            <type>zip</type>
                        </dependency>
                    </dependencies>
                    <build>
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <groupId>network.ike.tooling</groupId>
                                    <artifactId>ike-maven-plugin</artifactId>
                                    <version>${ike-tooling.version}</version>
                                    <extensions>true</extensions>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                        <plugins>
                            <plugin>
                                <groupId>org.jacoco</groupId>
                                <artifactId>jacoco-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);

        PomSiteSurvey survey = PomSiteScanner.scan(pom);

        assertThat(survey.sites())
                .extracting(ReferenceSite::kind)
                .containsOnly(
                        ReferenceKind.PARENT,
                        ReferenceKind.DEPENDENCY,
                        ReferenceKind.PLUGIN);
        assertThat(survey.sites())
                .filteredOn(s -> s.kind() == ReferenceKind.PARENT)
                .hasSize(1)
                .first()
                .extracting(ReferenceSite::targetGa, ReferenceSite::textAtSite)
                .containsExactly(
                        new GA("network.ike.platform", "ike-platform"),
                        "1-SNAPSHOT");
        assertThat(survey.sites())
                .filteredOn(s -> s.kind() == ReferenceKind.PLUGIN)
                .extracting(ReferenceSite::textAtSite)
                .containsExactlyInAnyOrder("${ike-tooling.version}", null);
        assertThat(survey.propertyDeclarations())
                .containsEntry("ike-tooling.version", "124");
    }

    private static Path writePom(Path tmp, String content) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, content, StandardCharsets.UTF_8);
        return pom;
    }
}
