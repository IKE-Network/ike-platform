package network.ike.plugin.ws;

import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code ws:add} version alignment (IKE-Network/ike-issues#826):
 * a dependency version held behind a {@code ${...}} property must keep its
 * indirection (never inlined to a literal), and any version {@code ws:add}
 * does write must be the reactor member's actual — feature-qualified —
 * version read from its checked-out POM, not main-line manifest metadata.
 *
 * <p>Covers {@link DependencyVersionAligner#align},
 * {@link WsAddMojo#alignSubprojectPoms}, and
 * {@link WsAddMojo#collectWorkspaceArtifactVersions}.
 */
class WsAddVersionAlignmentTest {

    private static final String QUALIFIED = "1.127.2-chronology-builder-SNAPSHOT";
    private static final String MAIN_LINE = "1.127.2-SNAPSHOT";

    private static final Map<String, String> TINKAR_VERSIONS =
            Map.of("dev.ikm.tinkar:entity", QUALIFIED);

    // ── DependencyVersionAligner.align ──────────────────────────

    @Test
    void property_reference_left_untouched_when_already_aligned() {
        String pom = modulePom("${tinkar.version}");

        DependencyVersionAligner.Result result = DependencyVersionAligner.align(
                pom, TINKAR_VERSIONS, Map.of("tinkar.version", QUALIFIED));

        assertThat(result.pom()).isEqualTo(pom);
        assertThat(result.propertyUpdates()).isEmpty();
    }

    @Test
    void stale_property_reference_reports_update_and_keeps_indirection() {
        String pom = modulePom("${tinkar.version}");

        DependencyVersionAligner.Result result = DependencyVersionAligner.align(
                pom, TINKAR_VERSIONS, Map.of("tinkar.version", MAIN_LINE));

        assertThat(result.pom())
                .as("the ${...} reference itself is never rewritten")
                .isEqualTo(pom);
        assertThat(result.propertyUpdates())
                .containsExactlyEntriesOf(Map.of("tinkar.version", QUALIFIED));
    }

    @Test
    void unresolvable_property_reference_left_untouched() {
        String pom = modulePom("${tinkar.version}");

        DependencyVersionAligner.Result result = DependencyVersionAligner.align(
                pom, TINKAR_VERSIONS, Map.of());

        assertThat(result.pom()).isEqualTo(pom);
        assertThat(result.propertyUpdates()).isEmpty();
    }

    @Test
    void project_version_reference_never_touched() {
        String pom = modulePom("${project.version}");

        DependencyVersionAligner.Result result = DependencyVersionAligner.align(
                pom, TINKAR_VERSIONS,
                Map.of("project.version", MAIN_LINE));

        assertThat(result.pom()).isEqualTo(pom);
        assertThat(result.propertyUpdates()).isEmpty();
    }

    @Test
    void mixed_expression_version_left_untouched() {
        String pom = modulePom("${tinkar.base}-SNAPSHOT");

        DependencyVersionAligner.Result result = DependencyVersionAligner.align(
                pom, TINKAR_VERSIONS, Map.of("tinkar.base", "1.127.2"));

        assertThat(result.pom()).isEqualTo(pom);
        assertThat(result.propertyUpdates()).isEmpty();
    }

    @Test
    void literal_version_rewritten_to_reactor_version() {
        String pom = modulePom(MAIN_LINE);

        DependencyVersionAligner.Result result = DependencyVersionAligner.align(
                pom, TINKAR_VERSIONS, Map.of());

        assertThat(result.pom())
                .contains("<version>" + QUALIFIED + "</version>")
                .doesNotContain(MAIN_LINE);
        assertThat(result.propertyUpdates()).isEmpty();
    }

    @Test
    void literal_version_already_aligned_is_noop() {
        String pom = modulePom(QUALIFIED);

        DependencyVersionAligner.Result result = DependencyVersionAligner.align(
                pom, TINKAR_VERSIONS, Map.of());

        assertThat(result.pom()).isEqualTo(pom);
    }

    @Test
    void external_dependency_never_touched() {
        String pom = modulePom(MAIN_LINE).replace(
                "<groupId>dev.ikm.tinkar</groupId>",
                "<groupId>org.external</groupId>");

        DependencyVersionAligner.Result result = DependencyVersionAligner.align(
                pom, TINKAR_VERSIONS, Map.of());

        assertThat(result.pom()).isEqualTo(pom);
    }

    @Test
    void dependency_management_entries_never_touched() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>module-a</artifactId>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>dev.ikm.tinkar</groupId>
                                <artifactId>entity</artifactId>
                                <version>%s</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """.formatted(MAIN_LINE);

        DependencyVersionAligner.Result result = DependencyVersionAligner.align(
                pom, TINKAR_VERSIONS, Map.of());

        assertThat(result.pom()).isEqualTo(pom);
        assertThat(result.propertyUpdates()).isEmpty();
    }

    @Test
    void singlePropertyReference_classification() {
        assertThat(DependencyVersionAligner
                .singlePropertyReference("${tinkar.version}"))
                .isEqualTo("tinkar.version");
        assertThat(DependencyVersionAligner
                .singlePropertyReference("  ${tinkar.version}  "))
                .isEqualTo("tinkar.version");
        assertThat(DependencyVersionAligner
                .singlePropertyReference("1.127.2-SNAPSHOT")).isNull();
        assertThat(DependencyVersionAligner
                .singlePropertyReference("${a}-${b}")).isNull();
        assertThat(DependencyVersionAligner
                .singlePropertyReference("${base}-SNAPSHOT")).isNull();
    }

    // ── alignSubprojectPoms (file-level, #826 scenario) ──────────

    @Test
    void stale_property_updated_at_declaration_site_reference_preserved(
            @TempDir Path tmp) throws IOException {
        Path sub = subprojectWithModule(tmp,
                MAIN_LINE,                 // root <properties> value (stale)
                "${tinkar.version}");      // module dependency version

        int modified = WsAddMojo.alignSubprojectPoms(
                sub, TINKAR_VERSIONS, new TestLog());

        assertThat(modified).isEqualTo(1);
        assertThat(Files.readString(sub.resolve("pom.xml")))
                .contains("<tinkar.version>" + QUALIFIED + "</tinkar.version>");
        assertThat(Files.readString(sub.resolve("module-a/pom.xml")))
                .as("module keeps the property reference — no literal inlined")
                .contains("<version>${tinkar.version}</version>")
                .doesNotContain(MAIN_LINE)
                .doesNotContain("<version>" + QUALIFIED + "</version>");
    }

    @Test
    void aligned_property_leaves_all_poms_untouched(@TempDir Path tmp)
            throws IOException {
        Path sub = subprojectWithModule(tmp, QUALIFIED, "${tinkar.version}");
        String rootBefore = Files.readString(sub.resolve("pom.xml"));
        String moduleBefore = Files.readString(sub.resolve("module-a/pom.xml"));

        int modified = WsAddMojo.alignSubprojectPoms(
                sub, TINKAR_VERSIONS, new TestLog());

        assertThat(modified).isZero();
        assertThat(Files.readString(sub.resolve("pom.xml")))
                .isEqualTo(rootBefore);
        assertThat(Files.readString(sub.resolve("module-a/pom.xml")))
                .isEqualTo(moduleBefore);
    }

    @Test
    void literal_module_dependency_rewritten_to_qualified_version(
            @TempDir Path tmp) throws IOException {
        Path sub = subprojectWithModule(tmp, QUALIFIED, MAIN_LINE);

        int modified = WsAddMojo.alignSubprojectPoms(
                sub, TINKAR_VERSIONS, new TestLog());

        assertThat(modified).isEqualTo(1);
        assertThat(Files.readString(sub.resolve("module-a/pom.xml")))
                .contains("<version>" + QUALIFIED + "</version>")
                .doesNotContain(MAIN_LINE);
    }

    // ── collectWorkspaceArtifactVersions (#826 wrong-branch defect) ──

    @Test
    void member_pom_version_wins_over_stale_manifest_version(@TempDir Path tmp)
            throws Exception {
        // Reactor member tinkar-core: POM carries the feature-qualified
        // version; workspace.yaml still records main-line.
        Path memberDir = Files.createDirectories(tmp.resolve("tinkar-core"));
        Files.writeString(memberDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.tinkar</groupId>
                    <artifactId>entity</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(QUALIFIED), StandardCharsets.UTF_8);

        Manifest manifest = manifestWithTinkarCore(tmp, MAIN_LINE);

        Map<String, String> versions = WsAddMojo.collectWorkspaceArtifactVersions(
                tmp, "rich-surface-starter-knowledge", manifest);

        assertThat(versions)
                .containsEntry("dev.ikm.tinkar:entity", QUALIFIED);
    }

    @Test
    void added_subproject_itself_is_excluded(@TempDir Path tmp)
            throws Exception {
        Path memberDir = Files.createDirectories(tmp.resolve("tinkar-core"));
        Files.writeString(memberDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.tinkar</groupId>
                    <artifactId>entity</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(QUALIFIED), StandardCharsets.UTF_8);

        Manifest manifest = manifestWithTinkarCore(tmp, MAIN_LINE);

        Map<String, String> versions = WsAddMojo.collectWorkspaceArtifactVersions(
                tmp, "tinkar-core", manifest);

        assertThat(versions).isEmpty();
    }

    // ── Fixtures ─────────────────────────────────────────────────

    /**
     * A module POM declaring one dependency on
     * {@code dev.ikm.tinkar:entity} at the given version expression.
     */
    private static String modulePom(String versionExpression) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>module-a</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>dev.ikm.tinkar</groupId>
                            <artifactId>entity</artifactId>
                            <version>%s</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(versionExpression);
    }

    /**
     * A two-POM subproject: root declaring {@code tinkar.version} in
     * {@code <properties>}, and {@code module-a} declaring the
     * dependency at the given version expression.
     */
    private static Path subprojectWithModule(Path tmp, String propertyValue,
                                             String moduleVersionExpression)
            throws IOException {
        Path sub = Files.createDirectories(
                tmp.resolve("rich-surface-starter-knowledge"));
        Files.writeString(sub.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.starter</groupId>
                    <artifactId>rich-surface-starter-knowledge</artifactId>
                    <version>1-chronology-builder-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <properties>
                        <tinkar.version>%s</tinkar.version>
                    </properties>
                    <modules>
                        <module>module-a</module>
                    </modules>
                </project>
                """.formatted(propertyValue), StandardCharsets.UTF_8);

        Path moduleDir = Files.createDirectories(sub.resolve("module-a"));
        Files.writeString(moduleDir.resolve("pom.xml"),
                modulePom(moduleVersionExpression), StandardCharsets.UTF_8);
        return sub;
    }

    /**
     * A manifest registering {@code tinkar-core} at the given
     * (possibly stale) version.
     */
    private static Manifest manifestWithTinkarCore(Path tmp, String version)
            throws Exception {
        Path manifestPath = tmp.resolve("workspace.yaml");
        Files.writeString(manifestPath, """
                schema-version: "1.0"
                generated: "2026-06-01"

                defaults:
                  branch: feature/chronology-builder

                subprojects:
                  tinkar-core:
                    description: >
                      Tinkar core.
                    repo: https://github.com/ikmdev/tinkar-core.git
                    branch: feature/chronology-builder
                    version: "%s"
                    groupId: dev.ikm.tinkar
                    depends-on: []
                """.formatted(version), StandardCharsets.UTF_8);
        return ManifestReader.read(manifestPath);
    }
}
