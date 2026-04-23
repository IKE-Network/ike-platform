package network.ike.plugin.ws;

import network.ike.workspace.Defaults;
import network.ike.workspace.Dependency;
import network.ike.workspace.IdeSettings;
import network.ike.workspace.Manifest;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WsReleaseDraftMojo#computeReleaseSet} — the pure
 * function at the heart of the #192 cascade semantics.
 *
 * <p>Acceptance criteria from #192 covered here:
 * <ul>
 *   <li>Release set computation includes transitive downstream of
 *       source-changed subprojects</li>
 *   <li>Catch-up does not expand the release set — a subproject with
 *       only stale properties (no source changes, no upstream in this
 *       cycle) stays out</li>
 * </ul>
 */
class WsReleaseCascadeTest {

    // ── Cascade includes transitive downstream ─────────────────────

    @Test
    void cascade_aChangedCDependsOnA_releasesAandC() {
        // Workspace: A leaf, C depends on A. Only A has source changes.
        // Release set must be {A, C} so C's POM gets A's new version.
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("C", "A"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("A"));

        assertThat(set).containsExactly("A", "C");
    }

    @Test
    void cascade_threeSubprojectChain_releasesAllDownstream() {
        // A → B → C, only A changed. Release set: {A, B, C}.
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("B", "A"),
                node("C", "B"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("A"));

        assertThat(set).containsExactly("A", "B", "C");
    }

    @Test
    void cascade_midGraphChange_releasesOnlyDownstreamOfChange() {
        // A → B → C. Only B changed. Release set: {B, C}. A stays out
        // — catch-up is downstream-only, not retroactive to upstream.
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("B", "A"),
                node("C", "B"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("B"));

        assertThat(set).containsExactly("B", "C");
    }

    @Test
    void cascade_diamondShape_includesAllReachable() {
        // A → B, A → C, B → D, C → D. Only A changed.
        // Release set must include all four: A, B, C, D.
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("B", "A"),
                node("C", "A"),
                node("D", "B", "C"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("A"));

        assertThat(set).containsExactlyInAnyOrder("A", "B", "C", "D");
        // Topological order: A first, D last; B and C in between.
        assertThat(set).first().isEqualTo("A");
        assertThat(set).last().isEqualTo("D");
    }

    @Test
    void cascade_multipleSourceChanged_unionsTheirDownstreams() {
        // A → C, B → D, A and B both changed.
        // Release set: {A, B, C, D}.
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("B"),
                node("C", "A"),
                node("D", "B"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("A", "B"));

        assertThat(set).containsExactlyInAnyOrder("A", "B", "C", "D");
    }

    // ── Catch-up does not expand release set ────────────────────────

    @Test
    void catchUp_doesNotExpandReleaseSet_unrelatedSubprojectStaysOut() {
        // {A → C, B → C, only A changed}. C cascades because of A.
        // B has stale property to C (now releasing) but B has no
        // source change and no upstream in cycle → B stays out.
        // Release set must be {A, C}, not {A, B, C}.
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("B"),
                node("C", "A", "B"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("A"));

        assertThat(set).containsExactly("A", "C");
        assertThat(set).doesNotContain("B");
    }

    @Test
    void catchUp_independentSubprojectStaysOut() {
        // A → C, B is independent. Only A changed.
        // Release set: {A, C}. B has no relation to A and stays out.
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("B"),
                node("C", "A"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("A"));

        assertThat(set).containsExactly("A", "C");
        assertThat(set).doesNotContain("B");
    }

    @Test
    void catchUp_staleUpstreamButNoCycleMember_staysOut() {
        // A → B, A → C. Only B has source changes (B is a leaf-ish).
        // Release set: {B}. C has stale A.version property (A != A's
        // current pom version) but A is not in cycle and C has no
        // source changes → C stays out.
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("B", "A"),
                node("C", "A"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("B"));

        assertThat(set).containsExactly("B");
        assertThat(set).doesNotContain("A", "C");
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    void emptySourceChanged_emptyReleaseSet() {
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("B", "A"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of());

        assertThat(set).isEmpty();
    }

    @Test
    void leafSourceChanged_onlyItself() {
        // A is a leaf with nothing depending on it.
        WorkspaceGraph graph = graphOf(node("A"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("A"));

        assertThat(set).containsExactly("A");
    }

    @Test
    void releaseSetIsTopologicallyOrdered() {
        // A → B → C. All three source-changed.
        // Result must be in topo order: A, B, C — never B, A, C etc.
        WorkspaceGraph graph = graphOf(
                node("A"),
                node("B", "A"),
                node("C", "B"));

        Set<String> set = WsReleaseDraftMojo.computeReleaseSet(
                graph, Set.of("C", "A", "B")); // input order shouldn't matter

        assertThat(set).containsExactly("A", "B", "C");
    }

    // ── Test fixture builders ───────────────────────────────────────

    /**
     * Build a WorkspaceGraph from a list of node specs. Each spec
     * is the subproject name plus the names of its upstream
     * dependencies (which become depends-on edges with relationship
     * "build").
     */
    private static WorkspaceGraph graphOf(NodeSpec... nodes) {
        Map<String, Subproject> subprojects = new LinkedHashMap<>();
        for (NodeSpec spec : nodes) {
            List<Dependency> deps = spec.dependsOn.stream()
                    .map(name -> new Dependency(name, "build", null))
                    .toList();
            Subproject sub = new Subproject(
                    spec.name,
                    spec.name + " (test)",
                    "https://example.com/" + spec.name + ".git",
                    "main",
                    "1.0.0-SNAPSHOT",
                    "com.test",
                    deps,
                    null,
                    null,
                    null,
                    null);
            subprojects.put(spec.name, sub);
        }
        Manifest manifest = new Manifest(
                "1.0", "2026-04-21",
                new Defaults("main", "4.0.0-rc-5"),
                subprojects,
                IdeSettings.EMPTY);
        return new WorkspaceGraph(manifest);
    }

    private static NodeSpec node(String name, String... dependsOn) {
        return new NodeSpec(name, List.of(dependsOn));
    }

    private record NodeSpec(String name, List<String> dependsOn) {}
}
