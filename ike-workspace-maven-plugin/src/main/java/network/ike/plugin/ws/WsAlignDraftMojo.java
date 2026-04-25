package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;
import network.ike.workspace.Subproject;
import network.ike.workspace.Dependency;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.PublishedArtifactSet;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Align workspace state: inter-subproject dependency versions in POM
 * files and branch fields in {@code workspace.yaml}.
 *
 * <p>This is the {@code ws:align-draft} goal (preview). The
 * corresponding {@code ws:align-publish} applies the same changes.
 * Two axes are reconciled, both expressing the same
 * "declared ↔ actual" pattern (see #180):
 *
 * <ul>
 *   <li><b>POMs</b> — for each subproject on disk, scan POM dependency
 *       declarations. When a dependency's groupId matches another
 *       workspace subproject and the declared version does not match
 *       that subproject's current POM version, the dependency version
 *       is updated. Property-based versions (e.g. {@code <ike-bom.version>})
 *       are updated via {@code PomModel.updateProperty}; direct
 *       {@code <version>} tags are updated via
 *       {@code PomModel.updateDependencyVersion}.</li>
 *   <li><b>Branches</b> — reconcile {@code branch:} fields in
 *       {@code workspace.yaml} against the actual branch each cloned
 *       subproject is on. With {@code from=repos} (default), the yaml
 *       is updated from on-disk state; with {@code from=manifest},
 *       each subproject is checked out to the declared branch. This
 *       replaces the former {@code ws:sync} goal.</li>
 * </ul>
 *
 * <pre>{@code
 * mvn ws:align-draft                         # report only (both axes)
 * mvn ws:align-publish                        # apply (both axes)
 * mvn ws:align-publish -Dscope=poms           # POMs only
 * mvn ws:align-publish -Dscope=branches       # branches only
 * mvn ws:align-publish -Dscope=branches -Dfrom=manifest  # switch repos
 * }</pre>
 */
@Mojo(name = "align-draft", projectRequired = false, aggregator = true)
public class WsAlignDraftMojo extends AbstractWorkspaceMojo {

    /**
     * When true, report changes without writing to POM files.
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /**
     * What to align: {@code poms} (inter-subproject dependency
     * versions), {@code branches} (workspace.yaml branch fields vs.
     * on-disk git state), or {@code all} (both). Default {@code all}.
     */
    @Parameter(property = "scope", defaultValue = "all")
    String scope;

    /**
     * Branch-sync direction — only consulted when {@code scope}
     * includes branches. {@code repos} (default) reads actual branches
     * and updates workspace.yaml; {@code manifest} runs
     * {@code git checkout} per subproject so repos match the yaml.
     */
    @Parameter(property = "from", defaultValue = "repos")
    String from;

    /**
     * When true, allow branch checkout against subprojects with
     * uncommitted changes. Only consulted with
     * {@code scope=branches|all} and {@code from=manifest}. Default
     * {@code false}.
     */
    @Parameter(property = "force", defaultValue = "false")
    boolean force;

    /** Creates this goal instance. */
    public WsAlignDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        boolean draft = !publish;
        boolean doPoms = !"branches".equals(scope);
        boolean doBranches = !"poms".equals(scope);
        if (!doPoms && !doBranches) {
            throw new MojoException(
                    "Invalid scope '" + scope + "' — expected poms|branches|all");
        }

        getLog().info("");
        getLog().info("IKE Workspace Align — reconcile POM dependencies"
                + " and branch fields");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Scope: " + scope);
        if (doBranches) {
            getLog().info("  Branches: "
                    + ("manifest".equals(from)
                            ? "manifest → repos (git checkout)"
                            : "repos → manifest (update yaml)"));
        }

        if (draft) {
            getLog().info("  (draft — no files will be modified)");
        }
        getLog().info("");

        // #150 migration: ws:align-publish (and the draft variant) is the
        // designated entry point that rewrites legacy schemas in place.
        // Every other reader hits the hard-cut in ManifestReader.read, so
        // users on an old workspace see a message pointing them at
        // ws:align-publish first. Do this BEFORE loadGraph() so graph
        // construction sees the migrated content.
        Path manifestPath = resolveManifest();
        ManifestReader.migrateLegacySchemaIfNeeded(
                manifestPath, msg -> getLog().info("  " + msg));

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // Preflight: POM alignment requires clean working trees (#132)
        // so the rewrite commit doesn't bundle unrelated edits. Branch
        // alignment does its own per-subproject uncommitted-changes check —
        // in from=manifest mode it skips repos with uncommitted changes
        // unless -Dforce; in from=repos mode it only reads, and the
        // workspace root will itself have uncommitted changes once yaml
        // is rewritten. So the strict preflight only fires when scope
        // includes poms.
        if (doPoms) {
            List<String> sorted = graph.topologicalSort();
            PreflightResult preflight = Preflight.of(
                    List.of(PreflightCondition.WORKING_TREE_CLEAN),
                    PreflightContext.of(root, graph, sorted));
            if (draft) {
                preflight.warnIfFailed(getLog(), WsGoal.ALIGN_PUBLISH);
            } else {
                preflight.requirePassed(WsGoal.ALIGN_PUBLISH);
            }
        }

        // Build lookup: groupId:artifactId → (subproject name, current POM version)
        Map<String, ComponentVersion> artifactIndex = doPoms
                ? buildArtifactIndex(graph, root)
                : new LinkedHashMap<>();

        int totalChanges = 0;
        List<String> changedComponents = new ArrayList<>();
        List<AlignChange> reportChanges = new ArrayList<>();
        List<AlignCheck> alignChecks = new ArrayList<>();
        List<BranchChange> branchChanges = new ArrayList<>();

        if (doPoms) {
            totalChanges += alignPoms(graph, root, artifactIndex,
                    changedComponents, reportChanges, alignChecks, draft);
        }

        if (doBranches) {
            totalChanges += alignBranches(graph, root, manifestPath,
                    branchChanges, draft);
        }

        // --- Summary ---
        getLog().info("");
        if (totalChanges == 0) {
            getLog().info("  Nothing to align  ✓");
        } else if (draft) {
            getLog().info("  " + totalChanges + " change(s) would be applied");
            getLog().info("  Use ws:align-publish to apply changes.");
        } else {
            getLog().info("  Applied " + totalChanges + " change(s)");
        }
        getLog().info("");

        // --- Structured markdown report ---
        writeReport(publish ? WsGoal.ALIGN_PUBLISH : WsGoal.ALIGN_DRAFT,
                buildMarkdownReport(totalChanges, changedComponents,
                        reportChanges, alignChecks, branchChanges));
    }

    /**
     * Run POM-side alignment (dependency versions, parent versions,
     * plugin literals). Returns the number of changes made or that
     * would be made in draft mode.
     */
    private int alignPoms(WorkspaceGraph graph, File root,
                          Map<String, ComponentVersion> artifactIndex,
                          List<String> changedComponents,
                          List<AlignChange> reportChanges,
                          List<AlignCheck> alignChecks,
                          boolean draft) throws MojoException {
        int totalChanges = 0;

        for (Map.Entry<String, Subproject> entry : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File subprojectDir = new File(root, name);

            if (!new File(subprojectDir, "pom.xml").exists()) {
                getLog().debug("  " + name + ": not cloned — skipping");
                continue;
            }

            // Find all POM files in this subproject
            List<File> pomFiles;
            try {
                pomFiles = ReleaseSupport.findPomFiles(subprojectDir);
            } catch (MojoException e) {
                getLog().warn("  " + name + ": could not scan POM files — "
                        + e.getMessage());
                continue;
            }

            // Also use the declared depends-on to find version-property hints
            Map<String, String> versionPropertyMap = new LinkedHashMap<>();
            for (Dependency dep : subproject.dependsOn()) {
                if (dep.versionProperty() != null && !dep.versionProperty().isEmpty()) {
                    Subproject target = graph.manifest().subprojects().get(dep.subproject());
                    if (target != null && target.groupId() != null
                            && !target.groupId().isEmpty()) {
                        versionPropertyMap.put(dep.subproject(), dep.versionProperty());
                    }
                }
            }

            int componentChanges = 0;

            for (File pomFile : pomFiles) {
                int changes = alignPomDependencies(
                        name, pomFile, artifactIndex, versionPropertyMap,
                        subprojectDir, graph, reportChanges);
                changes += alignPomPlugins(
                        name, pomFile, artifactIndex,
                        subprojectDir, reportChanges);
                componentChanges += changes;
            }

            // Read POM version for the summary table
            String pomVersion = "—";
            try {
                pomVersion = ReleaseSupport.readPomVersion(
                        new File(subprojectDir, "pom.xml"));
            } catch (MojoException ignored) { }

            if (componentChanges > 0) {
                totalChanges += componentChanges;
                changedComponents.add(name);
                alignChecks.add(new AlignCheck(name, pomVersion, false));
            } else {
                alignChecks.add(new AlignCheck(name, pomVersion, true));
            }
        }

        // --- Parent version alignment (via Maven 4 Model API) ---
        for (Map.Entry<String, Subproject> entry : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            String parentSubprojectName = subproject.parent();
            if (parentSubprojectName == null) continue;

            Subproject parentSubproject = graph.manifest().subprojects().get(parentSubprojectName);
            if (parentSubproject == null || parentSubproject.version() == null) continue;

            File subprojectDir = new File(root, name);
            Path pomPath = subprojectDir.toPath().resolve("pom.xml");
            if (!Files.exists(pomPath)) continue;

            try {
                PomModel pom = PomModel.parse(pomPath);
                Parent parentInfo = pom.parent();
                if (parentInfo == null) continue;

                String expectedVersion = parentSubproject.version();
                String currentVersion = parentInfo.getVersion();
                if (currentVersion == null
                        || expectedVersion.equals(currentVersion)) {
                    continue;
                }

                String parentGid = parentInfo.getGroupId();
                String parentAid = parentInfo.getArtifactId();
                if (draft) {
                    getLog().info("  " + name + ": parent " + parentAid
                            + " " + currentVersion + " → " + expectedVersion
                            + " (draft)");
                } else {
                    // #241: match full GA, not artifactId alone
                    String updated = PomModel.updateParentVersion(
                            pom.content(), parentGid, parentAid,
                            expectedVersion);
                    Files.writeString(pomPath, updated, StandardCharsets.UTF_8);
                    getLog().info("  " + name + ": parent " + parentAid
                            + " " + currentVersion + " → " + expectedVersion);

                    // Also update submodule POMs that reference the same parent
                    List<File> subPoms = ReleaseSupport.findPomFiles(subprojectDir);
                    for (File subPom : subPoms) {
                        if (subPom.toPath().equals(pomPath)) continue;
                        String subContent = Files.readString(
                                subPom.toPath(), StandardCharsets.UTF_8);
                        String subUpdated = PomModel.updateParentVersion(
                                subContent, parentGid, parentAid,
                                expectedVersion);
                        if (!subUpdated.equals(subContent)) {
                            Files.writeString(subPom.toPath(), subUpdated,
                                    StandardCharsets.UTF_8);
                        }
                    }
                }
                reportChanges.add(new AlignChange(
                        name, "pom.xml", "parent:" + parentAid,
                        currentVersion, expectedVersion));
                totalChanges++;
                if (!changedComponents.contains(name)) {
                    changedComponents.add(name);
                }
            } catch (IOException e) {
                getLog().warn("  " + name + ": could not align parent version — "
                        + e.getMessage());
            }
        }

        // --- POM alignment summary ---
        if (totalChanges == 0) {
            getLog().info("  POMs: all inter-subproject versions are aligned  ✓");
        } else if (draft) {
            getLog().info("  POMs: " + totalChanges + " version(s) would be updated across "
                    + changedComponents.size() + " subproject(s)");
        } else {
            getLog().info("  POMs: updated " + totalChanges + " version(s) across "
                    + changedComponents.size() + " subproject(s)");
        }

        return totalChanges;
    }

    /**
     * Reconcile {@code workspace.yaml} branch fields against on-disk
     * git state. With {@code from=repos}, the yaml is updated to match
     * actual branches; with {@code from=manifest}, each subproject is
     * checked out to the declared branch (respecting uncommitted work
     * unless {@code -Dforce=true}).
     *
     * @return the number of branch changes applied, or that would be
     *         applied in draft mode
     */
    private int alignBranches(WorkspaceGraph graph, File root,
                              Path manifestPath,
                              List<BranchChange> branchChanges,
                              boolean draft) throws MojoException {
        if ("manifest".equals(from)) {
            return alignBranchesFromManifest(graph, root, branchChanges, draft);
        }
        return alignBranchesFromRepos(graph, root, manifestPath,
                branchChanges, draft);
    }

    /**
     * Read actual branches from each cloned subproject and update
     * {@code workspace.yaml} so the declared branch fields match reality.
     */
    private int alignBranchesFromRepos(WorkspaceGraph graph, File root,
                                       Path manifestPath,
                                       List<BranchChange> branchChanges,
                                       boolean draft) throws MojoException {
        Map<String, String> updates = new LinkedHashMap<>();

        for (Map.Entry<String, Subproject> entry : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            String actual = gitBranch(dir);
            String declared = subproject.branch();
            if (actual.equals(declared)) continue;

            updates.put(name, actual);
            branchChanges.add(new BranchChange(name, declared, actual, "yaml"));
            getLog().info("  branch: " + name + ": " + declared
                    + " → " + actual + (draft ? " (draft)" : ""));
        }

        if (updates.isEmpty()) {
            getLog().info("  Branches: yaml already matches repos  ✓");
            return 0;
        }

        if (!draft) {
            try {
                ManifestWriter.updateBranches(manifestPath, updates);
                getLog().info("  Branches: updated workspace.yaml ("
                        + updates.size() + " change(s))");
                // Commit if workspace root is a git repo
                File wsRoot = manifestPath.getParent().toFile();
                if (new File(wsRoot, ".git").exists()) {
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "add", "workspace.yaml");
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "commit", "-m",
                            "workspace: align branch fields from repos");
                }
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to update workspace.yaml: " + e.getMessage(), e);
            }
        }

        return updates.size();
    }

    /**
     * Read declared branches from {@code workspace.yaml} and run
     * {@code git checkout} in each subproject whose current branch
     * differs. Subprojects with uncommitted changes are skipped unless
     * {@code -Dforce=true}.
     */
    private int alignBranchesFromManifest(WorkspaceGraph graph, File root,
                                          List<BranchChange> branchChanges,
                                          boolean draft) throws MojoException {
        int switched = 0;
        int skippedDirty = 0;

        for (Map.Entry<String, Subproject> entry : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            String declared = subproject.branch();
            if (declared == null) continue;
            String actual = gitBranch(dir);
            if (actual.equals(declared)) continue;

            String status = gitStatus(dir);
            if (!status.isEmpty() && !force) {
                getLog().warn("  ⚠ " + name + ": uncommitted changes — skipping"
                        + " (pass -Dforce=true to override)");
                skippedDirty++;
                continue;
            }

            branchChanges.add(new BranchChange(name, actual, declared, "checkout"));
            getLog().info("  branch: " + name + ": " + actual
                    + " → " + declared + (draft ? " (draft)" : ""));

            if (!draft) {
                ReleaseSupport.exec(dir, getLog(),
                        "git", "checkout", declared);
                switched++;
            }
        }

        if (switched == 0 && skippedDirty == 0 && branchChanges.isEmpty()) {
            getLog().info("  Branches: repos already match yaml  ✓");
        } else if (draft) {
            getLog().info("  Branches: " + branchChanges.size()
                    + " subproject(s) would be switched");
        } else {
            getLog().info("  Branches: switched " + switched
                    + ", skipped " + skippedDirty + " (uncommitted)");
        }

        return draft ? branchChanges.size() : switched;
    }

    /**
     * Build a structured markdown report from collected alignment changes.
     */
    private String buildMarkdownReport(int totalChanges,
                                        List<String> changedComponents,
                                        List<AlignChange> changes,
                                        List<AlignCheck> checks,
                                        List<BranchChange> branches) {
        StringBuilder md = new StringBuilder();

        md.append("**Scope:** ").append(scope);
        if (!"poms".equals(scope)) {
            md.append(" (branches: ")
              .append("manifest".equals(from) ? "manifest → repos" : "repos → manifest")
              .append(")");
        }
        md.append("\n\n");

        if (totalChanges == 0) {
            md.append("Nothing to align.\n\n");
        } else if (!publish) {
            md.append("**Dry run** — ").append(totalChanges)
              .append(" change(s) would be applied.\n\n");
        } else {
            md.append("Applied ").append(totalChanges)
              .append(" change(s).\n\n");
        }

        if (!changes.isEmpty()) {
            md.append("### POM changes\n\n");
            md.append("| Subproject | POM | Artifact | From | To |\n");
            md.append("|-----------|-----|----------|------|----|\n");
            for (AlignChange c : changes) {
                md.append("| ").append(c.subproject)
                  .append(" | ").append(c.pomRelPath)
                  .append(" | `").append(c.artifact).append('`')
                  .append(" | ").append(c.fromVersion)
                  .append(" | ").append(c.toVersion)
                  .append(" |\n");
            }
            md.append('\n');
        }

        if (!branches.isEmpty()) {
            md.append("### Branch changes\n\n");
            md.append("| Subproject | From | To | Action |\n");
            md.append("|-----------|------|----|--------|\n");
            for (BranchChange b : branches) {
                md.append("| ").append(b.subproject)
                  .append(" | ").append(b.fromBranch)
                  .append(" | ").append(b.toBranch)
                  .append(" | ").append(b.action)
                  .append(" |\n");
            }
            md.append('\n');
        }

        // Always show alignment summary table
        if (!checks.isEmpty()) {
            md.append("| Subproject | Version | Status |\n");
            md.append("|-----------|---------|--------|\n");
            for (AlignCheck c : checks) {
                md.append("| ").append(c.subproject)
                  .append(" | ").append(c.version)
                  .append(" | ").append(c.aligned ? "✓ aligned" : "✗ needs update")
                  .append(" |\n");
            }
        }

        return md.toString();
    }

    /** A single version alignment change for the report. */
    private record AlignChange(String subproject, String pomRelPath,
                                String artifact, String fromVersion,
                                String toVersion) {}

    /** A subproject alignment check result for the summary table. */
    private record AlignCheck(String subproject, String version,
                               boolean aligned) {}

    /**
     * A single branch-alignment change. The {@code action} is either
     * {@code "yaml"} (workspace.yaml branch field rewritten) or
     * {@code "checkout"} (repo switched via {@code git checkout}).
     */
    private record BranchChange(String subproject, String fromBranch,
                                 String toBranch, String action) {}

    // ── Artifact index ───────────────────────────────────────────────

    /**
     * Build an index from {@code groupId:artifactId} to (subproject name,
     * current POM version) for all cloned workspace subprojects.
     *
     * <p>Uses {@link PublishedArtifactSet#scan} to discover every
     * artifact each subproject publishes, so components sharing a
     * groupId (e.g., {@code dev.ikm.ike}) are correctly distinguished
     * by artifactId.
     */
    private Map<String, ComponentVersion> buildArtifactIndex(
            WorkspaceGraph graph, File root) throws MojoException {
        Map<String, ComponentVersion> index = new LinkedHashMap<>();

        for (Map.Entry<String, Subproject> entry : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            File subprojectDir = new File(root, name);

            if (!new File(subprojectDir, "pom.xml").exists()) {
                continue;
            }

            String pomVersion;
            try {
                pomVersion = ReleaseSupport.readPomVersion(
                        new File(subprojectDir, "pom.xml"));
            } catch (MojoException e) {
                getLog().warn("  " + name + ": could not read POM version — "
                        + e.getMessage());
                continue;
            }

            Set<PublishedArtifactSet.Artifact> published;
            try {
                published = PublishedArtifactSet.scan(subprojectDir.toPath());
            } catch (IOException e) {
                getLog().warn("  " + name + ": could not scan published artifacts — "
                        + e.getMessage());
                continue;
            }

            ComponentVersion cv = new ComponentVersion(name, pomVersion);
            for (PublishedArtifactSet.Artifact artifact : published) {
                String key = artifact.groupId() + ":" + artifact.artifactId();
                index.put(key, cv);
            }
        }

        return index;
    }

    // ── POM dependency alignment ────────────────────────────────────

    /**
     * Scan a single POM file for dependencies whose {@code groupId:artifactId}
     * matches a workspace subproject's published artifact, and update
     * mismatched versions.
     *
     * <p>Uses Maven 4's {@link PomModel} for reading dependency coordinates
     * (no regex for extraction). Writes use targeted text replacement via
     * {@link PomModel#updateDependencyVersion} to preserve formatting.
     *
     * @return number of changes made (or that would be made in draft)
     */
    private int alignPomDependencies(String ownerName, File pomFile,
                                     Map<String, ComponentVersion> artifactIndex,
                                     Map<String, String> versionPropertyMap,
                                     File subprojectDir, WorkspaceGraph graph,
                                     List<AlignChange> reportChanges)
            throws MojoException {
        PomModel pom;
        try {
            pom = PomModel.parse(pomFile.toPath());
        } catch (IOException e) {
            getLog().debug("  " + ownerName + ": skipping "
                    + pomFile.getName() + " (empty or unparseable)");
            return 0;
        }

        String updated = pom.content();
        int changes = 0;

        // Iterate all dependencies using the Maven 4 Model API
        for (org.apache.maven.api.model.Dependency dep : pom.allDependencies()) {
            String depGroupId = dep.getGroupId();
            String depArtifactId = dep.getArtifactId();
            String currentVersion = dep.getVersion();

            if (depGroupId == null || depArtifactId == null
                    || currentVersion == null) {
                continue;
            }

            String key = depGroupId + ":" + depArtifactId;
            ComponentVersion target = artifactIndex.get(key);

            // Skip if not a workspace artifact or self-reference
            if (target == null || target.name.equals(ownerName)) {
                continue;
            }

            if (currentVersion.startsWith("${") && currentVersion.endsWith("}")) {
                // Property-based version — resolve via model properties
                String propName = currentVersion.substring(2,
                        currentVersion.length() - 1);
                String propValue = pom.properties().get(propName);
                if (propValue != null && !propValue.equals(target.version)) {
                    String relPath = subprojectDir.toPath().relativize(
                            pomFile.toPath()).toString();
                    getLog().info("  " + ownerName + " (" + relPath
                            + "): property <" + propName + "> "
                            + propValue + " → " + target.version);
                    reportChanges.add(new AlignChange(
                            ownerName, relPath,
                            "property:" + propName,
                            propValue, target.version));
                    updated = PomModel.updateProperty(
                            updated, propName, target.version);
                    changes++;
                }
            } else if (!currentVersion.equals(target.version)) {
                // Direct version mismatch — targeted text replacement
                String relPath = subprojectDir.toPath().relativize(
                        pomFile.toPath()).toString();
                getLog().info("  " + ownerName + " (" + relPath + "): "
                        + key + " " + currentVersion
                        + " → " + target.version);
                reportChanges.add(new AlignChange(
                        ownerName, relPath, key,
                        currentVersion, target.version));
                updated = PomModel.updateDependencyVersion(
                        updated, depGroupId, depArtifactId, target.version);
                changes++;
            }
        }

        // Handle version-property updates declared in depends-on.
        // Look up the target subproject's version by name (via the
        // artifact index), not by groupId — avoids the collision.
        for (Map.Entry<String, String> vpEntry : versionPropertyMap.entrySet()) {
            String targetComponent = vpEntry.getKey();
            String versionProperty = vpEntry.getValue();

            ComponentVersion cv = findComponentVersion(
                    targetComponent, artifactIndex, workspaceRoot());
            if (cv == null) continue;

            // Read current property value from the model
            String currentValue = pom.properties().get(versionProperty);
            if (currentValue != null && !currentValue.equals(cv.version)) {
                String relPath = subprojectDir.toPath().relativize(
                        pomFile.toPath()).toString();
                getLog().info("  " + ownerName + " (" + relPath
                        + "): property <" + versionProperty + "> "
                        + currentValue + " → " + cv.version);
                reportChanges.add(new AlignChange(
                        ownerName, relPath,
                        "property:" + versionProperty,
                        currentValue, cv.version));
                updated = PomModel.updateProperty(
                        updated, versionProperty, cv.version);
                changes++;
            }
        }

        // Write if changed
        if (changes > 0 && publish && !updated.equals(pom.content())) {
            try {
                Files.writeString(pomFile.toPath(), updated,
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to write " + pomFile + ": "
                        + e.getMessage(), e);
            }
        }

        return changes;
    }

    /**
     * Scan a single POM file for plugins whose {@code groupId:artifactId}
     * matches a workspace subproject's published artifact, and update
     * mismatched literal versions.
     *
     * <p>Uses Maven 4's {@link PomModel} for reading plugin coordinates.
     * Writes use {@link PomModel#updatePluginVersion} (OpenRewrite LST)
     * to preserve formatting.
     *
     * <p>Property-based plugin versions (e.g., {@code ${ike-tooling.version}})
     * are skipped here — property alignment is handled by the dependency
     * alignment pass via {@code versionProperty} declarations.
     *
     * @return number of changes made (or that would be made in draft)
     */
    private int alignPomPlugins(String ownerName, File pomFile,
                                Map<String, ComponentVersion> artifactIndex,
                                File subprojectDir,
                                List<AlignChange> reportChanges)
            throws MojoException {
        PomModel pom;
        try {
            pom = PomModel.parse(pomFile.toPath());
        } catch (IOException e) {
            return 0;
        }

        String updated = pom.content();
        int changes = 0;

        for (Plugin plugin : pom.allPlugins()) {
            String pluginGroupId = plugin.getGroupId();
            String pluginArtifactId = plugin.getArtifactId();
            String currentVersion = plugin.getVersion();

            if (pluginGroupId == null || pluginArtifactId == null
                    || currentVersion == null) {
                continue;
            }

            // Skip property-based versions — handled by dependency/property alignment
            if (currentVersion.startsWith("${")) {
                continue;
            }

            String key = pluginGroupId + ":" + pluginArtifactId;
            ComponentVersion target = artifactIndex.get(key);

            if (target == null || target.name().equals(ownerName)) {
                continue;
            }

            if (!currentVersion.equals(target.version())) {
                String relPath = subprojectDir.toPath().relativize(
                        pomFile.toPath()).toString();
                getLog().info("  " + ownerName + " (" + relPath + "): plugin "
                        + key + " " + currentVersion
                        + " → " + target.version());
                reportChanges.add(new AlignChange(
                        ownerName, relPath, "plugin:" + key,
                        currentVersion, target.version()));
                updated = PomModel.updatePluginVersion(
                        updated, pluginGroupId, pluginArtifactId,
                        target.version());
                changes++;
            }
        }

        if (changes > 0 && publish && !updated.equals(pom.content())) {
            try {
                Files.writeString(pomFile.toPath(), updated,
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to write " + pomFile + ": "
                        + e.getMessage(), e);
            }
        }

        return changes;
    }

    /**
     * Find a subproject's version by scanning its published artifacts
     * and looking them up in the artifact index. Matches by subproject
     * name (not groupId), so it handles groupId collisions.
     */
    private ComponentVersion findComponentVersion(
            String subprojectName,
            Map<String, ComponentVersion> artifactIndex, File root) {
        File subprojectDir = new File(root, subprojectName);
        if (!new File(subprojectDir, "pom.xml").exists()) {
            return null;
        }
        try {
            Set<PublishedArtifactSet.Artifact> published =
                    PublishedArtifactSet.scan(subprojectDir.toPath());
            for (PublishedArtifactSet.Artifact artifact : published) {
                String key = artifact.groupId() + ":" + artifact.artifactId();
                ComponentVersion cv = artifactIndex.get(key);
                if (cv != null && cv.name.equals(subprojectName)) {
                    return cv;
                }
            }
        } catch (IOException e) {
            // Fall through
        }
        return null;
    }

    // ── Internal record ─────────────────────────────────────────────

    /**
     * Associates a subproject name with its current POM version.
     */
    private record ComponentVersion(String name, String version) {}
}
