package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;

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
 * <p>Before branching, this goal refreshes local {@code main} from
 * {@code origin/main} via {@link RefreshMainSupport} so the new
 * feature branch starts from current main rather than whatever stale
 * state happens to be on the local machine. If the refresh would
 * produce file conflicts, the goal hard-errors before any branch is
 * created. See ike-issues#284.
 *
 * <p><strong>Workspace mode</strong> (workspace.yaml found):</p>
 * <ol>
 *   <li>Refreshes local main from {@code origin/main}</li>
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
 * mvn ws:feature-start-draft   -Dfeature=shield-terminology
 * mvn ws:feature-start-publish -Dfeature=shield-terminology
 * mvn ws:feature-start-publish -Dfeature=doc-refresh -DskipVersion=true
 * }</pre>
 *
 * @see RefreshMainSupport for the local-main refresh contract
 */
@Mojo(name = "feature-start-draft", projectRequired = false, aggregator = true)
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

    /**
     * Comma-separated subset of workspace subproject names to branch.
     * When set, only these subprojects are branched and version-cascaded;
     * the rest of the workspace is left on its current branch. When
     * unset (the default), every subproject in the workspace is branched
     * — the historical behaviour.
     *
     * <p>The release cascade (IKE-Network/ike-issues#499, part of #488)
     * computes its release-set closure from the derived graph
     * (#496) and passes that closure here as {@code -Daffected=...},
     * so feature-start branches only what the cascade is about to
     * release rather than the whole workspace.
     *
     * <p>Each name must match a subproject declared in
     * {@code workspace.yaml}; unknown names fail the goal early.
     */
    @Parameter(property = "affected")
    String affected;

    /** Creates this goal instance. */
    public FeatureStartDraftMojo() {}

    /**
     * Reusable cascade / version-qualification helpers, lazily
     * instantiated in {@link #execute()} so the injected logger is
     * available. Extracted from this class in ike-issues#204 so that
     * sibling-clone work (#201) can share the same logic.
     */
    private FeatureStartSupport support;

    /** A row in the feature-start summary table. */
    private record BranchRow(String subproject, String branch,
                              String snapshotVersion, String status) {}

    /** A row in the BOM cascade gaps table. */
    private record CascadeGapRow(String consumer, String dependency,
                                  String issue) {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        support = new FeatureStartSupport(getLog());
        feature = requireParam(feature, "feature", "Feature name (without feature/ prefix)");
        validateFeatureName(feature);
        String branchName = "feature/" + feature;

        if (!isWorkspaceMode()) {
            return executeBareMode(branchName);
        }

        // --- Workspace mode ---
        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // VCS bridge: catch-up before branching
        VcsOperations.catchUp(root, getLog());

        Set<String> known = graph.manifest().subprojects().keySet();
        Set<String> targets = resolveAffectedSubset(known);
        boolean scoped = targets.size() < known.size();

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        getLog().info("");
        getLog().info(header("Feature Start"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName);
        if (scoped) {
            getLog().info("  Scope:   " + sorted.size() + " of "
                    + known.size() + " components (--affected)");
        } else {
            getLog().info("  Scope:   " + sorted.size() + " components");
        }
        if (draft) {
            getLog().info("  Mode:    DRAFT");
        }
        getLog().info("");

        // Refresh local main from origin/main before branching, so the new
        // feature branch starts from current main. In draft, preview
        // read-only — never mutate local main (#570). See ike-issues#284.
        if (publish) {
            RefreshMainSupport.refreshOrThrow(root, sorted, "main", getLog());
        } else {
            RefreshMainSupport.previewRefresh(root, sorted, "main", getLog());
        }

        // Analyze BOM cascade issues and prompt for confirmation
        List<CascadeGapRow> cascadeGaps = new ArrayList<>();
        if (!skipVersion) {
            cascadeGaps = checkBomCascadeAndConfirm(graph, root);
        }

        List<String> created = new ArrayList<>();
        List<String> repaired = new ArrayList<>();
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
                // Already on the target branch. The branch may pre-date
                // feature-start's version qualification (created outside
                // feature-start, or a partial/aborted run), so the POM can still
                // carry the base version. Self-heal: qualify it if it isn't
                // already. branchQualifiedVersion is idempotent, so an
                // already-qualified POM is a no-op that falls through to the plain
                // "already on branch" skip below (ike-issues#720).
                String alreadyPomVersion = null;
                File alreadyPom = new File(dir, "pom.xml");
                if (!skipVersion && alreadyPom.exists()) {
                    try {
                        alreadyPomVersion = ReleaseSupport.readPomVersion(alreadyPom);
                    } catch (MojoException e) {
                        getLog().debug("Could not read POM version for "
                                + name + ": " + e.getMessage());
                    }
                }
                String alreadyQualified = (alreadyPomVersion != null
                        && !alreadyPomVersion.isEmpty())
                        ? VersionSupport.branchQualifiedVersion(alreadyPomVersion, branchName)
                        : null;
                if (alreadyQualified != null
                        && !alreadyQualified.equals(alreadyPomVersion)) {
                    if (draft) {
                        getLog().info("  [draft] " + name + " on " + branchName
                                + ": would qualify " + alreadyPomVersion
                                + " -> " + alreadyQualified);
                        branchRows.add(new BranchRow(name, branchName,
                                alreadyQualified, "would qualify (repair)"));
                    } else {
                        support.setPomVersion(dir, alreadyPomVersion, alreadyQualified);
                        ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
                        ReleaseSupport.exec(dir, getLog(), "git", "commit", "-m",
                                "feature: qualify version " + alreadyQualified
                                        + " for " + branchName);
                        getLog().info("  qualified " + name + ": "
                                + alreadyPomVersion + " -> " + alreadyQualified
                                + " (already on branch)");
                        branchRows.add(new BranchRow(name, branchName,
                                alreadyQualified, "qualified (repair)"));
                    }
                    repaired.add(name);
                    continue;
                }
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
            support.ensureFullClone(dir, name);

            ReleaseSupport.exec(dir, getLog(),
                    "git", "checkout", "-b", branchName);

            if (!skipVersion && effectiveVersion != null
                    && !effectiveVersion.isEmpty()) {
                support.setPomVersion(dir, effectiveVersion, newVersion);
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

        // Newly-branched OR repaired (already on the branch, qualified now) — both
        // need the version cascade, pin removal, and VCS-state writes; only
        // newly-created branches need the workspace repo branched (ike-issues#720).
        List<String> versioned = new ArrayList<>(created);
        versioned.addAll(repaired);

        // Remove intra-reactor version pins (draft reports, publish removes)
        if (!versioned.isEmpty()) {
            support.removeIntraReactorPins(root, versioned, publish);
        }

        // Cascade version-property updates to downstream components
        if (!versioned.isEmpty() && publish && !skipVersion) {
            support.cascadeVersionProperties(graph, root, sorted, branchName);
            support.cascadeBomProperties(graph, root, sorted, branchName);
            support.cascadeBomImports(graph, root, sorted, branchName);
        }

        // Write VCS state for each branched subproject (no push — branches stay local)
        if (!versioned.isEmpty() && publish) {
            for (String name : versioned) {
                File dir = new File(root, name);
                VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_START);
            }
        }

        // Branch the workspace repo, update workspace.yaml, AND branch-qualify the
        // aggregator's own pom — feature-start branches the top, so its version
        // gets the same qualifier as every subproject (ike-issues#721). Runs for
        // newly-created OR repaired branches; draft just previews.
        if (!versioned.isEmpty() && publish) {
            branchWorkspaceRepo(branchName, versioned);
        } else if (!versioned.isEmpty() && !skipVersion) {
            previewWorkspaceRootQualify(branchName);
        }

        getLog().info("");
        getLog().info("  " + (draft ? "To create: " : "Created: ") + created.size()
                + " | Qualified (repair): " + repaired.size()
                + " | Already on branch: " + skippedAlreadyOnBranch.size()
                + " | Not cloned: " + skippedNotCloned.size());
        getLog().info("");

        // Structured markdown report
        return new WorkspaceReportSpec(
                publish ? WsGoal.FEATURE_START_PUBLISH : WsGoal.FEATURE_START_DRAFT,
                buildMarkdownReport(branchName, branchRows, cascadeGaps));
    }

    private String buildMarkdownReport(String branchName,
                                        List<BranchRow> branchRows,
                                        List<CascadeGapRow> cascadeGaps) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Branch:** `" + branchName + "`");

        List<String[]> rows = new ArrayList<>();
        for (BranchRow row : branchRows) {
            rows.add(new String[]{row.subproject(), row.branch(),
                    row.snapshotVersion(), row.status()});
        }
        report.table(
                List.of("Subproject", "Branch", "Snapshot Version", "Status"),
                rows);

        boolean draft = !publish;
        report.paragraph("**" + branchRows.size() + "** subproject(s) "
                + (draft ? "would be branched" : "branched") + " onto `"
                + branchName + "`.");

        if (!cascadeGaps.isEmpty()) {
            List<String[]> gapRows = new ArrayList<>();
            for (CascadeGapRow row : cascadeGaps) {
                gapRows.add(new String[]{row.consumer(),
                        row.dependency(), row.issue()});
            }
            report.paragraph("**BOM cascade gaps:**")
                    .table(List.of("Consumer", "Dependency", "Issue"), gapRows);
        }

        return report.build();
    }

    /**
     * Bare-mode: create feature branch in the current repo only.
     */
    private WorkspaceReportSpec executeBareMode(String branchName) throws MojoException {
        boolean draft = !publish;
        // Bare mode = a working set of one (ike-issues#611).
        File dir = resolveWorkingSet().members().getFirst().directory().toFile();

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
            return new WorkspaceReportSpec(
                    publish ? WsGoal.FEATURE_START_PUBLISH : WsGoal.FEATURE_START_DRAFT,
                    "[draft] Would create `" + branchName + "` in `"
                            + dir.getName() + "`.\n");
        }

        // Auto-unshallow if needed
        support.ensureFullClone(dir, dir.getName());

        // Create branch
        ReleaseSupport.exec(dir, getLog(),
                "git", "checkout", "-b", branchName);
        getLog().info("  Created " + branchName);

        // Set branch-qualified version
        if (currentVersion != null && !currentVersion.isEmpty()) {
            String newVersion = VersionSupport.branchQualifiedVersion(
                    currentVersion, branchName);
            getLog().info("  Version: " + currentVersion + " \u2192 " + newVersion);
            support.setPomVersion(dir, currentVersion, newVersion);
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
        return new WorkspaceReportSpec(
                publish ? WsGoal.FEATURE_START_PUBLISH : WsGoal.FEATURE_START_DRAFT,
                "Created `" + branchName + "` in `" + dir.getName() + "`.\n");
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

            // Create the branch only if the workspace repo isn't already on it.
            // On a repair run (ike-issues#721) it already is — switch nothing.
            String wsBranch = VcsOperations.currentBranch(wsRoot);
            if (!wsBranch.equals(branchName)) {
                if (wsBranch.startsWith("feature/")) {
                    getLog().info("  Workspace repo: switching " + wsBranch + " to main");
                    VcsOperations.checkout(wsRoot, getLog(), "main");
                }
                getLog().info("  Branching workspace repo to " + branchName);
                VcsOperations.checkoutNew(wsRoot, getLog(), branchName);
            }

            // Branch-qualify the aggregator's own POM version too — feature-start
            // branches the workspace root, so its version gets the same qualifier
            // as every subproject (idempotent; ike-issues#721).
            boolean wsPomQualified = false;
            File wsPom = new File(wsRoot, "pom.xml");
            if (!skipVersion && wsPom.exists()) {
                try {
                    String wsVersion = ReleaseSupport.readPomVersion(wsPom);
                    String wsQualified = VersionSupport.branchQualifiedVersion(
                            wsVersion, branchName);
                    if (!wsQualified.equals(wsVersion)) {
                        support.setPomVersion(wsRoot, wsVersion, wsQualified);
                        ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "pom.xml");
                        getLog().info("  qualified workspace root: " + wsVersion
                                + " -> " + wsQualified);
                        wsPomQualified = true;
                    }
                } catch (MojoException e) {
                    getLog().debug("Could not qualify workspace root version: "
                            + e.getMessage());
                }
            }

            // Update workspace.yaml branch fields (idempotent on a repair run).
            Map<String, String> updates = new LinkedHashMap<>();
            for (String name : components) {
                updates.put(name, branchName);
            }
            ManifestWriter.updateBranches(manifestPath, updates);
            getLog().info("  Updated workspace.yaml branches for "
                    + components.size() + " components");

            ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
            if (VcsOperations.hasStagedChanges(wsRoot)) {
                VcsOperations.commit(wsRoot, getLog(), wsPomQualified
                        ? "feature: qualify workspace root + branches for " + branchName
                        : "workspace: update branches for " + branchName);
            } else {
                getLog().info("  workspace repo already up to date - nothing to commit");
            }

            // Write VCS state (no push — branch stays local)
            VcsOperations.writeVcsState(wsRoot, VcsState.Action.FEATURE_START);

        } catch (IOException e) {
            getLog().warn("  Could not update workspace.yaml: " + e.getMessage());
        }
    }

    /**
     * Draft preview of the aggregator-pom branch-qualification — the publish-side
     * work happens in {@link #branchWorkspaceRepo} (ike-issues#721).
     */
    private void previewWorkspaceRootQualify(String branchName) {
        try {
            File wsPom = new File(
                    resolveManifest().getParent().toFile(), "pom.xml");
            if (!wsPom.exists()) {
                return;
            }
            String version = ReleaseSupport.readPomVersion(wsPom);
            String qualified = VersionSupport.branchQualifiedVersion(version, branchName);
            if (!qualified.equals(version)) {
                getLog().info("  [draft] workspace root: would qualify "
                        + version + " -> " + qualified);
            }
        } catch (MojoException e) {
            getLog().debug("Could not preview workspace root version: "
                    + e.getMessage());
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

        // Prompt for confirmation. In batch mode (no Prompter — e.g.
        // unit tests, headless CI), the helper returns the default.
        // Default true here so unattended runs proceed past BOM
        // cascade warnings; the warnings above are already visible in
        // the build log for human review.
        if (!confirm("Proceed with feature-start?", true)) {
            throw new MojoException(
                    "Feature-start aborted. Fix BOM cascade gaps first.");
        }

        return gaps;
    }

    /**
     * Resolves the {@code --affected} parameter into a concrete
     * subset of workspace subproject names. Returns {@code known}
     * unchanged when {@code --affected} is unset; otherwise parses
     * the comma-separated list, trims each name, validates every
     * one against the workspace manifest, and returns the resulting
     * ordered set.
     *
     * <p>An empty or blank token is silently discarded so trailing
     * commas don't trip the goal. An unknown name fails the goal
     * with a message naming the offender — the release cascade is
     * the primary consumer of {@code --affected}, and it should
     * never pass a name that isn't in the manifest; if it does,
     * that is a bug in the cascade's release-set computation that
     * deserves a hard error rather than a silent drop.
     */
    private Set<String> resolveAffectedSubset(Set<String> known) {
        if (affected == null || affected.isBlank()) {
            return known;
        }
        Set<String> selected = new LinkedHashSet<>();
        for (String raw : affected.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!known.contains(name)) {
                throw new MojoException(
                        "Unknown subproject in --affected: '" + name
                        + "'. Known subprojects: " + known);
            }
            selected.add(name);
        }
        if (selected.isEmpty()) {
            throw new MojoException(
                    "--affected was set but resolved to an empty"
                    + " subset; supply at least one subproject name"
                    + " or omit the parameter to branch the whole"
                    + " workspace.");
        }
        return selected;
    }
}
