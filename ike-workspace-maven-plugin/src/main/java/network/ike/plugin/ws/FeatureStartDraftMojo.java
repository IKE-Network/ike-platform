package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.BomAnalysis;
import network.ike.workspace.Subproject;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.PublishedArtifactSet;
import network.ike.workspace.VersionSupport;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Start a coordinated feature branch across workspace subprojects.
 *
 * <p>Creates a feature branch with a consistent name across all
 * workspace subprojects, optionally setting branch-qualified
 * SNAPSHOT versions in each POM.
 *
 * <p><strong>Workspace mode</strong> (workspace.yaml found):</p>
 * <ol>
 *   <li>Validates the working tree is clean</li>
 *   <li>Creates branch {@code feature/<name>} from the current HEAD</li>
 *   <li>If the subproject has a Maven version, sets a branch-qualified
 *       version (e.g., {@code 1.2.0-my-feature-SNAPSHOT})</li>
 *   <li>Commits the version change</li>
 *   <li>Updates workspace.yaml branch fields for all branched components</li>
 *   <li>Commits the workspace.yaml change</li>
 * </ol>
 *
 * <p><strong>Bare mode</strong> (no workspace.yaml):</p>
 * <ol>
 *   <li>Creates the feature branch in the current repo only</li>
 *   <li>Sets version-qualified SNAPSHOT in the current repo's POMs</li>
 * </ol>
 *
 * <p>Components are processed in topological order so that upstream
 * dependencies get their new versions first.
 *
 * <pre>{@code
 * mvn ike:feature-start -Dfeature=shield-terminology
 * mvn ike:feature-start -Dfeature=doc-refresh -DskipVersion=true
 * }</pre>
 */
@Mojo(name = "feature-start-draft", projectRequired = false)
public class FeatureStartDraftMojo extends AbstractWorkspaceMojo {

    /** Feature name. Branch will be {@code feature/<name>}. Prompted if omitted. */
    @Parameter(property = "feature")
    String feature;

    /**
     * Skip POM version qualification. Useful for document projects
     * that don't have versioned artifacts.
     */
    @Parameter(property = "skipVersion", defaultValue = "false")
    boolean skipVersion;

    /** Show plan without executing. */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Creates this goal instance. */
    public FeatureStartDraftMojo() {}

    /** A row in the feature-start summary table. */
    private record BranchRow(String subproject, String branch,
                              String snapshotVersion, String status) {}

    /** A row in the BOM cascade gaps table. */
    private record CascadeGapRow(String consumer, String dependency,
                                  String issue) {}

    @Override
    public void execute() throws MojoException {
        feature = requireParam(feature, "feature", "Feature name (without feature/ prefix)");
        String branchName = "feature/" + feature;

        if (!isWorkspaceMode()) {
            executeBareMode(branchName);
            return;
        }

        // --- Workspace mode ---
        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // VCS bridge: catch-up before branching
        VcsOperations.catchUp(root, getLog());

        Set<String> targets = graph.manifest().subprojects().keySet();

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        getLog().info("");
        getLog().info(header("Feature Start"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName);
        getLog().info("  Scope:   " + sorted.size() + " components");
        if (draft) {
            getLog().info("  Mode:    DRAFT");
        }
        getLog().info("");

        // Analyze BOM cascade issues and prompt for confirmation
        List<CascadeGapRow> cascadeGaps = new ArrayList<>();
        if (!skipVersion) {
            cascadeGaps = checkBomCascadeAndConfirm(graph, root);
        }

        List<String> created = new ArrayList<>();
        List<String> skippedNotCloned = new ArrayList<>();
        List<String> skippedAlreadyOnBranch = new ArrayList<>();
        List<BranchRow> branchRows = new ArrayList<>();

        for (String name : sorted) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                skippedNotCloned.add(name);
                getLog().info("  \u26A0 " + name + " \u2014 not cloned, skipping");
                branchRows.add(new BranchRow(name, "—", "—", "not cloned"));
                continue;
            }

            String currentBranch = gitBranch(dir);
            if (currentBranch.equals(branchName)) {
                skippedAlreadyOnBranch.add(name);
                getLog().info("  \u2713 " + name + " \u2014 already on " + branchName);
                branchRows.add(new BranchRow(
                        name, branchName, "—", "already on branch"));
                continue;
            }

            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                throw new MojoException(
                        name + " has uncommitted changes — commit or stash, then try again.");
            }

            // If on a different feature branch, switch to main first.
            // New features always derive from main.
            if (currentBranch.startsWith("feature/") && !currentBranch.equals(branchName)) {
                if (draft) {
                    getLog().info("  [draft] " + name + " — would switch "
                            + currentBranch + " → main → " + branchName);
                } else {
                    getLog().info("  " + name + ": switching " + currentBranch + " → main");
                    VcsOperations.checkout(dir, getLog(), "main");
                }
            }

            // Resolve effective version: workspace.yaml first, POM fallback
            String effectiveVersion = subproject.version();
            if (effectiveVersion == null || effectiveVersion.isEmpty()) {
                File pom = new File(dir, "pom.xml");
                if (pom.exists()) {
                    try {
                        effectiveVersion = ReleaseSupport.readPomVersion(pom);
                    } catch (MojoException e) {
                        getLog().debug("Could not read POM version for "
                                + name + ": " + e.getMessage());
                    }
                }
            }

            String newVersion = (!skipVersion && effectiveVersion != null)
                    ? VersionSupport.branchQualifiedVersion(effectiveVersion, branchName)
                    : "—";

            if (draft) {
                String versionInfo = "—".equals(newVersion)
                        ? "" : " \u2192 " + newVersion;
                getLog().info("  [draft] " + name + " \u2014 would create "
                        + branchName + versionInfo);
                created.add(name);
                branchRows.add(new BranchRow(
                        name, branchName, newVersion, "would create"));
                continue;
            }

            // Auto-unshallow if this is a shallow clone — feature
            // branches need full history for merge-base operations
            ensureFullClone(dir, name);

            ReleaseSupport.exec(dir, getLog(),
                    "git", "checkout", "-b", branchName);

            if (!skipVersion && effectiveVersion != null
                    && !effectiveVersion.isEmpty()) {
                setPomVersion(dir, effectiveVersion, newVersion);
                ReleaseSupport.exec(dir, getLog(),
                        "git", "add", "pom.xml");
                ReleaseSupport.exec(dir, getLog(),
                        "git", "commit", "-m",
                        "feature: set version " + newVersion
                                + " for " + branchName);
            }

            getLog().info(Ansi.green("  ✓ ") + String.format("%-24s %s → %s",
                    name, effectiveVersion != null ? effectiveVersion : "—",
                    newVersion));

            created.add(name);
            branchRows.add(new BranchRow(
                    name, branchName, newVersion, "✓ created"));
        }

        // Remove intra-reactor version pins (draft reports, publish removes)
        if (!created.isEmpty()) {
            removeIntraReactorPins(root, created, publish);
        }

        // Cascade version-property updates to downstream components
        if (!created.isEmpty() && publish && !skipVersion) {
            cascadeVersionProperties(graph, root, sorted, branchName);
            cascadeBomProperties(graph, root, sorted, branchName);
            cascadeBomImports(graph, root, sorted, branchName);
        }

        // Write VCS state for each branched subproject (no push — branches stay local)
        if (!created.isEmpty() && publish) {
            for (String name : created) {
                File dir = new File(root, name);
                VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_START);
            }
        }

        // Branch the workspace repo and update workspace.yaml on the feature branch
        if (!created.isEmpty() && publish) {
            branchWorkspaceRepo(branchName, created);
        }

        getLog().info("");
        getLog().info("  Created: " + created.size()
                + " | Already on branch: " + skippedAlreadyOnBranch.size()
                + " | Not cloned: " + skippedNotCloned.size());
        getLog().info("");

        // Structured markdown report
        writeReport(publish ? WsGoal.FEATURE_START_PUBLISH : WsGoal.FEATURE_START_DRAFT, buildMarkdownReport(
                branchName, branchRows, cascadeGaps));
    }

    private String buildMarkdownReport(String branchName,
                                        List<BranchRow> branchRows,
                                        List<CascadeGapRow> cascadeGaps) {
        var sb = new StringBuilder();
        sb.append("**Branch:** `").append(branchName).append("`\n\n");

        sb.append("| Subproject | Branch | Snapshot Version | Status |\n");
        sb.append("|-----------|--------|-----------------|--------|\n");
        for (BranchRow row : branchRows) {
            sb.append("| ").append(row.subproject)
              .append(" | ").append(row.branch)
              .append(" | ").append(row.snapshotVersion)
              .append(" | ").append(row.status)
              .append(" |\n");
        }

        if (!cascadeGaps.isEmpty()) {
            sb.append("\n**BOM cascade gaps:**\n\n");
            sb.append("| Consumer | Dependency | Issue |\n");
            sb.append("|----------|------------|-------|\n");
            for (CascadeGapRow row : cascadeGaps) {
                sb.append("| ").append(row.consumer)
                  .append(" | ").append(row.dependency)
                  .append(" | ").append(row.issue)
                  .append(" |\n");
            }
        }

        return sb.toString();
    }

    /**
     * Bare-mode: create feature branch in the current repo only.
     */
    private void executeBareMode(String branchName) throws MojoException {
        boolean draft = !publish;
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");
        getLog().info("IKE Feature Start (bare repo)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName);
        getLog().info("  Repo:    " + dir.getName());
        if (draft) {
            getLog().info("  Mode:    DRAFT");
        }
        getLog().info("");

        // VCS bridge: catch-up before branching
        VcsOperations.catchUp(dir, getLog());

        // Validate clean worktree
        String status = gitStatus(dir);
        if (!status.isEmpty()) {
            throw new MojoException(
                    "Uncommitted changes. Commit or stash before starting a feature.");
        }

        // Read current version from POM
        String currentVersion = null;
        File pom = new File(dir, "pom.xml");
        if (pom.exists() && !skipVersion) {
            try {
                currentVersion = ReleaseSupport.readPomVersion(pom);
            } catch (MojoException e) {
                getLog().debug("Could not read POM version: " + e.getMessage());
            }
        }

        if (draft) {
            String versionInfo = "";
            if (currentVersion != null) {
                versionInfo = " \u2192 " + VersionSupport.branchQualifiedVersion(
                        currentVersion, branchName);
            }
            getLog().info("  [draft] Would create " + branchName + versionInfo);
            getLog().info("");
            return;
        }

        // Auto-unshallow if needed
        ensureFullClone(dir, dir.getName());

        // Create branch
        ReleaseSupport.exec(dir, getLog(),
                "git", "checkout", "-b", branchName);
        getLog().info("  Created " + branchName);

        // Set branch-qualified version
        if (currentVersion != null && !currentVersion.isEmpty()) {
            String newVersion = VersionSupport.branchQualifiedVersion(
                    currentVersion, branchName);
            getLog().info("  Version: " + currentVersion + " \u2192 " + newVersion);
            setPomVersion(dir, currentVersion, newVersion);
            ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
            // Also stage any updated submodule POMs
            try {
                List<File> allPoms = ReleaseSupport.findPomFiles(dir);
                for (File subPom : allPoms) {
                    if (!subPom.equals(pom)) {
                        String rel = dir.toPath().relativize(subPom.toPath()).toString();
                        ReleaseSupport.exec(dir, getLog(), "git", "add", rel);
                    }
                }
            } catch (MojoException e) {
                getLog().debug("Could not scan submodule POMs: " + e.getMessage());
            }
            ReleaseSupport.exec(dir, getLog(),
                    "git", "commit", "-m",
                    "feature: set version " + newVersion + " for " + branchName);
        }

        // Write VCS state (no push — branch stays local)
        VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_START);

        getLog().info("");
    }

    /**
     * Branch the workspace repo, update workspace.yaml on the feature branch,
     * and push with IKE_VCS_CONTEXT.
     */
    private void branchWorkspaceRepo(String branchName, List<String> components)
            throws MojoException {
        try {
            Path manifestPath = resolveManifest();
            File wsRoot = manifestPath.getParent().toFile();
            File wsGit = new File(wsRoot, ".git");
            if (!wsGit.exists()) return;

            // If workspace repo is on a different feature branch, switch to main first
            String wsBranch = VcsOperations.currentBranch(wsRoot);
            if (wsBranch.startsWith("feature/") && !wsBranch.equals(branchName)) {
                getLog().info("  Workspace repo: switching " + wsBranch + " → main");
                VcsOperations.checkout(wsRoot, getLog(), "main");
            }

            // Branch the workspace repo
            getLog().info("  Branching workspace repo → " + branchName);
            VcsOperations.checkoutNew(wsRoot, getLog(), branchName);

            // Update workspace.yaml on the feature branch
            Map<String, String> updates = new LinkedHashMap<>();
            for (String name : components) {
                updates.put(name, branchName);
            }
            ManifestWriter.updateBranches(manifestPath, updates);
            getLog().info("  Updated workspace.yaml branches for "
                    + components.size() + " components");

            ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
            if (VcsOperations.hasStagedChanges(wsRoot)) {
                VcsOperations.commit(wsRoot, getLog(),
                        "workspace: update branches for " + branchName);
            } else {
                getLog().info("  workspace.yaml already up to date — nothing to commit");
            }

            // Write VCS state (no push — branch stays local)
            VcsOperations.writeVcsState(wsRoot, VcsState.Action.FEATURE_START);

        } catch (IOException e) {
            getLog().warn("  Could not update workspace.yaml: " + e.getMessage());
        }
    }

    /**
     * Set the POM version, handling both simple and multi-module projects.
     * Uses ReleaseSupport's POM manipulation which skips the parent block.
     */
    private void setPomVersion(File dir, String oldVersion, String newVersion)
            throws MojoException {
        File pom = new File(dir, "pom.xml");
        if (!pom.exists()) {
            getLog().warn("    No pom.xml found in " + dir.getName());
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
                    String content = java.nio.file.Files.readString(
                            subPom.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                    if (content.contains("<version>" + oldVersion + "</version>")) {
                        String updated = content.replace(
                                "<version>" + oldVersion + "</version>",
                                "<version>" + newVersion + "</version>");
                        java.nio.file.Files.writeString(
                                subPom.toPath(), updated,
                                java.nio.charset.StandardCharsets.UTF_8);
                        String rel = dir.toPath().relativize(subPom.toPath()).toString();
                        getLog().info("    updated: " + rel);
                        ReleaseSupport.exec(dir, getLog(), "git", "add", rel);
                    }
                } catch (java.io.IOException e) {
                    getLog().warn("    Could not update " + subPom + ": " + e.getMessage());
                }
            }
        } catch (MojoException e) {
            getLog().warn("    Could not scan for submodule POMs: " + e.getMessage());
        }
    }

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
     */
    private void cascadeVersionProperties(WorkspaceGraph graph, File root,
                                           List<String> sorted, String branchName)
            throws MojoException {

        // Build map of upstream subproject → new branch-qualified version
        java.util.Map<String, String> newVersions = new java.util.LinkedHashMap<>();
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

            boolean pomChanged = false;
            try {
                String content = java.nio.file.Files.readString(
                        pomFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                String original = content;

                for (network.ike.workspace.Dependency dep : sub.dependsOn()) {
                    String upstreamName = dep.subproject();
                    if (dep.versionProperty() == null) continue;
                    if (!newVersions.containsKey(upstreamName)) continue;

                    String upstreamVersion = newVersions.get(upstreamName);
                    String before = content;
                    content = PomRewriter.updateProperty(
                            content, dep.versionProperty(), upstreamVersion);

                    if (!content.equals(before)) {
                        getLog().info("    " + name + ": " + dep.versionProperty()
                                + " → " + upstreamVersion
                                + " (from " + upstreamName + ")");
                    }
                }

                if (!content.equals(original)) {
                    java.nio.file.Files.writeString(
                            pomFile.toPath(), content,
                            java.nio.charset.StandardCharsets.UTF_8);
                    ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
                    ReleaseSupport.exec(dir, getLog(), "git", "commit", "-m",
                            "feature: update dependency versions for " + branchName);
                    pomChanged = true;
                }
            } catch (java.io.IOException e) {
                getLog().warn("    Could not cascade version properties in "
                        + name + ": " + e.getMessage());
            }
        }
    }

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
     */
    private void cascadeBomProperties(WorkspaceGraph graph, File root,
                                       List<String> sorted, String branchName)
            throws MojoException {

        // Build map of subproject name → new branch-qualified version
        java.util.Map<String, String> newVersions = new java.util.LinkedHashMap<>();
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
                String content = java.nio.file.Files.readString(
                        pomFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                String original = content;

                for (java.util.Map.Entry<String, String> vEntry : newVersions.entrySet()) {
                    String subName = vEntry.getKey();
                    if (subName.equals(name)) continue;

                    String propertyName = subName + ".version";
                    String before = content;
                    content = PomRewriter.updateProperty(
                            content, propertyName, vEntry.getValue());

                    if (!content.equals(before)) {
                        getLog().info("    " + name + ": <" + propertyName
                                + "> → " + vEntry.getValue());
                    }
                }

                if (!content.equals(original)) {
                    java.nio.file.Files.writeString(
                            pomFile.toPath(), content,
                            java.nio.charset.StandardCharsets.UTF_8);
                    ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
                    ReleaseSupport.exec(dir, getLog(), "git", "commit", "-m",
                            "feature: update BOM properties for " + branchName);
                }
            } catch (java.io.IOException e) {
                getLog().warn("    Could not cascade BOM properties in "
                        + name + ": " + e.getMessage());
            }
        }
    }

    /**
     * Check if a subproject is a shallow clone and fetch full history
     * if needed. Feature branches require full history for merge-base
     * operations during feature-finish.
     */
    private void ensureFullClone(File dir, String name)
            throws MojoException {
        try {
            String isShallow = ReleaseSupport.execCapture(dir,
                    "git", "rev-parse", "--is-shallow-repository");
            if ("true".equals(isShallow.trim())) {
                getLog().info("    Fetching full history (shallow clone detected)...");
                ReleaseSupport.exec(dir, getLog(),
                        "git", "fetch", "--unshallow");
            }
        } catch (MojoException e) {
            getLog().warn("    Could not check/unshallow " + name
                    + ": " + e.getMessage());
        }
    }

    /**
     * Analyze BOM cascade issues before starting the feature.
     * If issues are found, prompt the developer for confirmation.
     * In headless mode (no console), log warnings and proceed.
     *
     * @return cascade gap rows for the markdown report
     */
    private List<CascadeGapRow> checkBomCascadeAndConfirm(WorkspaceGraph graph, File root)
            throws MojoException {
        // Build published artifact sets
        java.util.Map<String, java.util.Set<PublishedArtifactSet.Artifact>>
                workspaceArtifacts = new java.util.LinkedHashMap<>();
        for (String name : graph.manifest().subprojects().keySet()) {
            java.nio.file.Path subDir = root.toPath().resolve(name);
            if (java.nio.file.Files.exists(subDir.resolve("pom.xml"))) {
                try {
                    workspaceArtifacts.put(name,
                            PublishedArtifactSet.scan(subDir));
                } catch (java.io.IOException e) {
                    // Skip
                }
            }
        }

        java.util.List<BomAnalysis.CascadeIssue> issues;
        try {
            issues = BomAnalysis.analyzeCascadeIssues(
                    root.toPath(), graph.manifest(), workspaceArtifacts);
        } catch (java.io.IOException e) {
            getLog().warn("  BOM cascade check failed: " + e.getMessage());
            return List.of();
        }

        // Filter out gaps that cascadeBomProperties() can resolve (#82).
        // Check if the affected subproject's own POM (or any POM in its
        // module tree) has a <upstream.version> property — if so, the
        // cascade will update it automatically and the gap is handled.
        issues.removeIf(issue -> {
            String propertyName = issue.dependsOn() + ".version";
            // Check the affected subproject's POM tree
            java.nio.file.Path subDir = root.toPath().resolve(issue.subprojectName());
            if (java.nio.file.Files.exists(subDir.resolve("pom.xml"))) {
                try {
                    java.util.List<java.io.File> poms = network.ike.plugin.ReleaseSupport
                            .findPomFiles(subDir.toFile());
                    for (java.io.File pom : poms) {
                        String content = java.nio.file.Files.readString(
                                pom.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                        if (content.contains("<" + propertyName + ">")) {
                            return true; // Gap handled by cascadeBomProperties
                        }
                    }
                } catch (Exception _) { /* skip */ }
            }
            // Also check any workspace subproject's root POM for the
            // convention property — a BOM subproject that manages the
            // upstream's artifacts indicates the cascade path exists
            // through the BOM import chain. We scan all root POMs since
            // the per-subproject type distinction was removed.
            for (String otherName : graph.manifest().subprojects().keySet()) {
                if (otherName.equals(issue.subprojectName())) continue;
                java.nio.file.Path otherPom = root.toPath()
                        .resolve(otherName).resolve("pom.xml");
                if (java.nio.file.Files.exists(otherPom)) {
                    try {
                        String content = java.nio.file.Files.readString(
                                otherPom, java.nio.charset.StandardCharsets.UTF_8);
                        if (content.contains("<" + propertyName + ">")) {
                            return true; // Another subproject has the convention property
                        }
                    } catch (java.io.IOException _) { /* skip */ }
                }
            }
            return false;
        });

        if (issues.isEmpty()) return List.of();

        // Collect structured gap rows for the report
        List<CascadeGapRow> gaps = new ArrayList<>();
        for (var issue : issues) {
            String issueDesc = "no version-property or BOM import";
            if (!issue.externalBomPins().isEmpty()) {
                var bom = issue.externalBomPins().getFirst();
                issueDesc = "pinned by " + bom.groupId()
                        + ":" + bom.artifactId() + ":" + bom.version();
            }
            gaps.add(new CascadeGapRow(
                    issue.subprojectName(), issue.dependsOn(), issueDesc));
        }

        // Report issues to console
        getLog().warn("");
        getLog().warn("  ╔══════════════════════════════════════════════════════════╗");
        getLog().warn("  ║  BOM Cascade Gaps Detected                              ║");
        getLog().warn("  ╚══════════════════════════════════════════════════════════╝");
        getLog().warn("");
        getLog().warn("  The following dependency edges have no version-property or");
        getLog().warn("  workspace-internal BOM import. Feature-start CANNOT cascade");
        getLog().warn("  version changes for these automatically:");
        getLog().warn("");

        for (var issue : issues) {
            getLog().warn("    " + issue.subprojectName() + " → " + issue.dependsOn());
            for (var bom : issue.externalBomPins()) {
                getLog().warn("      external BOM: " + bom.groupId()
                        + ":" + bom.artifactId() + ":" + bom.version());
            }
        }

        getLog().warn("");
        getLog().warn("  These components may resolve stale versions from external BOMs");
        getLog().warn("  instead of the feature branch versions.");
        getLog().warn("");

        // Prompt for confirmation (interactive mode only).
        // In non-interactive mode (tests, CI), warn and proceed.
        java.io.Console console = System.console();
        if (console != null) {
            String response = console.readLine(
                    "  Proceed with feature-start? (yes/no): ");
            if (response == null || !response.trim().toLowerCase().startsWith("y")) {
                throw new MojoException(
                        "Feature-start aborted by user. Fix BOM cascade gaps first.");
            }
        } else {
            getLog().warn("  Non-interactive mode — proceeding with warnings.");
            getLog().warn("  Use ws:verify to review BOM cascade gaps.");
        }

        return gaps;
    }

    /**
     * Cascade BOM import version updates to downstream components.
     *
     * <p>When an upstream subproject's version changes (e.g., tinkar-core
     * gets a branch-qualified version), downstream components that import
     * a BOM published by the upstream need their import version updated.
     */
    private void cascadeBomImports(WorkspaceGraph graph, File root,
                                    List<String> sorted, String branchName)
            throws MojoException {
        // Build published artifact sets and new version map
        java.util.Map<String, java.util.Set<PublishedArtifactSet.Artifact>>
                workspaceArtifacts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> newVersions = new java.util.LinkedHashMap<>();

        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            java.nio.file.Path subDir = root.toPath().resolve(name);

            if (java.nio.file.Files.exists(subDir.resolve("pom.xml"))) {
                try {
                    workspaceArtifacts.put(name,
                            PublishedArtifactSet.scan(subDir));
                } catch (java.io.IOException e) {
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
            Subproject sub = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);
            java.nio.file.Path pomPath = dir.toPath().resolve("pom.xml");

            if (!java.nio.file.Files.exists(pomPath)) continue;

            java.util.List<BomAnalysis.BomImport> bomImports;
            try {
                bomImports = BomAnalysis.extractBomImports(
                        pomPath, workspaceArtifacts);
            } catch (java.io.IOException e) {
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
                        getLog().info("    " + name + ": BOM import "
                                + bom.groupId() + ":" + bom.artifactId()
                                + " → " + newVersion);
                        pomChanged = true;
                    }
                } catch (java.io.IOException e) {
                    getLog().warn("    Could not update BOM import in "
                            + name + ": " + e.getMessage());
                }
            }

            if (pomChanged) {
                try {
                    ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
                    ReleaseSupport.exec(dir, getLog(),
                            "git", "commit", "-m",
                            "feature: update BOM imports for " + branchName);
                } catch (MojoException e) {
                    getLog().warn("    Could not commit BOM update in "
                            + name + ": " + e.getMessage());
                }
            }
        }
    }

    // ── Intra-reactor version pin removal ───────────────────────

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
     * @param root      workspace root directory
     * @param components subproject names to scan
     * @param publish   true to actually remove; false to report only
     */
    private void removeIntraReactorPins(File root, List<String> components,
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
                Set<String> reactorArtifacts = new java.util.LinkedHashSet<>();
                collectReactorArtifacts(subDir.toPath(), rootModel,
                        reactorArtifacts);

                if (reactorArtifacts.size() <= 1) continue;  // no submodules

                // Scan all POMs for pinned intra-reactor dependencies
                List<java.io.File> allPoms = ReleaseSupport.findPomFiles(subDir);
                boolean anyChanged = false;

                for (java.io.File pom : allPoms) {
                    PomModel model = PomModel.parse(pom.toPath());
                    String content = model.content();
                    String updated = content;

                    for (var dep : model.allDependencies()) {
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
                            getLog().info("    removed intra-reactor pin "
                                    + dep.getArtifactId() + " " + version
                                    + " from " + relPath);
                        } else {
                            getLog().info("  [draft] " + name + "/" + relPath
                                    + ": intra-reactor pin " + dep.getArtifactId()
                                    + " " + version
                                    + " would be removed (reactor resolves version)");
                        }
                    }

                    if (publish && !updated.equals(content)) {
                        java.nio.file.Files.writeString(pom.toPath(), updated,
                                java.nio.charset.StandardCharsets.UTF_8);
                        anyChanged = true;
                    }
                }

                if (anyChanged) {
                    ReleaseSupport.exec(subDir, getLog(), "git", "add", "-A");
                    ReleaseSupport.exec(subDir, getLog(),
                            "git", "commit", "-m",
                            "build: remove intra-reactor version pins");
                }
            } catch (java.io.IOException e) {
                getLog().warn("    Could not scan " + name
                        + " for intra-reactor pins: " + e.getMessage());
            }
        }
    }

    /**
     * Recursively collect all artifactIds in a reactor tree by walking
     * the {@code <subprojects>} (or {@code <modules>}) declarations.
     *
     * @param baseDir           directory of the POM being scanned
     * @param model             parsed POM model
     * @param reactorArtifacts  accumulator for discovered artifactIds
     */
    private void collectReactorArtifacts(java.nio.file.Path baseDir,
                                          PomModel model,
                                          Set<String> reactorArtifacts)
            throws java.io.IOException {
        reactorArtifacts.add(model.artifactId());

        for (String sub : model.subprojects()) {
            java.nio.file.Path subDir = baseDir.resolve(sub);
            java.nio.file.Path subPom = subDir.resolve("pom.xml");
            if (java.nio.file.Files.exists(subPom)) {
                PomModel subModel = PomModel.parse(subPom);
                collectReactorArtifacts(subDir, subModel, reactorArtifacts);
            }
        }
    }
}
