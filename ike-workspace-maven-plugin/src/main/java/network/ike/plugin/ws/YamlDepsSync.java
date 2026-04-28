package network.ike.plugin.ws;

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
     */
    static void run(File workspaceRoot, Log log) {
        Path manifestPath = workspaceRoot.toPath().resolve("workspace.yaml");
        if (!Files.isRegularFile(manifestPath)) {
            log.debug("yaml-deps-sync: no workspace.yaml — skipping");
            return;
        }

        try {
            Manifest manifest = ManifestReader.read(manifestPath);
            String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);
            String updated = yaml;
            int totalAdded = 0;
            int totalRemoved = 0;

            for (Map.Entry<String, Subproject> entry
                    : manifest.subprojects().entrySet()) {
                String name = entry.getKey();
                Subproject sub = entry.getValue();
                Path subDir = workspaceRoot.toPath().resolve(name);
                if (!Files.exists(subDir.resolve("pom.xml"))) {
                    // Not cloned — leave existing depends-on alone
                    continue;
                }

                List<WsAddMojo.DerivedDep> derived =
                        WsAddMojo.deriveDependencies(
                                workspaceRoot.toPath(), manifestPath,
                                subDir, name);
                if (derived == null) {
                    derived = List.of();
                }

                Set<String> currentDepNames = currentDependsOnNames(sub);
                Set<String> newDepNames = new HashSet<>();
                for (WsAddMojo.DerivedDep d : derived) {
                    newDepNames.add(d.subproject());
                }

                if (currentDepNames.equals(newDepNames)) continue;

                int added = countOnlyIn(newDepNames, currentDepNames);
                int removed = countOnlyIn(currentDepNames, newDepNames);
                totalAdded += added;
                totalRemoved += removed;

                String before = updated;
                updated = WsAddMojo.rewriteDependsOnBlock(
                        updated, name, derived);
                if (before.equals(updated)) {
                    // Subproject not present in YAML in a recognizable
                    // form — likely a freshly added entry without a
                    // depends-on block yet. Skip rather than guess.
                    log.debug("yaml-deps-sync: " + name
                            + " — could not locate depends-on block");
                    continue;
                }

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
            } else {
                log.debug("yaml-deps-sync: workspace.yaml is up to date");
            }
        } catch (IOException | ManifestException e) {
            log.warn("yaml-deps-sync: cannot update workspace.yaml — "
                    + e.getMessage());
        }
    }

    private static Set<String> currentDependsOnNames(Subproject sub) {
        if (sub.dependsOn() == null) return Set.of();
        Set<String> names = new HashSet<>();
        for (var dep : sub.dependsOn()) {
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
