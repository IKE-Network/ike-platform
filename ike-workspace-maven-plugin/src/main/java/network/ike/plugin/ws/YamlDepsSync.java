package network.ike.plugin.ws;

import network.ike.workspace.Dependency;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.Subproject;
import org.apache.maven.api.plugin.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-derive each subproject's {@code depends-on} edges from POM
 * contents and rewrite {@code workspace.yaml} when they have drifted.
 *
 * <p><b>Why.</b> {@code ws:add} derives {@code depends-on} once at
 * add time. POMs change every commit; without a periodic re-derive the
 * YAML graph drifts from POM reality, and {@code ws:overview},
 * {@code ws:release} topo-sort, and {@code ws:cascade} all use the
 * stale graph silently. This sync runs as part of the post-mutation
 * hook so any goal that touches the workspace also leaves the YAML
 * matching the POMs.
 *
 * <p><b>Idempotent.</b> Same POMs in → same YAML out. Re-running
 * back-to-back produces no further change.
 *
 * <p><b>Acyclic by construction.</b> Contracting the module graph to
 * repo granularity can manufacture a cycle no module edge actually
 * forms (a reactor leaf bundling a sibling repo's artifact while that
 * repo builds against other modules — the {@code komet ⇄
 * komet-claude-plugin} shape). A manifest carrying such a cycle is
 * rejected by every graph-consuming {@code ws:} goal, so writing one
 * bricks the workspace. Before writing, the prospective repo-level
 * graph is therefore checked for cycles; on failure nothing is
 * written and the contributing module-level edges are reported with
 * their file locations (IKE-Network/ike-issues#962).
 *
 * <p><b>Safety.</b> Only the {@code depends-on:} block is rewritten,
 * one subproject at a time, via
 * {@link WsAddMojo#rewriteDependsOnBlock}. All other YAML content
 * (comments, defaults, branch fields, version fields) is preserved
 * verbatim.
 *
 * <p>Subprojects that aren't cloned on disk are left untouched —
 * we can't read the POM that drives the derivation.
 *
 * <p>See {@code IKE-Network/ike-issues#279}.
 */
final class YamlDepsSync {

    private YamlDepsSync() {}

    /**
     * Refresh {@code depends-on} edges for the workspace at
     * {@code workspaceRoot}.
     *
     * @param workspaceRoot the workspace root directory
     * @param log           plugin log for the per-subproject summary
     * @return {@code true} if {@code workspace.yaml} was rewritten,
     *         {@code false} if it was already up to date, would have
     *         become cyclic, or could not be processed
     */
    static boolean run(File workspaceRoot, Log log) {
        Path manifestPath = workspaceRoot.toPath().resolve("workspace.yaml");
        if (!Files.isRegularFile(manifestPath)) {
            log.debug("yaml-deps-sync: no workspace.yaml — skipping");
            return false;
        }

        try {
            Manifest manifest = ManifestReader.read(manifestPath);
            String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);

            // ── Phase 1: derive everything; no writes yet ──────────
            //
            // The prospective repo-level graph starts as the current
            // manifest state and is overlaid with each re-derived
            // subproject's new edge set, so the cycle gate below sees
            // exactly what a rewrite would produce.
            Map<String, List<WsAddMojo.DerivedDep>> rederived =
                    new LinkedHashMap<>();
            Map<String, Map<String, List<WsAddMojo.PomRef>>> edgeSources =
                    new LinkedHashMap<>();
            Map<String, Set<String>> prospective = new LinkedHashMap<>();
            Set<String> known = manifest.subprojects().keySet();

            for (Map.Entry<String, Subproject> entry
                    : manifest.subprojects().entrySet()) {
                String name = entry.getKey();
                Subproject sub = entry.getValue();
                prospective.put(name,
                        DependsOnCycleGate.orderingTargets(sub, known));

                Path subDir = workspaceRoot.toPath().resolve(name);
                if (!Files.exists(subDir.resolve("pom.xml"))) {
                    // Not cloned — leave existing depends-on alone
                    continue;
                }

                WsAddMojo.Derivation derivation =
                        WsAddMojo.deriveDependenciesDetailed(
                                workspaceRoot.toPath(), manifestPath,
                                subDir, name);
                List<WsAddMojo.DerivedDep> derived = derivation.deps();

                Set<String> currentDepNames = currentDependsOnNames(sub);
                Set<String> newDepNames = new HashSet<>();
                for (WsAddMojo.DerivedDep d : derived) {
                    newDepNames.add(d.subproject());
                }

                if (currentDepNames.equals(newDepNames)) continue;

                // Dry-run the rewrite against the original YAML: a
                // subproject whose depends-on block cannot be located
                // will not be rewritten, so it must not contribute new
                // edges to the prospective graph either.
                if (WsAddMojo.rewriteDependsOnBlock(yaml, name, derived)
                        .equals(yaml)) {
                    log.debug("yaml-deps-sync: " + name
                            + " — could not locate depends-on block");
                    continue;
                }

                rederived.put(name, derived);
                edgeSources.put(name, derivation.producerSources());
                Set<String> targets = new LinkedHashSet<>(newDepNames);
                targets.retainAll(known);
                prospective.put(name, targets);
            }

            if (rederived.isEmpty()) {
                log.debug("yaml-deps-sync: workspace.yaml is up to date");
                return false;
            }

            // ── Phase 2: acyclicity gate (#962) ────────────────────
            List<String> cycle = DependsOnCycleGate.findCycle(prospective);
            if (!cycle.isEmpty()) {
                log.error(DependsOnCycleGate.diagnostic(
                        workspaceRoot.toPath(), cycle, edgeSources));
                return false;
            }

            // ── Phase 3: apply the rewrites and write once ─────────
            String updated = yaml;
            int totalAdded = 0;
            int totalRemoved = 0;
            for (Map.Entry<String, List<WsAddMojo.DerivedDep>> entry
                    : rederived.entrySet()) {
                String name = entry.getKey();
                List<WsAddMojo.DerivedDep> derived = entry.getValue();

                Set<String> currentDepNames = currentDependsOnNames(
                        manifest.subprojects().get(name));
                Set<String> newDepNames = new HashSet<>();
                for (WsAddMojo.DerivedDep d : derived) {
                    newDepNames.add(d.subproject());
                }

                int added = countOnlyIn(newDepNames, currentDepNames);
                int removed = countOnlyIn(currentDepNames, newDepNames);
                totalAdded += added;
                totalRemoved += removed;

                updated = WsAddMojo.rewriteDependsOnBlock(
                        updated, name, derived);

                List<String> addedNames = new ArrayList<>(newDepNames);
                addedNames.removeAll(currentDepNames);
                List<String> removedNames = new ArrayList<>(currentDepNames);
                removedNames.removeAll(newDepNames);
                log.info("  workspace.yaml: " + name + " depends-on (+"
                        + added + ", -" + removed + ")"
                        + (addedNames.isEmpty() ? ""
                                : " added " + addedNames)
                        + (removedNames.isEmpty() ? ""
                                : " removed " + removedNames));
            }

            if (!updated.equals(yaml)) {
                Files.writeString(manifestPath, updated, StandardCharsets.UTF_8);
                log.info("  yaml-deps-sync: " + totalAdded + " edge(s) added, "
                        + totalRemoved + " edge(s) removed");
                return true;
            }
            log.debug("yaml-deps-sync: workspace.yaml is up to date");
            return false;
        } catch (IOException | ManifestException e) {
            log.warn("yaml-deps-sync: cannot update workspace.yaml — "
                    + e.getMessage());
            return false;
        }
    }

    private static Set<String> currentDependsOnNames(Subproject sub) {
        if (sub.dependsOn() == null) return Set.of();
        Set<String> names = new HashSet<>();
        for (Dependency dep : sub.dependsOn()) {
            names.add(dep.subproject());
        }
        return names;
    }

    private static int countOnlyIn(Set<String> a, Set<String> b) {
        int count = 0;
        for (String s : a) if (!b.contains(s)) count++;
        return count;
    }
}
