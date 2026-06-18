package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.ReactorPom;
import network.ike.plugin.ws.TestLog;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReactorSubprojectsReconciler} (IKE-Network/ike-issues#696):
 * the reactor POM converges to {@code workspace.yaml} membership and the
 * legacy {@code with-*} profile pattern is retired.
 */
class ReactorSubprojectsReconcilerTest {

    /**
     * A reactor POM in the legacy shape: a {@code with-<name>} profile per
     * member, no top-level {@code <subprojects>}.
     */
    private static final String LEGACY_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.1.0" root="true">
                <modelVersion>4.1.0</modelVersion>
                <groupId>local.aggregate</groupId>
                <artifactId>demo-wsr</artifactId>
                <version>1-SNAPSHOT</version>
                <packaging>pom</packaging>
                <profiles>
                    <profile>
                        <id>with-alpha</id>
                        <activation>
                            <file>
                                <exists>${project.basedir}/alpha/pom.xml</exists>
                            </file>
                        </activation>
                        <subprojects>
                            <subproject>alpha</subproject>
                        </subprojects>
                    </profile>
                    <profile>
                        <id>with-beta</id>
                        <activation>
                            <file>
                                <exists>${project.basedir}/beta/pom.xml</exists>
                            </file>
                        </activation>
                        <subprojects>
                            <subproject>beta</subproject>
                        </subprojects>
                    </profile>
                </profiles>
            </project>
            """;

    /**
     * Manifest declaring three members — {@code alpha} and {@code beta}
     * have legacy profiles; {@code gamma} entered by another path and has
     * neither profile nor top-level entry.
     */
    private static final String MANIFEST = """
            schema-version: "1.0"
            defaults:
              branch: main
            subprojects:
              alpha:
                repo: https://example.com/alpha.git
                version: "1.0.0"
                groupId: network.ike.test
                sha: aaaaaaaa
              beta:
                repo: https://example.com/beta.git
                version: "2.0.0"
                groupId: network.ike.test
                sha: bbbbbbbb
              gamma:
                repo: https://example.com/gamma.git
                version: "3.0.0"
                groupId: network.ike.test
                sha: cccccccc
            """;

    private static WorkspaceContext ctx(Path tmp, Map<String, String> flags)
            throws IOException {
        Path manifest = tmp.resolve("workspace.yaml");
        Files.writeString(manifest, MANIFEST, StandardCharsets.UTF_8);
        return new WorkspaceContext(
                tmp.toFile(),
                manifest,
                new WorkspaceGraph(ManifestReader.read(manifest)),
                new ReconcilerOptions(flags),
                new TestLog());
    }

    @Test
    void apply_migrates_profiles_adds_missing_and_retires_profiles(
            @TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, LEGACY_POM, StandardCharsets.UTF_8);

        new ReactorSubprojectsReconciler().apply(ctx(tmp, Map.of()));

        String result = Files.readString(pom, StandardCharsets.UTF_8);
        // alpha/beta migrated, gamma added — all three, in yaml order.
        assertThat(ReactorPom.listSubprojects(result))
                .containsExactly("alpha", "beta", "gamma");
        // Every legacy profile retired; the empty block dropped.
        assertThat(ReactorPom.listSubprojectProfiles(result)).isEmpty();
        assertThat(result).doesNotContain("<profiles>");
        assertThat(result).doesNotContain("with-alpha");
    }

    @Test
    void apply_removes_stale_top_level_entry(@TempDir Path tmp)
            throws IOException {
        // POM already lists a member (delta) that workspace.yaml dropped.
        String modern = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0" root="true">
                    <modelVersion>4.1.0</modelVersion>
                    <artifactId>demo-wsr</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <subprojects>
                        <subproject>alpha</subproject>
                        <subproject>beta</subproject>
                        <subproject>gamma</subproject>
                        <subproject>delta</subproject>
                    </subprojects>
                </project>
                """;
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, modern, StandardCharsets.UTF_8);

        new ReactorSubprojectsReconciler().apply(ctx(tmp, Map.of()));

        assertThat(ReactorPom.listSubprojects(
                Files.readString(pom, StandardCharsets.UTF_8)))
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void detect_reports_drift_then_apply_is_idempotent(@TempDir Path tmp)
            throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, LEGACY_POM, StandardCharsets.UTF_8);

        ReactorSubprojectsReconciler reconciler =
                new ReactorSubprojectsReconciler();

        DriftReport before = reconciler.detect(ctx(tmp, Map.of()));
        assertThat(before.hasDrift()).isTrue();
        assertThat(before.detailLines())
                .anyMatch(d -> d.contains("add subproject: gamma"))
                .anyMatch(d -> d.contains("retire profile: with-alpha"));

        reconciler.apply(ctx(tmp, Map.of()));

        // Second detect: converged, no drift.
        assertThat(reconciler.detect(ctx(tmp, Map.of())).hasDrift()).isFalse();

        // Second apply: byte-for-byte unchanged.
        String afterFirst = Files.readString(pom, StandardCharsets.UTF_8);
        reconciler.apply(ctx(tmp, Map.of()));
        assertThat(Files.readString(pom, StandardCharsets.UTF_8))
                .isEqualTo(afterFirst);
    }

    @Test
    void apply_respects_opt_out(@TempDir Path tmp) throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, LEGACY_POM, StandardCharsets.UTF_8);

        new ReactorSubprojectsReconciler()
                .apply(ctx(tmp, Map.of("updateReactor", "false")));

        assertThat(Files.readString(pom, StandardCharsets.UTF_8))
                .isEqualTo(LEGACY_POM);
    }

    @Test
    void no_drift_when_no_reactor_pom(@TempDir Path tmp) throws IOException {
        assertThat(new ReactorSubprojectsReconciler()
                .detect(ctx(tmp, Map.of())).hasDrift())
                .isFalse();
    }
}
