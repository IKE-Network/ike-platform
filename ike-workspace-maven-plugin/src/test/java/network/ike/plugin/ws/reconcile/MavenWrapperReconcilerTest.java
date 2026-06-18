package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.MavenWrapper;
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
 * Tests for {@link MavenWrapperReconciler} (IKE-Network/ike-issues#701):
 * each subproject's Maven wrapper is pinned to the workspace Maven version
 * ({@code defaults.maven-version}, or the subproject's own override).
 */
class MavenWrapperReconcilerTest {

    private static final String EXPECTED = "4.0.0-rc-5";
    private static final String STALE = "3.9.11";

    /**
     * Three subprojects: {@code alpha} and {@code beta} inherit the
     * default Maven version; {@code gamma} overrides it with its own
     * {@code maven-version}.
     */
    private static final String MANIFEST = """
            schema-version: "1.0"
            defaults:
              branch: main
              maven-version: "4.0.0-rc-5"
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
                maven-version: "3.9.11"
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

    /** Write a subproject's {@code maven-wrapper.properties} at {@code version}. */
    private static void writeWrapper(Path tmp, String name, String version)
            throws IOException {
        MavenWrapper.writePropertiesFile(
                tmp.resolve(name).resolve(".mvn").resolve("wrapper")
                        .resolve("maven-wrapper.properties"),
                version);
    }

    private static String pinnedVersion(Path tmp, String name)
            throws IOException {
        return MavenWrapper.readPinnedVersion(tmp.resolve(name));
    }

    @Test
    void detect_reportsOnlyWronglyPinnedWrappers(@TempDir Path tmp)
            throws IOException {
        writeWrapper(tmp, "alpha", EXPECTED);   // correct
        writeWrapper(tmp, "beta", STALE);       // drift → default
        writeWrapper(tmp, "gamma", STALE);      // matches its 3.9.11 override

        DriftReport drift = new MavenWrapperReconciler().detect(ctx(tmp, Map.of()));

        assertThat(drift.hasDrift()).isTrue();
        assertThat(drift.detailLines())
                .containsExactly("beta: " + STALE + " → " + EXPECTED);
    }

    @Test
    void apply_repinsDriftedWrappersAndIsIdempotent(@TempDir Path tmp)
            throws IOException {
        writeWrapper(tmp, "alpha", EXPECTED);
        writeWrapper(tmp, "beta", STALE);
        writeWrapper(tmp, "gamma", STALE);

        MavenWrapperReconciler reconciler = new MavenWrapperReconciler();
        reconciler.apply(ctx(tmp, Map.of()));

        assertThat(pinnedVersion(tmp, "beta")).isEqualTo(EXPECTED);
        // alpha untouched (already correct), gamma untouched (override match).
        assertThat(pinnedVersion(tmp, "alpha")).isEqualTo(EXPECTED);
        assertThat(pinnedVersion(tmp, "gamma")).isEqualTo(STALE);

        // Second detect: converged, no drift.
        assertThat(reconciler.detect(ctx(tmp, Map.of())).hasDrift()).isFalse();

        // Second apply: byte-for-byte unchanged.
        Path betaProps = tmp.resolve("beta").resolve(".mvn").resolve("wrapper")
                .resolve("maven-wrapper.properties");
        String afterFirst = Files.readString(betaProps, StandardCharsets.UTF_8);
        reconciler.apply(ctx(tmp, Map.of()));
        assertThat(Files.readString(betaProps, StandardCharsets.UTF_8))
                .isEqualTo(afterFirst);
    }

    @Test
    void apply_respectsOptOut(@TempDir Path tmp) throws IOException {
        writeWrapper(tmp, "beta", STALE);

        new MavenWrapperReconciler()
                .apply(ctx(tmp, Map.of("updateWrapper", "false")));

        assertThat(pinnedVersion(tmp, "beta")).isEqualTo(STALE);
    }

    @Test
    void detect_usesSubprojectOverrideAsExpected(@TempDir Path tmp)
            throws IOException {
        // gamma's wrapper matches the *default* but not its own override —
        // the override is authoritative, so this is drift.
        writeWrapper(tmp, "gamma", EXPECTED);

        DriftReport drift = new MavenWrapperReconciler().detect(ctx(tmp, Map.of()));

        assertThat(drift.detailLines())
                .containsExactly("gamma: " + EXPECTED + " → " + STALE);
    }

    @Test
    void apply_repinsToSubprojectOverride(@TempDir Path tmp)
            throws IOException {
        writeWrapper(tmp, "gamma", EXPECTED);

        new MavenWrapperReconciler().apply(ctx(tmp, Map.of()));

        assertThat(pinnedVersion(tmp, "gamma")).isEqualTo(STALE);
    }

    @Test
    void detect_skipsSubprojectsWithoutAWrapper(@TempDir Path tmp)
            throws IOException {
        // No wrapper files written at all — presence is not this
        // reconciler's concern (ScaffoldConventionReconciler owns it).
        assertThat(new MavenWrapperReconciler()
                .detect(ctx(tmp, Map.of())).hasDrift())
                .isFalse();
    }

    @Test
    void detect_noDriftWhenAllConverged(@TempDir Path tmp) throws IOException {
        writeWrapper(tmp, "alpha", EXPECTED);
        writeWrapper(tmp, "beta", EXPECTED);
        writeWrapper(tmp, "gamma", STALE);   // matches its override

        assertThat(new MavenWrapperReconciler()
                .detect(ctx(tmp, Map.of())).hasDrift())
                .isFalse();
    }
}
