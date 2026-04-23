package network.ike.plugin.ws;

import network.ike.plugin.ws.ReleaseStatusInspector.Finding;
import network.ike.plugin.ws.ReleaseStatusInspector.Observation;
import network.ike.plugin.ws.ReleaseStatusInspector.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure rule tests for {@link ReleaseStatusInspector}.
 *
 * <p>Each test composes a fixture {@link Observation} and asserts the
 * resulting {@link Finding}. No git, no temp dirs — the goal here is
 * to lock the classification rules so a refactor of the inspector
 * cannot silently change a state mapping.
 */
class ReleaseStatusInspectorTest {

    private static Observation observation(String name) {
        return new Observation(name, true, "1.0.0-SNAPSHOT", "main",
                List.of(), Set.of(), Set.of(), true);
    }

    @Test
    void absent_when_subproject_not_checked_out() {
        Observation obs = new Observation("ghost", false, "unknown", "unknown",
                List.of(), Set.of(), Set.of(), false);

        Finding f = ReleaseStatusInspector.classify(obs);

        assertThat(f.status()).isEqualTo(Status.ABSENT);
        assertThat(f.subprojectName()).isEqualTo("ghost");
        assertThat(f.details()).containsExactly("Subproject directory not present.");
        assertThat(f.inFlightReleaseBranches()).isEmpty();
        assertThat(f.localOnlyTags()).isEmpty();
    }

    @Test
    void clean_when_no_release_branches_and_no_local_only_tags() {
        Observation obs = new Observation("lib-a", true, "2.0.0-SNAPSHOT", "main",
                List.of(),
                Set.of("v1.0.0", "v1.1.0"),
                Set.of("v1.0.0", "v1.1.0"),
                true);

        Finding f = ReleaseStatusInspector.classify(obs);

        assertThat(f.status()).isEqualTo(Status.CLEAN);
        assertThat(f.details()).containsExactly("No in-flight release artifacts.");
        assertThat(f.inFlightReleaseBranches()).isEmpty();
        assertThat(f.localOnlyTags()).isEmpty();
    }

    @Test
    void in_flight_when_release_branch_present_locally() {
        Observation obs = new Observation("lib-a", true, "2.0.0-SNAPSHOT", "release/2.0.0",
                List.of("release/2.0.0"),
                Set.of("v1.0.0"),
                Set.of("v1.0.0"),
                true);

        Finding f = ReleaseStatusInspector.classify(obs);

        assertThat(f.status()).isEqualTo(Status.IN_FLIGHT);
        assertThat(f.inFlightReleaseBranches()).containsExactly("release/2.0.0");
        assertThat(f.localOnlyTags()).isEmpty();
        assertThat(f.details()).anyMatch(d -> d.contains("release/2.0.0"));
    }

    @Test
    void in_flight_when_local_tag_missing_from_remote() {
        Observation obs = new Observation("lib-a", true, "2.1.0-SNAPSHOT", "main",
                List.of(),
                Set.of("v1.0.0", "v2.0.0"),
                Set.of("v1.0.0"),
                true);

        Finding f = ReleaseStatusInspector.classify(obs);

        assertThat(f.status()).isEqualTo(Status.IN_FLIGHT);
        assertThat(f.inFlightReleaseBranches()).isEmpty();
        assertThat(f.localOnlyTags()).containsExactly("v2.0.0");
        assertThat(f.details()).anyMatch(d -> d.contains("v2.0.0"));
    }

    @Test
    void diverged_when_release_branch_local_but_tag_already_on_remote() {
        Observation obs = new Observation("lib-a", true, "2.1.0-SNAPSHOT", "main",
                List.of("release/2.0.0"),
                Set.of("v1.0.0", "v2.0.0"),
                Set.of("v1.0.0", "v2.0.0"),
                true);

        Finding f = ReleaseStatusInspector.classify(obs);

        assertThat(f.status()).isEqualTo(Status.DIVERGED);
        assertThat(f.inFlightReleaseBranches()).containsExactly("release/2.0.0");
        // Local tag matches remote tag → not local-only
        assertThat(f.localOnlyTags()).isEmpty();
        assertThat(f.details()).anyMatch(d ->
                d.contains("release/2.0.0") && d.contains("v2.0.0"));
    }

    @Test
    void unreachable_remote_suppresses_local_only_tag_warning() {
        // Local has an untagged-on-origin v2.0.0, but origin couldn't
        // be queried. We must not flag IN_FLIGHT on that basis alone —
        // the local-only check is unreliable when origin is offline.
        Observation obs = new Observation("lib-a", true, "2.1.0-SNAPSHOT", "main",
                List.of(),
                Set.of("v1.0.0", "v2.0.0"),
                Set.of(),  // remote not consulted
                false);    // remote unreachable

        Finding f = ReleaseStatusInspector.classify(obs);

        assertThat(f.status()).isEqualTo(Status.CLEAN);
        assertThat(f.localOnlyTags()).isEmpty();
        assertThat(f.details())
                .anyMatch(d -> d.contains("origin unreachable"));
    }

    @Test
    void unreachable_remote_still_flags_release_branch_in_flight() {
        // Even with origin unreachable, a stray release/* branch is a
        // 100%-local signal that something didn't finish — flag it.
        Observation obs = new Observation("lib-a", true, "2.1.0-SNAPSHOT", "release/2.0.0",
                List.of("release/2.0.0"),
                Set.of("v2.0.0"),
                Set.of(),
                false);

        Finding f = ReleaseStatusInspector.classify(obs);

        assertThat(f.status()).isEqualTo(Status.IN_FLIGHT);
        assertThat(f.inFlightReleaseBranches()).containsExactly("release/2.0.0");
    }

    @Test
    void status_badges_render_distinct_glyphs() {
        // Lock the visual contract — a renderer change shouldn't
        // accidentally collapse two states to the same glyph.
        Set<String> badges = Set.of(
                Status.CLEAN.badge(),
                Status.IN_FLIGHT.badge(),
                Status.DIVERGED.badge(),
                Status.ABSENT.badge());
        assertThat(badges).hasSize(4);
    }
}
