package network.ike.plugin.ws;

import network.ike.workspace.Dependency;
import network.ike.workspace.Manifest;
import network.ike.workspace.Subproject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Acyclicity gate for the repo-level {@code depends-on} graph that
 * {@code workspace.yaml} derivation is about to write
 * (IKE-Network/ike-issues#962).
 *
 * <p>Contracting the module graph to repo granularity can manufacture
 * a cycle that no module edge actually forms — a reactor leaf bundling
 * a sibling repo's artifact while that repo builds against other
 * modules of the same reactor (the {@code komet ⇄ komet-claude-plugin}
 * shape). A manifest carrying such a cycle is rejected by every
 * graph-consuming {@code ws:} goal, blocking the whole workspace, so
 * derivation must detect the cycle <em>before</em> writing and report
 * the contributing module-level edges instead.
 *
 * <p>Only build-ordering edges participate: {@code relationship:
 * bundle} entries (package-time bundling of a repository-resolved
 * artifact, IKE-Network/ike-issues#963) order nothing and are skipped.
 *
 * <p>Used by {@link YamlDepsSync} (re-derivation post-step, which
 * warns and leaves the manifest untouched) and {@code ws:add}
 * (which restores the pre-add files and fails the goal).
 */
final class DependsOnCycleGate {

    private DependsOnCycleGate() {}

    /**
     * Build the repo-level build-ordering graph of a manifest: every
     * subproject mapped to its {@code depends-on} targets, minus
     * {@code bundle} edges and targets not present in the manifest.
     *
     * @param manifest the parsed workspace manifest
     * @return name → ordering-edge targets, in manifest order
     */
    static Map<String, Set<String>> orderingGraph(Manifest manifest) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Set<String> known = manifest.subprojects().keySet();
        for (Map.Entry<String, Subproject> entry
                : manifest.subprojects().entrySet()) {
            graph.put(entry.getKey(),
                    orderingTargets(entry.getValue(), known));
        }
        return graph;
    }

    /**
     * The subproject's build-ordering edge targets: every
     * {@code depends-on} entry except {@code relationship: bundle},
     * restricted to targets that exist in the manifest.
     *
     * @param sub   the subproject whose edges to collect
     * @param known all manifest subproject names
     * @return ordering-edge targets in declaration order
     */
    static Set<String> orderingTargets(Subproject sub, Set<String> known) {
        Set<String> targets = new LinkedHashSet<>();
        if (sub.dependsOn() == null) return targets;
        for (Dependency dep : sub.dependsOn()) {
            if ("bundle".equalsIgnoreCase(dep.relationship())) continue;
            if (known.contains(dep.subproject())) {
                targets.add(dep.subproject());
            }
        }
        return targets;
    }

    /**
     * Find a cycle in a repo-level graph. Iterative DFS with
     * three-color marking, mirroring {@code WorkspaceGraph.detectCycle}.
     *
     * @param edges name → edge targets
     * @return the cycle as a closed path (first element repeated last),
     *         or an empty list when the graph is acyclic
     */
    static List<String> findCycle(Map<String, Set<String>> edges) {
        Set<String> white = new LinkedHashSet<>(edges.keySet());
        Set<String> gray = new LinkedHashSet<>();
        Map<String, String> parent = new LinkedHashMap<>();

        for (String start : edges.keySet()) {
            if (!white.contains(start)) continue;

            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);

            while (!stack.isEmpty()) {
                String current = stack.peek();

                if (white.remove(current)) {
                    gray.add(current);
                    for (String dep : edges.getOrDefault(current, Set.of())) {
                        if (gray.contains(dep)) {
                            return reconstructCycle(parent, current, dep);
                        }
                        if (white.contains(dep)) {
                            parent.put(dep, current);
                            stack.push(dep);
                        }
                    }
                } else {
                    stack.pop();
                    gray.remove(current);
                }
            }
        }
        return List.of();
    }

    private static List<String> reconstructCycle(Map<String, String> parent,
                                                 String from, String to) {
        List<String> cycle = new ArrayList<>();
        cycle.add(to);
        String current = from;
        while (!current.equals(to)) {
            cycle.add(current);
            current = parent.getOrDefault(current, to);
        }
        cycle.add(to);
        Collections.reverse(cycle);
        return cycle;
    }

    /**
     * Render the abort diagnostic: the repo-level cycle plus, for each
     * edge with source attribution, the module POM references that
     * create it — file:line and coordinate.
     *
     * @param workspaceRoot workspace root, for relative display paths
     * @param cycle         closed cycle path from {@link #findCycle}
     * @param edgeSources   from-subproject → (to-subproject → refs);
     *                      edges without attribution are reported as
     *                      declared in workspace.yaml
     * @return the multi-line diagnostic
     */
    static String diagnostic(
            Path workspaceRoot,
            List<String> cycle,
            Map<String, Map<String, List<WsAddMojo.PomRef>>> edgeSources) {
        StringBuilder d = new StringBuilder();
        d.append("depends-on derivation would write a repo-level ")
                .append("dependency cycle: ")
                .append(String.join(" -> ", cycle))
                .append('\n');
        for (int i = 0; i < cycle.size() - 1; i++) {
            String from = cycle.get(i);
            String to = cycle.get(i + 1);
            d.append("  ").append(from).append(" -> ").append(to).append('\n');
            List<WsAddMojo.PomRef> refs = edgeSources
                    .getOrDefault(from, Map.of()).get(to);
            if (refs == null || refs.isEmpty()) {
                d.append("      declared in workspace.yaml ")
                        .append("(not re-derived in this run)\n");
                continue;
            }
            int shown = 0;
            for (WsAddMojo.PomRef ref : refs) {
                if (shown == 3) {
                    d.append("      ... and ").append(refs.size() - 3)
                            .append(" more\n");
                    break;
                }
                d.append("      from ")
                        .append(location(workspaceRoot, ref))
                        .append(" (").append(ref.coordinate()).append(")\n");
                shown++;
            }
        }
        d.append("workspace.yaml left unchanged — a manifest with this cycle ")
                .append("would block every ws: goal. If an edge bundles a ")
                .append("repository-resolved artifact rather than ordering ")
                .append("the build, declare it 'relationship: bundle' ")
                .append("(IKE-Network/ike-issues#963); ")
                .append("see IKE-Network/ike-issues#962.");
        return d.toString();
    }

    /**
     * Workspace-relative {@code path:line} for a POM reference, the
     * line being the first occurrence of the referenced artifactId in
     * the file (best effort — omitted when not found).
     */
    private static String location(Path workspaceRoot, WsAddMojo.PomRef ref) {
        Path file = ref.pomFile();
        String display;
        try {
            display = workspaceRoot.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            display = file.toString();
        }
        int colon = ref.coordinate().indexOf(':');
        String artifactId = colon < 0
                ? ref.coordinate()
                : ref.coordinate().substring(colon + 1);
        int line = lineOf(file, "<artifactId>" + artifactId + "</artifactId>");
        return line > 0 ? display + ":" + line : display;
    }

    /** 1-based line of the first occurrence of {@code needle}, or 0. */
    private static int lineOf(Path file, String needle) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) return i + 1;
            }
        } catch (IOException e) {
            // best effort — fall through
        }
        return 0;
    }
}
