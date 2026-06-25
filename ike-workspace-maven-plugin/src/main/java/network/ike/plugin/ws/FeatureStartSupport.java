package network.ike.plugin.ws;

import network.ike.plugin.PomRewriter;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.BomAnalysis;
import network.ike.workspace.Dependency;
import network.ike.workspace.PublishedArtifactSet;
import network.ike.workspace.Subproject;
import network.ike.workspace.VersionSupport;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reusable helpers for {@code ws:feature-start} version qualification
 * and BOM/property cascade — extracted from {@link FeatureStartDraftMojo}
 * so they can be shared by sibling-clone work (ike-issues#201).
 *
 * <p>This is a pure mechanical extraction (ike-issues#204): every method
 * was lifted unchanged from {@code FeatureStartDraftMojo}, with
 * {@code getLog()} replaced by the injected {@link Log} field. No
 * behavior changes.
 *
 * <p>Each method takes the same parameters as the original and reads
 * the same workspace state — there is no instance state besides the
 * logger.
 */
final class FeatureStartSupport {

    private final Log log;

    /**
     * @param log Maven logger, used for all human-facing output and for
     *            forwarding to {@link ReleaseSupport} / git subprocess
     *            invocations
     */
    FeatureStartSupport(Log log) {
        this.log = log;
    }

    // ── Version setting ──────────────────────────────────────────

    /**
     * Set the POM version, handling both simple and multi-module projects.
     * Uses ReleaseSupport's POM manipulation which skips the parent block.
     *
     * @param dir        the subproject root directory (containing {@code pom.xml})
     * @param oldVersion current POM version (used to find replacements)
     * @param newVersion target POM version
     * @throws MojoException if the rewrite or git operations fail
     */
    void setPomVersion(File dir, String oldVersion, String newVersion)
            throws MojoException {
        File pom = new File(dir, "pom.xml");
        if (!pom.exists()) {
            log.warn("    No pom.xml found in " + dir.getName());
            return;
        }

        // Set version in root POM
        ReleaseSupport.setPomVersion(pom, oldVersion, newVersion);

        // Also update any submodule POMs that reference the old version
        // in their <parent> block (for multi-module projects)
        try {
            List<File> allPoms = ReleaseSupport.findPomFiles(dir);
            for (File subPom : allPoms) {
                if (subPom.equals(pom)) continue;
                try {
                    String content = Files.readString(
                            subPom.toPath(), StandardCharsets.UTF_8);
                    if (content.contains("<version>" + oldVersion + "</version>")) {
                        String updated = content.replace(
                                "<version>" + oldVersion + "</version>",
                                "<version>" + newVersion + "</version>");
                        Files.writeString(
                                subPom.toPath(), updated,
                                StandardCharsets.UTF_8);
                        String rel = dir.toPath().relativize(subPom.toPath()).toString();
                        log.info("    updated: " + rel);
                        ReleaseSupport.exec(dir, log, "git", "add", rel);
                    }
                } catch (IOException e) {
                    log.warn("    Could not update " + subPom + ": " + e.getMessage());
                }
            }
        } catch (MojoException e) {
            log.warn("    Could not scan for submodule POMs: " + e.getMessage());
        }
    }

    // ── Version-property cascade (declared in workspace.yaml) ────

    /**
     * Cascade version-property updates to downstream components.
     *
     * <p>When an upstream subproject's version changes (e.g., tinkar-core
     * gets a branch-qualified version), downstream components that track
     * that version via a POM property (declared as {@code version-property}
     * in workspace.yaml) need their property updated too.
     *
     * <p>For example, if rocks-kb depends on tinkar-core with
     * {@code version-property: ike-bom.version}, and tinkar-core's version
     * changed to {@code 1.127.2-feature-foo-SNAPSHOT}, then rocks-kb's
     * {@code <ike-bom.version>} property is updated to match.
     *
     * @param graph      the workspace dependency graph
     * @param root       workspace root directory
     * @param sorted     subprojects in topological order
     * @param branchName the feature branch name (e.g., {@code feature/foo})
     * @throws MojoException if a per-subproject git operation fails
     */
    void cascadeVersionProperties(WorkspaceGraph graph, File root,
                                  List<String> sorted, String branchName)
            throws MojoException {

        // Build map of upstream subproject → new branch-qualified version
        Map<String, String> newVersions = new LinkedHashMap<>();
        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            if (sub.version() != null && !sub.version().isEmpty()) {
                newVersions.put(name, VersionSupport.branchQualifiedVersion(
                        sub.version(), branchName));
            }
        }

        // For each subproject in topological order, update version-properties
        // that reference upstream subprojects
        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);
            File pomFile = new File(dir, "pom.xml");
            if (!pomFile.exists()) continue;

            try {
                String content = Files.readString(
                        pomFile.toPath(), StandardCharsets.UTF_8);
                String original = content;

                for (Dependency dep : sub.dependsOn()) {
                    String upstreamName = dep.subproject();
                    if (dep.versionProperty() == null) continue;
                    if (!newVersions.containsKey(upstreamName)) continue;

                    String upstreamVersion = newVersions.get(upstreamName);
                    String before = content;
                    content = PomRewriter.updateProperty(
                            content, dep.versionProperty(), upstreamVersion);

                    if (!content.equals(before)) {
                        log.info("    " + name + ": " + dep.versionProperty()
                                + " → " + upstreamVersion
                                + " (from " + upstreamName + ")");
                    }
                }

                if (!content.equals(original)) {
                    Files.writeString(
                            pomFile.toPath(), content,
                            StandardCharsets.UTF_8);
                    ReleaseSupport.exec(dir, log, "git", "add", "pom.xml");
                    ReleaseSupport.exec(dir, log, "git", "commit", "-m",
                            "feature: update dependency versions for " + branchName);
                }
            } catch (IOException e) {
                log.warn("    Could not cascade version properties in "
                        + name + ": " + e.getMessage());
            }
        }
    }

    // ── BOM property cascade (by subproject name convention) ─────

    /**
     * Cascade branch-qualified versions into POM properties that match
     * workspace subproject names.
     *
     * <p>Scans each subproject's root POM {@code <properties>} block for
     * entries like {@code <tinkar-core.version>1.0.0-SNAPSHOT</tinkar-core.version>}
     * where "tinkar-core" matches a workspace subproject name. Updates
     * these properties to the branch-qualified version.
     *
     * <p>This complements {@link #cascadeVersionProperties} which only
     * handles properties explicitly declared via {@code version-property}
     * in workspace.yaml dependency entries.
     *
     * @param graph      the workspace dependency graph
     * @param root       workspace root directory
     * @param sorted     subprojects in topological order
     * @param branchName the feature branch name
     * @throws MojoException if a per-subproject git operation fails
     */
    void cascadeBomProperties(WorkspaceGraph graph, File root,
                              List<String> sorted, String branchName)
            throws MojoException {

        // Build map of subproject name → new branch-qualified version
        Map<String, String> newVersions = new LinkedHashMap<>();
        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            String effectiveVersion = sub.version();
            if (effectiveVersion == null || effectiveVersion.isEmpty()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (pom.exists()) {
                    try {
                        effectiveVersion = ReleaseSupport.readPomVersion(pom);
                    } catch (MojoException e) { /* skip */ }
                }
            }
            if (effectiveVersion != null && !effectiveVersion.isEmpty()) {
                newVersions.put(name, VersionSupport.branchQualifiedVersion(
                        effectiveVersion, branchName));
            }
        }

        // For each subproject, check its POM properties for references
        // to other workspace subprojects (e.g., <tinkar-core.version>)
        for (String name : sorted) {
            File dir = new File(root, name);
            File pomFile = new File(dir, "pom.xml");
            if (!pomFile.exists()) continue;

            try {
                String content = Files.readString(
                        pomFile.toPath(), StandardCharsets.UTF_8);
                String original = content;

                for (Map.Entry<String, String> vEntry : newVersions.entrySet()) {
                    String subName = vEntry.getKey();
                    if (subName.equals(name)) continue;

                    String propertyName = subName + ".version";
                    String before = content;
                    content = PomRewriter.updateProperty(
                            content, propertyName, vEntry.getValue());

                    if (!content.equals(before)) {
                        log.info("    " + name + ": <" + propertyName
                                + "> → " + vEntry.getValue());
                    }
                }

                if (!content.equals(original)) {
                    Files.writeString(
                            pomFile.toPath(), content,
                            StandardCharsets.UTF_8);
                    ReleaseSupport.exec(dir, log, "git", "add", "pom.xml");
                    ReleaseSupport.exec(dir, log, "git", "commit", "-m",
                            "feature: update BOM properties for " + branchName);
                }
            } catch (IOException e) {
                log.warn("    Could not cascade BOM properties in "
                        + name + ": " + e.getMessage());
            }
        }
    }

    // ── BOM-import cascade ───────────────────────────────────────

    /**
     * Cascade BOM-import version updates to downstream subprojects.
     *
     * <p>For each subproject in topological order, scans its POMs for
     * BOM imports ({@code <scope>import</scope>} + {@code <type>pom</type>})
     * whose {@code groupId:artifactId} matches an artifact published by
     * an upstream workspace subproject. When the upstream subproject's
     * version changed for the feature branch, the BOM import version is
     * rewritten to match.
     *
     * @param graph      the workspace dependency graph
     * @param root       workspace root directory
     * @param sorted     subprojects in topological order
     * @param branchName the feature branch name
     * @throws MojoException if a per-subproject git operation fails
     */
    void cascadeBomImports(WorkspaceGraph graph, File root,
                           List<String> sorted, String branchName)
            throws MojoException {
        // Build published artifact sets and new version map
        Map<String, Set<PublishedArtifactSet.Artifact>> workspaceArtifacts =
                new LinkedHashMap<>();
        Map<String, String> newVersions = new LinkedHashMap<>();

        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            Path subDir = root.toPath().resolve(name);

            if (Files.exists(subDir.resolve("pom.xml"))) {
                try {
                    workspaceArtifacts.put(name,
                            PublishedArtifactSet.scan(subDir));
                } catch (IOException e) {
                    // Skip
                }
            }

            // Resolve effective version (same logic as the branching loop)
            String effectiveVersion = sub.version();
            if (effectiveVersion == null || effectiveVersion.isEmpty()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (pom.exists()) {
                    try {
                        effectiveVersion = ReleaseSupport.readPomVersion(pom);
                    } catch (MojoException e) { /* skip */ }
                }
            }
            if (effectiveVersion != null && !effectiveVersion.isEmpty()) {
                newVersions.put(name, VersionSupport.branchQualifiedVersion(
                        effectiveVersion, branchName));
            }
        }

        // For each subproject in topological order, check if it imports
        // a BOM published by an upstream subproject that got a new version
        for (String name : sorted) {
            File dir = new File(root, name);
            Path pomPath = dir.toPath().resolve("pom.xml");

            if (!Files.exists(pomPath)) continue;

            List<BomAnalysis.BomImport> bomImports;
            try {
                bomImports = BomAnalysis.extractBomImports(
                        pomPath, workspaceArtifacts);
            } catch (IOException e) {
                continue;
            }

            boolean pomChanged = false;
            for (BomAnalysis.BomImport bom : bomImports) {
                if (!bom.isWorkspaceInternal()) continue;

                String upstreamName = bom.publishingSubproject();
                if (!newVersions.containsKey(upstreamName)) continue;

                String newVersion = newVersions.get(upstreamName);
                try {
                    boolean updated = BomAnalysis.updateBomImportVersion(
                            pomPath, bom.groupId(), bom.artifactId(), newVersion);
                    if (updated) {
                        log.info("    " + name + ": BOM import "
                                + bom.groupId() + ":" + bom.artifactId()
                                + " → " + newVersion);
                        pomChanged = true;
                    }
                } catch (IOException e) {
                    log.warn("    Could not update BOM import in "
                            + name + ": " + e.getMessage());
                }
            }

            if (pomChanged) {
                try {
                    ReleaseSupport.exec(dir, log, "git", "add", "pom.xml");
                    ReleaseSupport.exec(dir, log,
                            "git", "commit", "-m",
                            "feature: update BOM imports for " + branchName);
                } catch (MojoException e) {
                    log.warn("    Could not commit BOM update in "
                            + name + ": " + e.getMessage());
                }
            }
        }
    }

    // ── Shallow-clone unshallowing ───────────────────────────────

    /**
     * Check if a subproject is a shallow clone and fetch full history
     * if needed. Feature branches require full history for merge-base
     * operations during feature-finish.
     *
     * @param dir  the subproject root directory
     * @param name subproject name (for log messages only)
     * @throws MojoException never (errors are logged as warnings)
     */
    void ensureFullClone(File dir, String name)
            throws MojoException {
        try {
            String isShallow = ReleaseSupport.execCapture(dir,
                    "git", "rev-parse", "--is-shallow-repository");
            if ("true".equals(isShallow.trim())) {
                log.info("    Fetching full history (shallow clone detected)...");
                ReleaseSupport.exec(dir, log,
                        "git", "fetch", "--unshallow");
            }
        } catch (MojoException e) {
            log.warn("    Could not check/unshallow " + name
                    + ": " + e.getMessage());
        }
    }

    // ── Intra-reactor version pin removal ────────────────────────

    /**
     * Detect and remove intra-reactor version pins across all
     * components. A "pin" is a {@code <version>} tag on a dependency
     * whose {@code groupId:artifactId} matches another module within
     * the same reactor — the reactor resolves versions automatically,
     * so explicit pins are redundant and cause cascade issues.
     *
     * <p>In draft mode, reports what would be removed. In publish mode,
     * removes the pins and commits the changes.
     *
     * @param root       workspace root directory
     * @param components subproject names to scan
     * @param publish    true to actually remove; false to report only
     * @throws MojoException if a per-subproject git operation fails
     */
    void removeIntraReactorPins(File root, List<String> components,
                                boolean publish)
            throws MojoException {
        for (String name : components) {
            File subDir = new File(root, name);
            File rootPom = new File(subDir, "pom.xml");
            if (!rootPom.exists()) continue;

            try {
                // Build the set of all reactor artifactIds by walking
                // the subproject tree from the subproject root POM.
                PomModel rootModel = PomModel.parse(rootPom.toPath());
                String reactorGroupId = rootModel.groupId();
                Set<String> reactorArtifacts = new LinkedHashSet<>();
                collectReactorArtifacts(subDir.toPath(), rootModel,
                        reactorArtifacts);

                if (reactorArtifacts.size() <= 1) continue;  // no submodules

                // Scan all POMs for pinned intra-reactor dependencies
                List<File> allPoms = ReleaseSupport.findPomFiles(subDir);
                boolean anyChanged = false;

                for (File pom : allPoms) {
                    PomModel model = PomModel.parse(pom.toPath());
                    String content = model.content();
                    String updated = content;

                    for (org.apache.maven.api.model.Dependency dep : model.allDependencies()) {
                        String version = dep.getVersion();
                        if (version == null) continue;

                        // Check if this dependency is a reactor sibling
                        // — any explicit <version> is redundant, whether
                        // literal ("1.0.0-SNAPSHOT") or property-based
                        // ("${project.version}")
                        String depGroupId = dep.getGroupId();
                        if (depGroupId == null) depGroupId = reactorGroupId;
                        if (!reactorArtifacts.contains(dep.getArtifactId())) continue;

                        // Found an intra-reactor pin
                        String relPath = subDir.toPath()
                                .relativize(pom.toPath()).toString();

                        if (publish) {
                            updated = PomModel.removeDependencyVersion(
                                    updated, depGroupId, dep.getArtifactId());
                            log.info("    removed intra-reactor pin "
                                    + dep.getArtifactId() + " " + version
                                    + " from " + relPath);
                        } else {
                            log.info("  [draft] " + name + "/" + relPath
                                    + ": intra-reactor pin " + dep.getArtifactId()
                                    + " " + version
                                    + " would be removed (reactor resolves version)");
                        }
                    }

                    if (publish && !updated.equals(content)) {
                        Files.writeString(pom.toPath(), updated,
                                StandardCharsets.UTF_8);
                        anyChanged = true;
                    }
                }

                if (anyChanged) {
                    ReleaseSupport.exec(subDir, log, "git", "add", "-A");
                    ReleaseSupport.exec(subDir, log,
                            "git", "commit", "-m",
                            "build: remove intra-reactor version pins");
                }
            } catch (IOException e) {
                log.warn("    Could not scan " + name
                        + " for intra-reactor pins: " + e.getMessage());
            }
        }
    }

    /**
     * Recursively collect all artifactIds in a reactor tree by walking
     * the {@code <subprojects>} (or {@code <modules>}) declarations.
     *
     * @param baseDir          directory of the POM being scanned
     * @param model            parsed POM model
     * @param reactorArtifacts accumulator for discovered artifactIds
     * @throws IOException if a submodule POM cannot be read
     */
    private void collectReactorArtifacts(Path baseDir,
                                         PomModel model,
                                         Set<String> reactorArtifacts)
            throws IOException {
        reactorArtifacts.add(model.artifactId());

        for (String sub : model.subprojects()) {
            Path subDir = baseDir.resolve(sub);
            Path subPom = subDir.resolve("pom.xml");
            if (Files.exists(subPom)) {
                PomModel subModel = PomModel.parse(subPom);
                collectReactorArtifacts(subDir, subModel, reactorArtifacts);
            }
        }
    }
}
