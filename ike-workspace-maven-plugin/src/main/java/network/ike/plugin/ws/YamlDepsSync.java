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
 * <p><b>Each member in its own section.</b> A member's edges are written
 * to the block in that member's own section of the manifest, and a
 * member with no {@code depends-on:} key gains one there. The summary
 * the sync returns is read back from the manifest it wrote, member by
 * member, so the re-derivation commit describes its own diff
 * (IKE-Network/ike-issues#1387).
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
     * and one summary line per subproject whose own block the rewrite
     * changed, in manifest order, for the re-derivation commit message
     * (IKE-Network/ike-issues#964, #1387).
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
        Path manifestPath = Manifests.path(workspaceRoot);
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
            for (Map.Entry<String, List<WsAddMojo.DerivedDep>> entry
                    : derivedByName.entrySet()) {
                updated = DependsOnMerge.merge(
                        updated, entry.getKey(), entry.getValue()).yaml();
            }
            if (updated.equals(yaml)) {
                log.debug("yaml-deps-sync: workspace.yaml is up to date");
                return SyncResult.UNCHANGED;
            }
            Files.writeString(manifestPath, updated, StandardCharsets.UTF_8);

            // ── Phase 4: report what was written (#1387) ───────────
            // Each line is read back from the two texts: what a
            // member's own block recorded before against what it
            // records now. A merge step's own account says what was
            // asked of it, which is not what the file holds when the
            // step landed in another member's block or a later step
            // wrote over it.
            int totalAdded = 0;
            int totalRemoved = 0;
            List<String> changeLines = new ArrayList<>();
            for (String name : manifest.subprojects().keySet()) {
                BlockChange change = BlockChange.between(name,
                        DependsOnMerge.recorded(yaml, name),
                        DependsOnMerge.recorded(updated, name));
                if (change == null) continue;
                totalAdded += change.added();
                totalRemoved += change.removed();
                changeLines.add(change.summary());
                if (change.removed() == 0) {
                    log.info("  workspace.yaml: " + change.summary());
                } else {
                    // Removals may be discarding a hand edit — say so
                    // where the user can see it (#964).
                    log.warn("  workspace.yaml: " + change.summary()
                            + " — build edges are re-derived from POMs; "
                            + "a deliberately absent edge needs a non-build "
                            + "relationship (e.g. bundle) to survive");
                }
            }
            log.info("  yaml-deps-sync: " + totalAdded + " edge(s) added, "
                    + totalRemoved + " edge(s) removed");
            return new SyncResult(true, List.copyOf(changeLines));
        } catch (IOException | ManifestException e) {
            log.warn("yaml-deps-sync: cannot update workspace.yaml — "
                    + e.getMessage());
            return SyncResult.UNCHANGED;
        }
    }

    /**
     * What a sync wrote to one member's {@code depends-on} block, as the
     * difference between the block read from the manifest text before
     * and after (IKE-Network/ike-issues#1387).
     *
     * @param summary the member's line for the log and for the
     *                re-derivation commit message
     * @param added   the number of edges the block gained, counting an
     *                edge re-recorded under another relationship
     * @param removed the number of edges the block lost outright
     */
    private record BlockChange(String summary, int added, int removed) {

        /**
         * Compare one member's block before and after a sync.
         *
         * <p>An edge whose relationship moved — a {@code build} edge
         * that derivation now reads as {@code bundle} (#965) — is a
         * supersede, not a discard: it counts as added and is named
         * apart from the removals, so the hand-edit warning on removals
         * (#964) stays honest.
         *
         * @param name   the member
         * @param before the block as the manifest recorded it
         * @param after  the block as the manifest records it now
         * @return the change, or null when the block is the same
         */
        static BlockChange between(String name,
                                   DependsOnMerge.Recorded before,
                                   DependsOnMerge.Recorded after) {
            if (before.equals(after)) return null;

            List<String> added = new ArrayList<>();
            List<String> versioned = new ArrayList<>();
            Map<String, List<String>> moved = new LinkedHashMap<>();
            for (Map.Entry<String, DependsOnMerge.Edge> entry
                    : after.edges().entrySet()) {
                String target = entry.getKey();
                DependsOnMerge.Edge now = entry.getValue();
                DependsOnMerge.Edge was = before.edges().get(target);
                if (was == null) {
                    added.add(target);
                } else if (!was.relationship()
                        .equalsIgnoreCase(now.relationship())) {
                    added.add(target);
                    moved.computeIfAbsent(
                            was.relationship() + "→" + now.relationship(),
                            transition -> new ArrayList<>()).add(target);
                } else if (now.versionProperty() != null
                        && !now.versionProperty()
                                .equals(was.versionProperty())) {
                    versioned.add(target);
                }
            }
            List<String> removed = new ArrayList<>();
            for (String target : before.edges().keySet()) {
                if (!after.edges().containsKey(target)) removed.add(target);
            }

            boolean created = !before.present() && after.present();
            if (added.isEmpty() && removed.isEmpty() && versioned.isEmpty()) {
                // No edge changed: a block written for a member that
                // has no edges, or the managed marker on an existing one.
                return new BlockChange(name + (created
                        ? ": add managed block, no edges"
                        : ": annotate managed block"), 0, 0);
            }
            StringBuilder summary = new StringBuilder(name)
                    .append(" depends-on (+").append(added.size())
                    .append(", -").append(removed.size()).append(')');
            if (!added.isEmpty()) summary.append(" added ").append(added);
            if (!removed.isEmpty()) {
                summary.append(" removed ").append(removed);
            }
            for (Map.Entry<String, List<String>> transition
                    : moved.entrySet()) {
                summary.append(' ').append(transition.getKey())
                        .append(' ').append(transition.getValue());
            }
            if (!versioned.isEmpty()) {
                summary.append(" version-property ").append(versioned);
            }
            if (created) summary.append(" in a new block");
            return new BlockChange(summary.toString(),
                    added.size(), removed.size());
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
