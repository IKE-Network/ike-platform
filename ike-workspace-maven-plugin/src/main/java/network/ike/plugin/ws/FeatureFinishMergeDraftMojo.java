package network.ike.plugin.ws;

import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * No-fast-forward merge of a feature branch, preserving full history.
 *
 * <p>Creates a merge commit on the target branch containing the
 * complete feature branch history. The feature branch is <b>kept alive</b>
 * by default because histories stay connected — the branch can
 * continue to receive work and be merged again later.
 *
 * <p>When to use: long-lived feature branches that periodically merge
 * intermediate work to the target branch. Use when you need
 * traceability of individual feature commits on the target branch.
 *
 * <pre>{@code
 * mvn ike:feature-finish-merge -Dfeature=long-running
 * mvn ike:feature-finish-merge -Dfeature=done-feature -DkeepBranch=false
 * }</pre>
 *
 * @see FeatureFinishSquashDraftMojo for clean single-commit merges (default)
 */
@Mojo(name = "feature-finish-merge-draft", projectRequired = false)
public class FeatureFinishMergeDraftMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public FeatureFinishMergeDraftMojo() {}

    @Parameter(property = "feature")
    String feature;

    @Parameter(property = "targetBranch", defaultValue = "main")
    String targetBranch;

    /**
     * Keep the feature branch after merge. Default is true because
     * no-ff merge preserves history — the branch can continue to
     * receive work and be merged again.
     */
    @Parameter(property = "keepBranch", defaultValue = "true")
    boolean keepBranch = true;

    @Parameter(property = "message")
    String message;

    /**
     * Push merged target branch to origin after merge. Default is false
     * because checkpoint is the natural CI handoff point, not feature-finish.
     */
    @Parameter(property = "push", defaultValue = "false")
    boolean push;

    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    @Override
    public void execute() throws MojoException {
        if (!isWorkspaceMode()) {
            if (feature == null || feature.isBlank()) {
                feature = requireParam(feature, "feature",
                        "Feature to merge (without feature/ prefix)");
            }
            executeBareMode("feature/" + feature);
            return;
        }

        // Auto-detect feature from subproject branches if not specified
        if (feature == null || feature.isBlank()) {
            WorkspaceGraph g = loadGraph();
            List<String> all = g.topologicalSort();
            feature = FeatureFinishSupport.detectFeature(
                    workspaceRoot(), all, this, getLog());
        }
        String branchName = "feature/" + feature;

        // message is optional — auto-generated from subproject history
        executeWorkspaceMode(branchName);
    }

    private void executeWorkspaceMode(String branchName) throws MojoException {
        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        var targets = graph.manifest().subprojects().keySet();
        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));
        List<String> reversed = new ArrayList<>(sorted);
        Collections.reverse(reversed);

        getLog().info("");
        getLog().info(header("Feature Finish (merge)"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
        getLog().info("  Strategy: no-fast-forward merge");
        if (draft) getLog().info("  Mode:     DRAFT");
        getLog().info("");

        VcsOperations.catchUp(root, getLog());

        List<String> eligible = new ArrayList<>();
        List<String> uncommitted = new ArrayList<>();
        for (String name : reversed) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            String reason = FeatureFinishSupport.validateComponent(
                    root, name, branchName, subproject, this);
            if (reason == null) {
                eligible.add(name);
            } else if ("MODIFIED".equals(reason)) {
                uncommitted.add(name);
            } else {
                getLog().info(Ansi.yellow("  · ") + name + " — " + reason + ", skipping");
            }
        }

        // Check workspace root for uncommitted changes (#102)
        if (new File(root, ".git").exists() && !gitStatus(root).isEmpty()) {
            uncommitted.add("workspace root");
        }

        if (!uncommitted.isEmpty()) {
            var sb = new StringBuilder();
            sb.append("Cannot finish feature — uncommitted changes in:\n");
            for (String name : uncommitted) {
                sb.append("  ").append(name).append("\n");
            }
            sb.append("Please commit these changes first (mvn ws:commit), ")
              .append("then re-run feature-finish.");
            if (draft) {
                getLog().warn("");
                getLog().warn(sb.toString());
                getLog().warn("");
            } else {
                throw new MojoException(sb.toString());
            }
        }

        if (eligible.isEmpty()) {
            getLog().info("  No components on " + branchName + " — nothing to do.");
            return;
        }

        // Auto-generate commit message from per-subproject history
        String generatedMessage = FeatureFinishSupport.generateFeatureMessage(
                root, eligible, branchName, targetBranch, message, getLog());
        getLog().info("  Commit message:");
        for (String line : generatedMessage.split("\n")) {
            getLog().info("    " + line);
        }
        getLog().info("");

        int merged = 0;
        for (String name : eligible) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);

            if (draft) {
                getLog().info("  [draft] " + name + " — would merge → " + targetBranch);
                merged++;
                continue;
            }

            getLog().info(Ansi.cyan("  → ") + name);
            VcsOperations.catchUp(dir, getLog());
            FeatureFinishSupport.stripBranchVersion(dir, subproject, branchName, getLog());

            VcsOperations.checkout(dir, getLog(), targetBranch);
            VcsOperations.mergeNoFf(dir, getLog(), branchName, generatedMessage);
            FeatureFinishSupport.verifyAndFixQualifiers(dir, branchName, getLog());
            if (push) {
                VcsOperations.pushIfRemoteExists(dir, getLog(), "origin", targetBranch);
            }

            if (!keepBranch) {
                FeatureFinishSupport.deleteBranch(dir, getLog(), branchName);
            }

            VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);
            merged++;
        }

        if (merged > 0 && publish) {
            FeatureFinishSupport.cleanFeatureSites(root, eligible, branchName, getLog());
            FeatureFinishSupport.updateWorkspaceYaml(
                    manifestPath, eligible, targetBranch, feature, getLog());
            FeatureFinishSupport.mergeWorkspaceRepo(
                    manifestPath, branchName, targetBranch, keepBranch, push, getLog());
        }

        // Offer stale branch cleanup (#100)
        if (publish && merged > 0) {
            FeatureFinishSupport.promptStaleBranchCleanup(
                    root, eligible, branchName, targetBranch, getLog());
        }

        getLog().info("");
        getLog().info("  Merged: " + merged + " components (no-ff)");
        getLog().info("  Branch " + (keepBranch ? "kept" : "deleted") + ": " + branchName);
        getLog().info("");

        // Structured markdown report
        writeReport(publish ? WsGoal.FEATURE_FINISH_MERGE_PUBLISH : WsGoal.FEATURE_FINISH_MERGE_DRAFT, buildMergeReport(
                eligible, branchName, targetBranch, merged, draft, keepBranch));
    }

    private String buildMergeReport(List<String> components, String branch,
                                     String target, int merged,
                                     boolean isDraft, boolean kept) {
        var sb = new StringBuilder();
        sb.append("**Branch:** `").append(branch).append("` → `")
          .append(target).append("`\n");
        sb.append("**Strategy:** no-fast-forward merge\n\n");

        sb.append("| Subproject | Status |\n");
        sb.append("|-----------|--------|\n");
        for (String name : components) {
            sb.append("| ").append(name).append(" | ")
              .append(isDraft ? "would merge" : "merged").append(" |\n");
        }

        sb.append("\n**").append(merged).append(" subproject(s)** ")
          .append(isDraft ? "would be merged" : "merged")
          .append(". Branch ").append(kept ? "kept" : "deleted").append(".\n");
        return sb.toString();
    }

    private void executeBareMode(String branchName) throws MojoException {
        boolean draft = !publish;
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");
        getLog().info("IKE Feature Finish — Merge (bare repo)");
        getLog().info("══════════════════════════════════════════════════════════════");

        VcsOperations.catchUp(dir, getLog());

        String currentBranch = gitBranch(dir);
        if (!currentBranch.equals(branchName)) {
            throw new MojoException(
                    "Not on " + branchName + " (currently on " + currentBranch + ")");
        }
        if (!gitStatus(dir).isEmpty()) {
            throw new MojoException("Uncommitted changes. Commit or stash first.");
        }

        if (draft) {
            getLog().info("  [draft] Would merge → " + targetBranch);
            return;
        }

        // Auto-generate message for bare mode
        String bareMessage = (message != null && !message.isBlank())
                ? message
                : "Merge " + branchName + " into " + targetBranch;

        FeatureFinishSupport.stripBranchVersionBare(dir, branchName, getLog());

        VcsOperations.checkout(dir, getLog(), targetBranch);
        VcsOperations.mergeNoFf(dir, getLog(), branchName, bareMessage);
        FeatureFinishSupport.verifyAndFixQualifiers(dir, branchName, getLog());
        if (push) {
            VcsOperations.pushIfRemoteExists(dir, getLog(), "origin", targetBranch);
        }

        if (!keepBranch) {
            FeatureFinishSupport.deleteBranch(dir, getLog(), branchName);
        }

        VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);

        getLog().info("  Done.");
        getLog().info("");
    }
}
