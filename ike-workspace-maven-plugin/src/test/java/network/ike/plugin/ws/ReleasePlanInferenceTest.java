package network.ike.plugin.ws;

import network.ike.plugin.ws.ReleasePlan.GA;
import network.ike.plugin.ws.ReleasePlanCompute.ArtifactReleaseIntent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for value-based property-target inference
 * (IKE-Network/ike-issues#1004) — how the plan resolves a version
 * property that no scanned reference site accounted for. The fixtures
 * are the komet working set's real shapes: a property named for
 * something other than the released artifact, a property consumed only
 * inside plugin configuration, and the value collision that arises
 * when five members all sit at {@code 1-SNAPSHOT}.
 */
class ReleasePlanInferenceTest {

    private static final ArtifactReleaseIntent KOMET = intent(
            "dev.ikm.komet", "komet-parent", "komet",
            "1.59.0-SNAPSHOT", "1.59.0");
    private static final ArtifactReleaseIntent TINKAR_CORE = intent(
            "dev.ikm.tinkar", "tinkar-core", "tinkar-core",
            "1.127.2-SNAPSHOT", "1.127.2");
    private static final ArtifactReleaseIntent GRPC_PLUGIN = intent(
            "network.ike.komet", "komet-grpc-plugin", "komet-grpc-plugin",
            "1-SNAPSHOT", "1");
    private static final ArtifactReleaseIntent CLAUSE_PLUGIN = intent(
            "network.ike.komet", "complex-clause-plugin",
            "complex-clause-plugin", "1-SNAPSHOT", "1");
    private static final List<ArtifactReleaseIntent> CYCLE =
            List.of(KOMET, TINKAR_CORE, GRPC_PLUGIN, CLAUSE_PLUGIN);

    @Test
    void unique_value_resolves_a_property_named_for_something_else() {
        // komet-bom pins komet's modules through <komet.version>, but
        // the repository releases as komet-parent — no name match.
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "komet.version", "1.59.0-SNAPSHOT", CYCLE))
                .isEqualTo(KOMET);

        // ike-knowledge-provider pins tinkar-core's modules under a
        // legacy name that matches no artifact at all.
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "chronology-store.version", "1.127.2-SNAPSHOT", CYCLE))
                .isEqualTo(TINKAR_CORE);
    }

    @Test
    void shared_value_is_broken_by_the_property_name() {
        // Both plugins sit at 1-SNAPSHOT; komet-desktop consumes each
        // only inside <artifactItem> configuration, so nothing but the
        // name distinguishes them.
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "komet-grpc-plugin.version", "1-SNAPSHOT", CYCLE))
                .isEqualTo(GRPC_PLUGIN);
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "complex-clause-plugin.version", "1-SNAPSHOT", CYCLE))
                .isEqualTo(CLAUSE_PLUGIN);
    }

    @Test
    void ambiguity_is_refused_rather_than_guessed() {
        // Shares the value with two members and names neither: the
        // release preflight should refuse the cycle, not the planner
        // pick a winner.
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "some-plugin.version", "1-SNAPSHOT", CYCLE)).isNull();

        // Names a member but disagrees on the value — that property is
        // pinning a released version, not tracking this cycle.
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "tinkar-core.version", "1.127.1", CYCLE)).isNull();
    }

    @Test
    void unresolvable_values_never_infer() {
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "x.version", "${project.version}", CYCLE)).isNull();
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "x.version", "", CYCLE)).isNull();
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "x.version", null, CYCLE)).isNull();
    }

    /** A property whose stem matches the producing subproject name. */
    @Test
    void subproject_name_also_breaks_a_shared_value() {
        ArtifactReleaseIntent oddlyNamed = intent(
                "network.ike.komet", "some-artifact", "rules-plugin",
                "1-SNAPSHOT", "1");
        assertThat(ReleasePlanCompute.inferTrackedArtifact(
                "rules-plugin.version", "1-SNAPSHOT",
                List.of(GRPC_PLUGIN, oddlyNamed)))
                .isEqualTo(oddlyNamed);
    }

    private static ArtifactReleaseIntent intent(String groupId,
            String artifactId, String subproject, String pre,
            String release) {
        return new ArtifactReleaseIntent(new GA(groupId, artifactId),
                subproject, Path.of("/ws", subproject, "pom.xml"),
                pre, release, "next-SNAPSHOT");
    }
}
