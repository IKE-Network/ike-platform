package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.Subproject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <p><b>Merge, not replace.</b> Derivation only knows {@code build}
 * edges, so only build edges are machine-owned: hand-declared entries
 * with any other relationship ({@code bundle}, {@code content},
 * {@code tooling}) are preserved verbatim — comments included — and
 * are never downgraded to {@code build}
 * (IKE-Network/ike-issues#963, #964). See {@link DependsOnMerge}.
 *
 * <p><b>Acyclic by construction.</b> Contracting the module graph to
 * repo granularity can manufacture a cycle no module edge actually
 * forms. Before writing, the prospective repo-level ordering graph is
 * checked; on a cycle nothing is written and the contributing
 * module-level edges are reported with file locations
 * (IKE-Network/ike-issues#962, {@link DependsOnCycleGate}).
 *
 * <p>Subprojects that aren't cloned on disk are left untouched —
 * we can't read the POM that drives the derivation.
 *
 * <p>Subprojects whose checkout <em>drifts</em> from the manifest —
 * a different branch than the entry declares, or a detached HEAD away
 * from the {@code sha:} pin — are likewise left untouched: the POM on
 * disk does not represent the manifest's declared state, and deriving
 * from it strips edges that are real on the declared branch
 * (IKE-Network/ike-issues#968). A checkout without git metadata
 * derives as before — branch verification needs a repository.
 *
 * <p>See {@code IKE-Network/ike-issues#279}.
 */
final class YamlDepsSync {

    private YamlDepsSync() {}

    /**
     * Outcome of a sync: whether {@code workspace.yaml} was rewritten,
     * and one summary line per changed subproject for the re-derivation
     * commit message (IKE-Network/ike-issues#964).
     */
    record SyncResult(boolean changed, List<String> changeLines) {

        static final SyncResult UNCHANGED =
                new SyncResult(false, List.of());
    }

    /**
     * Refresh {@code depends-on} edges for the workspace at
     * {@code workspaceRoot}.
     *
     * @param workspaceRoot the workspace root directory
     * @param log           plugin log for the per-subproject summary
     * @return the sync outcome; unchanged when the manifest was already
     *         up to date, would have become cyclic, or could not be
     *         processed
     */
    static SyncResult run(File workspaceRoot, Log log) {
        Path manifestPath = workspaceRoot.toPath().resolve("workspace.yaml");
        if (!Files.isRegularFile(manifestPath)) {
            log.debug("yaml-deps-sync: no workspace.yaml — skipping");
            return SyncResult.UNCHANGED;
        }

        try {
            Manifest manifest = ManifestReader.read(manifestPath);
            String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);

            // ── Phase 1: derive and dry-run the merges; no writes ──
            Map<String, List<WsAddMojo.DerivedDep>> derivedByName =
                    new LinkedHashMap<>();
            Map<String, Map<String, List<WsAddMojo.PomRef>>> edgeSources =
                    new LinkedHashMap<>();
            Map<String, Set<String>> prospective = new LinkedHashMap<>();
            Set<String> known = manifest.subprojects().keySet();
            List<String> driftSkipped = new ArrayList<>();

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
                if (Files.exists(subDir.resolve(".git"))) {
                    String drift = driftReason(subDir,
                            declaredBranch(sub, manifest), sub.sha());
                    if (drift != null) {
                        // Drifted checkout — its POM does not represent
                        // the manifest's declared state (#968).
                        driftSkipped.add(name + " — " + drift);
                        continue;
                    }
                }

                WsAddMojo.Derivation derivation =
                        WsAddMojo.deriveDependenciesDetailed(
                                workspaceRoot.toPath(), manifestPath,
                                subDir, name);

                DependsOnMerge.Result merged = DependsOnMerge.merge(
                        yaml, name, derivation.deps());
                if (!merged.changed(yaml)) continue;

                derivedByName.put(name, derivation.deps());
                edgeSources.put(name, derivation.producerSources());
                prospective.put(name,
                        orderingTargets(merged.finalRelationships(), known));
            }

            if (!driftSkipped.isEmpty()) {
                log.warn("  yaml-deps-sync: left " + driftSkipped.size()
                        + " drifted subproject(s) alone — the checkout "
                        + "disagrees with the manifest, so its POM does "
                        + "not represent the declared state; recorded "
                        + "depends-on kept (IKE-Network/ike-issues#968):");
                for (String skipped : driftSkipped) {
                    log.warn("    " + skipped);
                }
                log.warn("    Align checkouts to the manifest: "
                        + "mvn ws:reconcile-branches-publish -Dfrom=manifest");
                log.warn("    Or accept checkouts as truth:    "
                        + "mvn ws:reconcile-branches-publish");
            }

            if (derivedByName.isEmpty()) {
                log.debug("yaml-deps-sync: workspace.yaml is up to date");
                return SyncResult.UNCHANGED;
            }

            // ── Phase 2: acyclicity gate (#962) ────────────────────
            List<String> cycle = DependsOnCycleGate.findCycle(prospective);
            if (!cycle.isEmpty()) {
                log.error(DependsOnCycleGate.diagnostic(
                        workspaceRoot.toPath(), cycle, edgeSources));
                return SyncResult.UNCHANGED;
            }

            // ── Phase 3: apply the merges and write once ───────────
            String updated = yaml;
            int totalAdded = 0;
            int totalRemoved = 0;
            List<String> changeLines = new ArrayList<>();
            for (Map.Entry<String, List<WsAddMojo.DerivedDep>> entry
                    : derivedByName.entrySet()) {
                String name = entry.getKey();
                DependsOnMerge.Result merged = DependsOnMerge.merge(
                        updated, name, entry.getValue());
                updated = merged.yaml();

                List<String> added = new ArrayList<>(merged.addedBuild());
                added.addAll(merged.addedBundle());
                // A build edge whose derivation moved to bundle is a
                // supersede (#965), not a discard — report it apart from
                // true removals so the #964 hand-edit warning stays honest.
                List<String> superseded =
                        new ArrayList<>(merged.removedBuild());
                superseded.retainAll(merged.addedBundle());
                List<String> removed =
                        new ArrayList<>(merged.removedBuild());
                removed.removeAll(superseded);
                totalAdded += added.size();
                totalRemoved += removed.size();

                if (added.isEmpty() && removed.isEmpty()) {
                    // Text-only change: managed-marker annotation.
                    log.info("  workspace.yaml: " + name
                            + " depends-on annotated as managed");
                    changeLines.add(name + ": annotate managed block");
                    continue;
                }
                String summary = name + " depends-on (+" + added.size()
                        + ", -" + removed.size() + ")"
                        + (added.isEmpty() ? "" : " added " + added)
                        + (removed.isEmpty() ? "" : " removed " + removed)
                        + (superseded.isEmpty()
                                ? "" : " build→bundle " + superseded);
                changeLines.add(summary);
                if (removed.isEmpty()) {
                    log.info("  workspace.yaml: " + summary);
                } else {
                    // Removals may be discarding a hand edit — say so
                    // where the user can see it (#964).
                    log.warn("  workspace.yaml: " + summary
                            + " — build edges are re-derived from POMs; "
                            + "a deliberately absent edge needs a non-build "
                            + "relationship (e.g. bundle) to survive");
                }
            }

            if (!updated.equals(yaml)) {
                Files.writeString(manifestPath, updated, StandardCharsets.UTF_8);
                log.info("  yaml-deps-sync: " + totalAdded + " edge(s) added, "
                        + totalRemoved + " edge(s) removed");
                return new SyncResult(true, List.copyOf(changeLines));
            }
            log.debug("yaml-deps-sync: workspace.yaml is up to date");
            return SyncResult.UNCHANGED;
        } catch (IOException | ManifestException e) {
            log.warn("yaml-deps-sync: cannot update workspace.yaml — "
                    + e.getMessage());
            return SyncResult.UNCHANGED;
        }
    }

    /**
     * The branch the manifest declares for a subproject: its own
     * {@code branch:} field, falling back to {@code defaults.branch}.
     *
     * @param sub      the subproject entry
     * @param manifest the parsed manifest
     * @return the declared branch, or null when neither is set
     */
    private static String declaredBranch(Subproject sub, Manifest manifest) {
        if (sub.branch() != null && !sub.branch().isBlank()) {
            return sub.branch();
        }
        return manifest.defaults() == null
                ? null : manifest.defaults().branch();
    }

    /**
     * Why a cloned subproject's checkout cannot back a re-derivation,
     * or null when it can (IKE-Network/ike-issues#968).
     *
     * <p>A checkout on a different branch than the manifest declares —
     * or detached anywhere but the manifest's {@code sha:} pin — is the
     * same epistemic situation as an absent clone: the POM on disk does
     * not represent the declared state. Unreadable git state counts as
     * drift for the same reason.
     *
     * @param subDir         the subproject checkout directory
     * @param declaredBranch the manifest branch (may be null)
     * @param pinnedSha      the manifest {@code sha:} pin (may be null)
     * @return a human-readable drift description, or null when coherent
     */
    private static String driftReason(Path subDir, String declaredBranch,
                                      String pinnedSha) {
        try {
            String checkout = VcsOperations.currentBranch(subDir.toFile());
            if (checkout == null || checkout.isBlank()) {
                String head = VcsOperations.headSha(subDir.toFile());
                if (pinnedSha != null && pinnedSha.startsWith(head)) {
                    // Parked exactly on the manifest pin — coherent.
                    return null;
                }
                return "detached at " + head
                        + (declaredBranch == null ? ""
                                : ", manifest declares " + declaredBranch);
            }
            if (declaredBranch != null && !declaredBranch.isBlank()
                    && !declaredBranch.equals(checkout)) {
                return "checkout on " + checkout
                        + ", manifest declares " + declaredBranch;
            }
            return null;
        } catch (MojoException e) {
            return "git state unreadable (" + e.getMessage() + ")";
        }
    }

    /**
     * Ordering-edge targets of a merged entry set: every final entry
     * except {@code relationship: bundle}, restricted to known
     * subprojects — the same rule as
     * {@link DependsOnCycleGate#orderingTargets}.
     */
    private static Set<String> orderingTargets(
            Map<String, String> finalRelationships, Set<String> known) {
        Set<String> targets = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : finalRelationships.entrySet()) {
            if ("bundle".equalsIgnoreCase(e.getValue())) continue;
            if (known.contains(e.getKey())) targets.add(e.getKey());
        }
        return targets;
    }
}
