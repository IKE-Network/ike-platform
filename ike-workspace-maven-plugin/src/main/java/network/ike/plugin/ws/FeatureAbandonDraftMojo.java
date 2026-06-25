package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;

import network.ike.workspace.Subproject;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.WorkingSet;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Abandon a feature branch across all workspace subprojects.
 *
 * <p>The draft variant previews what would be abandoned — which components,
 * how many unmerged commits, what would be lost. The publish variant
 * prompts for confirmation then executes the deletion.
 *
 * <p>Components are processed in reverse topological order (downstream
 * first) to avoid transient dependency issues.
 *
 * <pre>{@code
 * mvn ws:feature-abandon-draft                       # preview
 * mvn ws:feature-abandon-publish                     # execute (with confirmation)
 * mvn ws:feature-abandon-publish -Dforce=true        # skip confirmation
 * mvn ws:feature-abandon-publish -DdeleteRemote=true # also delete remote branches
 * }</pre>
 *
 * @see FeatureStartDraftMojo for creating feature branches
 */
@Mojo(name = "feature-abandon-draft", projectRequired = false, aggregator = true)
public class FeatureAbandonDraftMojo extends AbstractWorkspaceMojo {

    @Parameter(property = "feature")
    String feature;

    @Parameter(property = "targetBranch")
    String targetBranch;

    @Parameter(property = "deleteRemote", defaultValue = "false")
    boolean deleteRemote;

    @Parameter(property = "force", defaultValue = "false")
    boolean force;

    /** Execute the abandon. Default is draft (preview only). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Creates this goal instance. */
    public FeatureAbandonDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (!isWorkspaceMode()) {
            return executeBareMode();
        }

        return executeWorkspaceMode();
    }

    private WorkspaceReportSpec executeWorkspaceMode() throws MojoException {
        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        if (targetBranch == null || targetBranch.isBlank()) {
            targetBranch = graph.manifest().defaults().branch();
            if (targetBranch == null) targetBranch = "main";
        }

        Set<String> targets = graph.manifest().subprojects().keySet();

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));
        List<String> reversed = new ArrayList<>(sorted);
        Collections.reverse(reversed);

        // Preflight: all working trees must be clean (#132)
        PreflightResult preflight = Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, sorted));
        if (draft) {
            preflight.warnIfFailed(getLog(), WsGoal.FEATURE_ABANDON_PUBLISH);
        } else {
            preflight.requirePassed(WsGoal.FEATURE_ABANDON_PUBLISH);
        }

        // Auto-detect feature branch if not specified
        if (feature == null || feature.isBlank()) {
            feature = detectFeatureBranch(root, reversed);
        }
        validateFeatureName(feature);
        String branchName = "feature/" + feature;

        // Capture the aggregator's (workspace root) branch BEFORE any mutation
        // so its report Effect is accurate in publish mode too, where the root
        // has already been switched to the target branch by the time the report
        // is built (#763, under epic #764).
        String aggregatorBranchBefore = new File(root, ".git").exists()
                ? gitBranch(root) : null;

        // Collect eligible components and show preview
        getLog().info("");
        getLog().info(header("Feature Abandon"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
        if (deleteRemote) getLog().info("  Remote:   will delete origin/" + branchName);
        if (draft) getLog().info("  Mode:     DRAFT");
        getLog().info("");

        List<String> eligible = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        // Per-member effect, keyed by member name, used to build the shared
        // working-set report table (#767, under epic #764). The aggregator's
        // effect is computed separately, after the subproject loop.
        Map<String, String> effects = new LinkedHashMap<>();
        int totalUnmerged = 0;

        for (String name : reversed) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().info(Ansi.yellow("  · ") + name + " — not cloned");
                skipped.add(name);
                effects.put(name, "skipped (not cloned)");
                continue;
            }

            String currentBranch = gitBranch(dir);
            if (!currentBranch.equals(branchName)) {
                getLog().info(Ansi.yellow("  · ") + name + " — on "
                        + currentBranch + ", not on feature");
                skipped.add(name);
                effects.put(name, "skipped (not on " + branchName + ")");
                continue;
            }

            // Check for uncommitted changes
            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                throw new MojoException(
                        name + " has uncommitted changes. Commit, stash, or discard before abandoning.");
            }

            // Check for unmerged commits
            int unmergedCount = 0;
            try {
                String unmerged = ReleaseSupport.execCapture(dir,
                        "git", "log", "--oneline",
                        targetBranch + ".." + branchName);
                if (!unmerged.isBlank()) {
                    unmergedCount = (int) unmerged.lines().count();
                }
            } catch (MojoException e) {
                // Target branch may not exist locally
            }

            totalUnmerged += unmergedCount;

            if (unmergedCount > 0) {
                String label = draft ? "would abandon" : "abandon";
                getLog().info(Ansi.yellow("  ⚠ ") + name + " — "
                        + unmergedCount + " unmerged commit(s) — " + label);
            } else {
                String label = draft ? "would abandon (clean)" : "abandon";
                getLog().info(Ansi.cyan("  → ") + name + " — " + label);
            }
            eligible.add(name);
            effects.put(name, abandonEffect(draft, branchName, targetBranch,
                    unmergedCount));
        }

        if (eligible.isEmpty()) {
            getLog().info("  No components on " + branchName + " — nothing to abandon.");
            getLog().info("");
            // Still render the working-set table so the report shows every
            // member (the aggregator included) and why each was skipped (#763).
            return writeAbandonReport(branchName, targetBranch, aggregatorBranchBefore,
                    effects,
                    eligible, skipped, draft);
        }

        getLog().info("");
        getLog().info("  " + eligible.size() + " subproject(s) on " + branchName);
        if (totalUnmerged > 0) {
            getLog().warn("  " + totalUnmerged + " total unmerged commit(s) will be lost");
        }

        if (draft) {
            getLog().info("");
            getLog().info("  Next: mvn "
                    + WsGoal.FEATURE_ABANDON_PUBLISH.qualified()
                    + (force ? "" : " (will prompt for confirmation)"));
            getLog().info("");
            return writeAbandonReport(branchName, targetBranch, aggregatorBranchBefore,
                    effects,
                    eligible, skipped, draft);
        }

        // Publish mode — prompt for confirmation
        if (!force && !confirm("Abandon feature/" + feature + "?", false)) {
            throw new MojoException("Abandon cancelled.");
        }

        // Execute
        for (String name : eligible) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);

            // Strip branch-qualified versions before switching
            FeatureFinishSupport.stripBranchVersion(dir, subproject, branchName, getLog());

            VcsOperations.checkout(dir, getLog(), targetBranch);
            VcsOperations.deleteBranch(dir, getLog(), branchName);

            if (deleteRemote) {
                try {
                    VcsOperations.deleteRemoteBranch(dir, getLog(), "origin", branchName);
                } catch (MojoException e) {
                    getLog().warn("    could not delete remote branch: " + e.getMessage());
                }
            }

            VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);
            getLog().info(Ansi.green("  ✓ ") + name + " → " + targetBranch);
        }

        // Update workspace.yaml and workspace repo
        if (!eligible.isEmpty()) {
            abandonWorkspaceRepo(manifestPath, eligible, branchName);
        }

        getLog().info("");
        getLog().info("  Abandoned: " + eligible.size()
                + " | Skipped: " + skipped.size());
        if (!deleteRemote) {
            getLog().info("  Remote branches kept. Use -DdeleteRemote=true to delete them.");
        }
        getLog().info("");

        return writeAbandonReport(branchName, targetBranch, aggregatorBranchBefore,
                    effects,
                eligible, skipped, draft);
    }

    private WorkspaceReportSpec writeAbandonReport(String branchName,
                                     String targetBranch, String aggregatorBranchBefore,
                                     Map<String, String> effects,
                                     List<String> eligible, List<String> skipped,
                                     boolean isDraft) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Branch:** `" + branchName + "`");

        // One row per working-set member — the aggregator (workspace root)
        // included — so the staleness a subproject-only table hid is visible
        // (#763, under epic #764). The Effect column states what the goal did
        // (publish) or would do (draft) to each member.
        List<WorkingSetReportTable.Row> rows = new ArrayList<>();
        for (WorkingSet.Member member : resolveWorkingSet().members()) {
            File dir = member.directory().toFile();
            String version = readMemberVersion(dir);
            String branch = gitBranch(dir);
            String sha = gitShortSha(dir);
            String effect = member.isAggregator()
                    ? aggregatorEffect(member, aggregatorBranchBefore, branchName,
                            targetBranch, isDraft)
                    : effects.getOrDefault(member.name(), "skipped (no-op)");
            rows.add(new WorkingSetReportTable.Row(member, version, branch, sha,
                    effect));
        }
        WorkingSetReportTable.render(report, "Working set", rows);

        report.paragraph("**" + eligible.size() + "** "
                + (isDraft ? "would be abandoned" : "abandoned")
                + ", **" + skipped.size() + "** skipped.");
        return new WorkspaceReportSpec(
                publish ? WsGoal.FEATURE_ABANDON_PUBLISH : WsGoal.FEATURE_ABANDON_DRAFT,
                report.build());
    }

    /**
     * Read a working-set member's POM version for the report, returning
     * {@code null} (rendered as the table's {@code —} placeholder) when the
     * member has no readable {@code pom.xml}. This gathers the aggregator's
     * version the same way as a subproject — the {@code #763} fix that surfaces
     * a workspace root left on a stale branch-qualified version — without
     * letting a non-Maven member fail the whole report.
     *
     * @param dir the member's directory
     * @return the POM version, or {@code null} if none can be read
     */
    private String readMemberVersion(File dir) {
        File pom = new File(dir, "pom.xml");
        if (!pom.isFile()) {
            return null;
        }
        try {
            return ReleaseSupport.readPomVersion(pom);
        } catch (MojoException e) {
            return null;
        }
    }

    /**
     * The Effect cell for an eligible subproject — phrased as planned for a
     * draft goal, applied for publish.
     *
     * @param draft         whether this is the draft (preview) variant
     * @param branchName    the feature branch being abandoned
     * @param targetBranch  the branch switched to after abandoning
     * @param unmergedCount unmerged commits that would be lost
     * @return the Effect cell text
     */
    private static String abandonEffect(boolean draft, String branchName,
                                        String targetBranch, int unmergedCount) {
        String verb = draft ? "would abandon" : "abandoned";
        String tail = " " + branchName + " → " + targetBranch;
        if (unmergedCount > 0) {
            return verb + tail + " (" + unmergedCount + " unmerged lost)";
        }
        return verb + tail;
    }

    /**
     * The Effect cell for the aggregator (workspace root). Mirrors
     * {@link #abandonWorkspaceRepo}: when the workspace repo is itself on the
     * feature branch it is reverted and switched to the target branch;
     * otherwise its branches are reconciled but it stays put. Phrased as
     * planned for a draft goal, applied for publish. Uses the pre-execution
     * branch ({@code branchBefore}) so the cell is accurate even in publish
     * mode, where the report is built after the root has been switched.
     *
     * @param member       the aggregator member
     * @param branchBefore the aggregator's branch before any mutation, or
     *                     {@code null} if the root is not a git working tree
     * @param branchName   the feature branch being abandoned
     * @param targetBranch the branch switched to after abandoning
     * @param draft        whether this is the draft (preview) variant
     * @return the Effect cell text
     */
    private String aggregatorEffect(WorkingSet.Member member, String branchBefore,
                                    String branchName, String targetBranch,
                                    boolean draft) {
        if (branchBefore == null
                || !new File(member.directory().toFile(), ".git").exists()) {
            return "skipped (not cloned)";
        }
        if (branchBefore.equals(branchName)) {
            return draft
                    ? "would revert workspace.yaml + switch → " + targetBranch
                    : "reverted workspace.yaml + switched → " + targetBranch;
        }
        return draft
                ? "would reconcile workspace.yaml branches"
                : "reconciled workspace.yaml branches";
    }

    // ── Auto-detect ─────────────────────────────────────────────────

    /**
     * Scan workspace subprojects for feature branches and return
     * the feature name. If multiple features are found, prompts
     * the user to choose.
     */
    private String detectFeatureBranch(File root, List<String> components)
            throws MojoException {
        Set<String> features = new TreeSet<>();

        for (String name : components) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            String branch = gitBranch(dir);
            if (branch.startsWith("feature/")) {
                features.add(branch.substring("feature/".length()));
            }
        }

        if (features.isEmpty()) {
            throw new MojoException(
                    "No components are on a feature branch. Nothing to abandon.");
        }

        if (features.size() == 1) {
            String detected = features.iterator().next();
            getLog().info("  Detected feature: " + detected);
            return detected;
        }

        // Multiple features — present a numbered selection menu
        List<String> featureList = new ArrayList<>(features);
        String picked = selectFromList("Multiple feature branches detected"
                + " — pick one to abandon", featureList);
        if (picked != null) {
            return picked;
        }

        throw new MojoException(
                "Multiple features found: " + features
                        + ". Specify with -Dfeature=<name>.");
    }

    // ── Bare mode ───────────────────────────────────────────────────

    private WorkspaceReportSpec executeBareMode() throws MojoException {
        boolean draft = !publish;
        // Bare mode = a working set of one (ike-issues#611).
        File dir = resolveWorkingSet().members().getFirst().directory().toFile();

        if (targetBranch == null || targetBranch.isBlank()) {
            targetBranch = "main";
        }

        String currentBranch = gitBranch(dir);
        if (feature == null || feature.isBlank()) {
            if (currentBranch.startsWith("feature/")) {
                feature = currentBranch.substring("feature/".length());
            } else {
                throw new MojoException(
                        "Not on a feature branch (on " + currentBranch
                                + "). Specify with -Dfeature=<name>.");
            }
        }
        validateFeatureName(feature);
        String branchName = "feature/" + feature;

        getLog().info("");
        getLog().info("IKE Feature Abandon (bare repo)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName + " → " + targetBranch);
        if (draft) getLog().info("  Mode:    DRAFT");
        getLog().info("");

        if (!currentBranch.equals(branchName)) {
            throw new MojoException(
                    "Not on " + branchName + " (currently on " + currentBranch + ")");
        }
        if (!gitStatus(dir).isEmpty()) {
            throw new MojoException(
                    "Uncommitted changes. Commit, stash, or discard first.");
        }

        if (draft) {
            getLog().info("  [draft] Would abandon " + branchName
                    + " and switch to " + targetBranch);
            getLog().info("");
            getLog().info("  Next: mvn "
                    + WsGoal.FEATURE_ABANDON_PUBLISH.qualified());
            getLog().info("");
            return new WorkspaceReportSpec(WsGoal.FEATURE_ABANDON_DRAFT,
                    "Bare repo: would abandon `" + branchName + "` and switch to `"
                            + targetBranch + "`.\n");
        }

        // Publish mode — prompt for confirmation
        if (!force && !confirm("Abandon feature/" + feature + "?", false)) {
            throw new MojoException("Abandon cancelled.");
        }

        FeatureFinishSupport.stripBranchVersionBare(dir, branchName, getLog());

        VcsOperations.checkout(dir, getLog(), targetBranch);
        VcsOperations.deleteBranch(dir, getLog(), branchName);
        getLog().info(Ansi.green("  ✓ ") + "Switched to " + targetBranch
                + ", deleted " + branchName);

        if (deleteRemote) {
            try {
                VcsOperations.deleteRemoteBranch(dir, getLog(), "origin", branchName);
                getLog().info(Ansi.green("  ✓ ") + "Deleted remote branch");
            } catch (MojoException e) {
                getLog().warn("  Could not delete remote branch: " + e.getMessage());
            }
        }

        VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);
        getLog().info("");
        return new WorkspaceReportSpec(WsGoal.FEATURE_ABANDON_PUBLISH,
                "Bare repo: abandoned `" + branchName + "`, switched to `"
                        + targetBranch + "`.\n");
    }

    // ── Workspace repo cleanup ──────────────────────────────────────

    private void abandonWorkspaceRepo(Path manifestPath,
                                       List<String> components,
                                       String branchName)
            throws MojoException {
        try {
            Map<String, String> updates = new LinkedHashMap<>();
            for (String name : components) {
                updates.put(name, targetBranch);
            }
            ManifestWriter.updateBranches(manifestPath, updates);

            File wsRoot = manifestPath.getParent().toFile();
            if (!new File(wsRoot, ".git").exists()) return;

            String wsBranch = gitBranch(wsRoot);
            if (wsBranch.equals(branchName)) {
                ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                if (VcsOperations.hasStagedChanges(wsRoot)) {
                    VcsOperations.commit(wsRoot, getLog(),
                            "workspace: revert branches for abandon " + branchName);
                }

                VcsOperations.checkout(wsRoot, getLog(), targetBranch);

                try {
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "cherry-pick", branchName);
                } catch (MojoException e) {
                    ManifestWriter.updateBranches(manifestPath, updates);
                    ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                    if (VcsOperations.hasStagedChanges(wsRoot)) {
                        VcsOperations.commit(wsRoot, getLog(),
                                "workspace: revert branches after abandon " + branchName);
                    }
                }

                VcsOperations.deleteBranch(wsRoot, getLog(), branchName);

                if (deleteRemote) {
                    try {
                        VcsOperations.deleteRemoteBranch(wsRoot, getLog(), "origin", branchName);
                    } catch (MojoException e) {
                        getLog().warn("  Could not delete workspace remote branch: "
                                + e.getMessage());
                    }
                }
            } else {
                ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                if (VcsOperations.hasStagedChanges(wsRoot)) {
                    VcsOperations.commit(wsRoot, getLog(),
                            "workspace: revert branches after abandon " + branchName);
                }
            }

            VcsOperations.pushIfRemoteExists(wsRoot, getLog(), "origin", targetBranch);

        } catch (IOException e) {
            getLog().warn("  Could not update workspace.yaml: " + e.getMessage());
        }
    }
}
