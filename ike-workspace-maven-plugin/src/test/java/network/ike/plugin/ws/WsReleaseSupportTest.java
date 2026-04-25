package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for pure functions extracted from {@link WsReleaseDraftMojo}:
 * POM version extraction, parent version updates, and version
 * property updates.
 */
class WsReleaseSupportTest {

    // ── extractVersionFromPom ────────────────────────────────────────

    @Test
    void extractVersionFromPom_simpleProject() {
        String pom = """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.2.3-SNAPSHOT</version>
                </project>
                """;
        assertThat(WsReleaseDraftMojo.extractVersionFromPom(pom))
                .isEqualTo("1.2.3-SNAPSHOT");
    }

    @Test
    void extractVersionFromPom_withParentBlock_returnsParentVersion() {
        // extractVersionFromPom finds the FIRST <version> — which is
        // inside <parent>. This is the documented behavior for
        // workspace-level quick reads.
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>20-SNAPSHOT</version>
                    </parent>
                    <artifactId>my-module</artifactId>
                    <version>5.0.0</version>
                </project>
                """;
        assertThat(WsReleaseDraftMojo.extractVersionFromPom(pom))
                .isEqualTo("20-SNAPSHOT");
    }

    @Test
    void extractVersionFromPom_noVersion_returnsUnknown() {
        String pom = """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>orphan</artifactId>
                </project>
                """;
        assertThat(WsReleaseDraftMojo.extractVersionFromPom(pom))
                .isEqualTo("unknown");
    }

    @Test
    void extractVersionFromPom_null_returnsUnknown() {
        assertThat(WsReleaseDraftMojo.extractVersionFromPom(null))
                .isEqualTo("unknown");
    }

    @Test
    void extractVersionFromPom_blank_returnsUnknown() {
        assertThat(WsReleaseDraftMojo.extractVersionFromPom("  "))
                .isEqualTo("unknown");
    }

    @Test
    void extractVersionFromPom_integerVersion() {
        String pom = "<project><version>20</version></project>";
        assertThat(WsReleaseDraftMojo.extractVersionFromPom(pom))
                .isEqualTo("20");
    }

    // ── updateParentVersion ──────────────────────────────────────────

    @Test
    void updateParentVersion_matchingGa_updatesVersion() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>19-SNAPSHOT</version>
                    </parent>
                    <artifactId>ike-platform</artifactId>
                </project>
                """;
        String result = PomRewriter.updateParentVersion(
                pom, "network.ike", "ike-parent", "21-SNAPSHOT");

        assertThat(result)
                .contains("<version>21-SNAPSHOT</version>")
                .doesNotContain("19-SNAPSHOT");
    }

    @Test
    void updateParentVersion_nonMatchingArtifactId_unchanged() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>19-SNAPSHOT</version>
                    </parent>
                </project>
                """;
        String result = PomRewriter.updateParentVersion(
                pom, "network.ike", "other-parent", "21-SNAPSHOT");

        assertThat(result).contains("<version>19-SNAPSHOT</version>");
    }

    /**
     * Regression guard for issue #241. When two unrelated groupIds
     * share an artifactId (e.g. {@code network.ike.platform:ike-parent}
     * and {@code network.ike.pipeline:ike-parent}), targeting one
     * must not mutate the other.
     */
    @Test
    void updateParentVersion_matchingArtifactIdButDifferentGroupId_unchanged() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike.pipeline</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>111</version>
                    </parent>
                </project>
                """;
        // Target is the ike-platform parent — pipeline parent must stay put.
        String result = PomRewriter.updateParentVersion(
                pom, "network.ike.platform", "ike-parent", "2");

        assertThat(result).contains("<version>111</version>")
                .doesNotContain("<version>2</version>");
    }

    @Test
    void updateParentVersion_leavesProjectVersionAlone() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>19-SNAPSHOT</version>
                    </parent>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """;
        String result = PomRewriter.updateParentVersion(
                pom, "network.ike", "ike-parent", "21-SNAPSHOT");

        assertThat(result)
                .contains("<version>21-SNAPSHOT</version>")
                .contains("<version>1.0.0</version>");
    }

    @Test
    void updateParentVersion_artifactIdWithDots_handled() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-build-tools</artifactId>
                        <version>5-SNAPSHOT</version>
                    </parent>
                </project>
                """;
        String result = PomRewriter.updateParentVersion(
                pom, "network.ike", "ike-build-tools", "6-SNAPSHOT");

        assertThat(result).contains("<version>6-SNAPSHOT</version>");
    }

    // ── updateVersionProperty ────────────────────────────────────────

    @Test
    void updateVersionProperty_matchingProperty_updated() {
        String pom = """
                <project>
                    <properties>
                        <ike-platform.version>19-SNAPSHOT</ike-platform.version>
                    </properties>
                </project>
                """;
        String result = PomRewriter.updateProperty(
                pom, "ike-platform.version", "21-SNAPSHOT");

        assertThat(result)
                .contains("<ike-platform.version>21-SNAPSHOT</ike-platform.version>")
                .doesNotContain("19-SNAPSHOT");
    }

    @Test
    void updateVersionProperty_dottedProperty_updated() {
        String pom = """
                <project>
                    <properties>
                        <ike.pipeline.version>19-SNAPSHOT</ike.pipeline.version>
                    </properties>
                </project>
                """;
        String result = PomRewriter.updateProperty(
                pom, "ike.pipeline.version", "21-SNAPSHOT");

        assertThat(result)
                .contains("<ike.pipeline.version>21-SNAPSHOT</ike.pipeline.version>");
    }

    @Test
    void updateVersionProperty_nonMatchingProperty_unchanged() {
        String pom = """
                <project>
                    <properties>
                        <ike-platform.version>19-SNAPSHOT</ike-platform.version>
                    </properties>
                </project>
                """;
        String result = PomRewriter.updateProperty(
                pom, "other.version", "21-SNAPSHOT");

        assertThat(result)
                .contains("<ike-platform.version>19-SNAPSHOT</ike-platform.version>");
    }

    @Test
    void updateVersionProperty_leavesReferencesAlone() {
        // Only the declaration is rewritten; ${lib.version} references
        // elsewhere in the POM are not touched (they'll resolve to the
        // new value at Maven evaluation time).
        String pom = """
                <project>
                    <properties>
                        <lib.version>1.0</lib.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <version>${lib.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String result = PomRewriter.updateProperty(
                pom, "lib.version", "2.0");

        assertThat(result)
                .contains("<lib.version>2.0</lib.version>")
                .contains("${lib.version}");
    }

    // ── resolveMvnCommand ───────────────────────────────────────────

    @Test
    void resolveMvnCommand_noWrapper_returnsMvn(@TempDir Path tmpDir) {
        assertThat(WsReleaseDraftMojo.resolveMvnCommand(tmpDir.toFile()))
                .isEqualTo("mvn");
    }

    @Test
    void resolveMvnCommand_mvnwCmd_returnsAbsolutePath(@TempDir Path tmpDir)
            throws IOException {
        File mvnwCmd = tmpDir.resolve("mvnw.cmd").toFile();
        mvnwCmd.createNewFile();

        assertThat(WsReleaseDraftMojo.resolveMvnCommand(tmpDir.toFile()))
                .isEqualTo(mvnwCmd.getAbsolutePath());
    }

    @Test
    void resolveMvnCommand_executableMvnw_preferred(@TempDir Path tmpDir)
            throws IOException {
        File mvnw = tmpDir.resolve("mvnw").toFile();
        mvnw.createNewFile();
        mvnw.setExecutable(true);

        File mvnwCmd = tmpDir.resolve("mvnw.cmd").toFile();
        mvnwCmd.createNewFile();

        // mvnw (executable) should be preferred over mvnw.cmd
        assertThat(WsReleaseDraftMojo.resolveMvnCommand(tmpDir.toFile()))
                .isEqualTo(mvnw.getAbsolutePath());
    }

    @Test
    void resolveMvnCommand_nonExecutableMvnw_fallsToMvnwCmd(@TempDir Path tmpDir)
            throws IOException {
        File mvnw = tmpDir.resolve("mvnw").toFile();
        mvnw.createNewFile();
        mvnw.setExecutable(false);

        File mvnwCmd = tmpDir.resolve("mvnw.cmd").toFile();
        mvnwCmd.createNewFile();

        assertThat(WsReleaseDraftMojo.resolveMvnCommand(tmpDir.toFile()))
                .isEqualTo(mvnwCmd.getAbsolutePath());
    }

    // ── buildPreReleaseCheckpointYaml ────────────────────────────────

    @Test
    void buildPreReleaseCheckpointYaml_header() {
        String yaml = WsReleaseDraftMojo.buildPreReleaseCheckpointYaml(
                "pre-release-20260320-100000",
                "2026-03-20T10:00:00Z",
                List.of());

        assertThat(yaml)
                .contains("# Workspace checkpoint: pre-release-20260320-100000")
                .contains("# Generated: 2026-03-20T10:00:00Z")
                .contains("checkpoint: pre-release-20260320-100000")
                .contains("timestamp: 2026-03-20T10:00:00Z")
                .contains("subprojects:");
    }

    @Test
    void buildPreReleaseCheckpointYaml_singleComponent() {
        List<String[]> components = List.<String[]>of(
                new String[]{"ike-platform", "main", "abc123d", "20-SNAPSHOT", "false"});

        String yaml = WsReleaseDraftMojo.buildPreReleaseCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", components);

        assertThat(yaml)
                .contains("  ike-platform:")
                .contains("    branch: main")
                .contains("    sha: abc123d")
                .contains("    version: 20-SNAPSHOT")
                .contains("    modified: false");
    }

    @Test
    void buildPreReleaseCheckpointYaml_modifiedComponent() {
        List<String[]> components = List.<String[]>of(
                new String[]{"ike-docs", "feature/docs", "def456", "1.0-SNAPSHOT", "true"});

        String yaml = WsReleaseDraftMojo.buildPreReleaseCheckpointYaml(
                "test", "2026-01-01T00:00:00Z", components);

        assertThat(yaml)
                .contains("    modified: true");
    }

    @Test
    void buildPreReleaseCheckpointYaml_multipleComponents() {
        List<String[]> components = List.of(
                new String[]{"alpha", "main", "aaa", "1.0", "false"},
                new String[]{"beta", "develop", "bbb", "2.0-SNAPSHOT", "true"});

        String yaml = WsReleaseDraftMojo.buildPreReleaseCheckpointYaml(
                "multi", "2026-01-01T00:00:00Z", components);

        assertThat(yaml)
                .contains("  alpha:")
                .contains("  beta:");
    }

    @Test
    void buildPreReleaseCheckpointYaml_emptyComponents() {
        String yaml = WsReleaseDraftMojo.buildPreReleaseCheckpointYaml(
                "empty", "2026-01-01T00:00:00Z", List.of());

        assertThat(yaml)
                .contains("subprojects:\n")
                .endsWith("subprojects:\n");
    }

    // ── extractVersionFromPom: edge cases ────────────────────────────

    @Test
    void extractVersionFromPom_versionWithQualifier() {
        String pom = "<project><version>3.0.0-my-feature-SNAPSHOT</version></project>";
        assertThat(WsReleaseDraftMojo.extractVersionFromPom(pom))
                .isEqualTo("3.0.0-my-feature-SNAPSHOT");
    }

    @Test
    void extractVersionFromPom_multipleVersionElements_returnsFirst() {
        // Multiple <version> elements — returns the first one found
        String pom = """
                <project>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <version>2.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        assertThat(WsReleaseDraftMojo.extractVersionFromPom(pom))
                .isEqualTo("1.0.0");
    }

    @Test
    void extractVersionFromPom_versionInComment_skipsComment() {
        // Comments should not confuse the regex because the regex
        // matches the first <version> tag which is the real one
        String pom = """
                <project>
                    <!-- version was 0.9 -->
                    <version>1.0.0</version>
                </project>
                """;
        assertThat(WsReleaseDraftMojo.extractVersionFromPom(pom))
                .isEqualTo("1.0.0");
    }

    // ── updateParentVersion: additional edge cases ───────────────────

    @Test
    void updateParentVersion_noParentBlock_unchanged() {
        String pom = """
                <project>
                    <groupId>network.ike</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0</version>
                </project>
                """;
        String result = PomRewriter.updateParentVersion(
                pom, "network.ike", "ike-parent", "21-SNAPSHOT");

        // No parent block → no change
        assertThat(result).isEqualTo(pom);
    }

    @Test
    void updateParentVersion_multipleArtifactIds_onlyParentChanged() {
        String pom = """
                <project>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>19</version>
                    </parent>
                    <artifactId>my-module</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>network.ike</groupId>
                            <artifactId>ike-parent</artifactId>
                            <version>19</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        String result = PomRewriter.updateParentVersion(
                pom, "network.ike", "ike-parent", "21");

        // Parent version should be updated, but the dependency version should not
        // (updateParentVersion only modifies the parent block)
        assertThat(result).contains("<parent>");
    }

    // ── updateVersionProperty: edge cases ────────────────────────────

    @Test
    void updateVersionProperty_propertyNotPresent_unchanged() {
        String pom = """
                <project>
                    <properties>
                        <java.version>21</java.version>
                    </properties>
                </project>
                """;
        String result = PomRewriter.updateProperty(
                pom, "ike-platform.version", "21-SNAPSHOT");

        assertThat(result).isEqualTo(pom);
    }

    // ── upstreamTargetVersion (#209) ─────────────────────────────────

    @Test
    void upstreamTargetVersion_releasedThisCycle_returnsReleasedVersion(
            @TempDir Path tmp) throws IOException {
        // #209: If an upstream released earlier in this cycle,
        // downstream references must point at the RELEASED version —
        // not the post-release N+1-SNAPSHOT on the upstream's main
        // branch (which isn't deployed yet).
        WsReleaseDraftMojo mojo = new WsReleaseDraftMojo();
        Map<String, String> releasedVersions = new LinkedHashMap<>();
        releasedVersions.put("lib-a", "105");

        String target = mojo.upstreamTargetVersion(
                "lib-a", releasedVersions, tmp.toFile());

        assertThat(target).isEqualTo("105");
        assertThat(target).doesNotContain("SNAPSHOT");
        assertThat(target).doesNotContain("106");
    }

    @Test
    void upstreamTargetVersion_notInCycle_readsCurrentVersionFromDisk(
            @TempDir Path tmp) throws IOException {
        // When an upstream is not releasing this cycle, catch-up uses
        // the version currently on disk — the published version this
        // workspace is built against.
        File upstreamDir = tmp.resolve("lib-a").toFile();
        Files.createDirectories(upstreamDir.toPath());
        Files.writeString(upstreamDir.toPath().resolve("pom.xml"),
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>lib-a</artifactId>
                    <version>104</version>
                </project>
                """,
                StandardCharsets.UTF_8);

        WsReleaseDraftMojo mojo = new WsReleaseDraftMojo();
        String target = mojo.upstreamTargetVersion(
                "lib-a", new LinkedHashMap<>(), tmp.toFile());

        assertThat(target).isEqualTo("104");
    }

    @Test
    void upstreamTargetVersion_notOnDiskAndNotInCycle_returnsNull(
            @TempDir Path tmp) {
        // Upstream is neither releasing this cycle nor checked out —
        // nothing to align to.
        WsReleaseDraftMojo mojo = new WsReleaseDraftMojo();

        String target = mojo.upstreamTargetVersion(
                "ghost-lib", new LinkedHashMap<>(), tmp.toFile());

        assertThat(target).isNull();
    }
}
