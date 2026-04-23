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
 * Squash-merge a feature branch back to the target branch.
 *
 * <p>This is the <b>default and recommended</b> strategy for finishing
 * features. The feature branch's full commit history is compressed into
 * a single commit on the target branch. The feature branch is deleted
 * after merge because squash creates divergent history — continuing
 * on the branch would cause conflicts.
 *
 * <p>Use {@code -DkeepBranch=true} only if you understand that the
 * branch can no longer be cleanly merged again.
 *
 * <p>When to use: most features. Feature branch history is disposable.
 * Target branch gets one clean commit.
 *
 * <pre>{@code
 * mvn ike:feature-finish-squash -Dfeature=my-feature -Dmessage="Add widget support"
 * }</pre>
 *
 * @see FeatureFinishMergeDraftMojo for long-lived branches
 */
@Mojo(name = "feature-finish-squash-draft", projectRequired = false)
public class FeatureFinishSquashDraftMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public FeatureFinishSquashDraftMojo() {}

    /** Feature name. Expects branch {@code feature/<name>}. Prompted if omitted. */
    @Parameter(property = "feature")
    String feature;

    /** Target branch to merge into. */
    @Parameter(property = "targetBranch", defaultValue = "main")
    String targetBranch;

    /**
     * Keep the feature branch after squash-merge. Default is false because
     * squash creates divergent history — the branch cannot be cleanly merged
     * again.
     */
    @Parameter(property = "keepBranch", defaultValue = "false")
    boolean keepBranch;

    /**
     * Squash commit message. Required — draft warns that publish will
     * fail if missing; publish refuses before any mutation (see #160).
     */
    @Parameter(property = "message")
    String message;

    /**
     * Push merged target branch to origin after merge. Default is false
     * because checkpoint is the natural CI handoff point, not feature-finish.
     */
    @Parameter(property = "push", defaultValue = "false")
    boolean push;

    /** Show plan without executing. */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    @Override
    public void execute() throws MojoException {
        boolean draft = !publish;

        if (!isWorkspaceMode()) {
            if (feature == null || feature.isBlank()) {
                feature = requireParam(feature, "feature",
                        "Feature to squash-merge (without feature/ prefix)");
            }
            validateMessage(draft);
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
        validateMessage(draft);
        executeWorkspaceMode("feature/" + feature);
    }

    /**
     * Ensure {@code -Dmessage=...} is supplied before any mutation path
     * runs. In draft mode this emits a warning (so the plan still
     * renders); in publish mode it aborts before any VCS operation
     * touches a subproject. Fixes #160 — null message previously
     * propagated into {@code git commit -m} and NPE'd mid-operation
     * on the first subproject, leaving partial state.
     *
     * @param draft whether we're in draft (warn) or publish (throw) mode
     * @throws MojoException in publish mode when message is missing
     */
    private void validateMessage(boolean draft) throws MojoException {
        if (message != null && !message.isBlank()) return;
        String detail = WsGoal.FEATURE_FINISH_SQUASH_PUBLISH.qualified()
                + " requires -Dmessage=\"...\" — the squash commit message "
                + "is not auto-generated.";
        if (draft) {
            getLog().warn("");
            getLog().warn("  ⚠ " + detail);
            getLog().warn("");
        } else {
            throw new MojoException(detail);
        }
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
        getLog().info(header("Feature Finish (squash)"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
        getLog().info("  Strategy: squash-merge");
        if (draft) getLog().info("  Mode:     DRAFT");
        getLog().info("");

        // Catch-up
        VcsOperations.catchUp(root, getLog());

        // Validate and collect eligible components
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

        // Merge each subproject
        int merged = 0;
        for (String name : eligible) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);

            if (draft) {
                getLog().info("  [draft] " + name + " — would squash-merge → " + targetBranch);
                merged++;
                continue;
            }

            getLog().info(Ansi.cyan("  → ") + name);
            VcsOperations.catchUp(dir, getLog());
            FeatureFinishSupport.stripBranchVersion(dir, subproject, branchName, getLog());

            VcsOperations.checkout(dir, getLog(), targetBranch);
            VcsOperations.mergeSquash(dir, getLog(), branchName);

            if (VcsOperations.hasStagedChanges(dir)) {
                VcsOperations.commit(dir, getLog(), message);
                FeatureFinishSupport.verifyAndFixQualifiers(dir, branchName, getLog());
                if (push) {
                    VcsOperations.pushIfRemoteExists(dir, getLog(), "origin", targetBranch);
                }
            } else {
                getLog().info("    no changes after squash (version-only branch) — skipping commit");
                // #162: clear .git/SQUASH_MSG & .git/MERGE_MSG so a later
                // git commit doesn't pick up the template and land an
                // empty "Squashed commit of the following:" on main.
                VcsOperations.resetHard(dir, getLog(), "HEAD");
            }

            if (!keepBranch) {
                FeatureFinishSupport.deleteBranch(dir, getLog(), branchName);
            }

            VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);
            merged++;
        }

        // Clean up sites
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
        getLog().info("  Squash-merged: " + merged + " components");
        if (!keepBranch) {
            getLog().info("  Branch deleted: " + branchName);
        }
        getLog().info("");

        // Structured markdown report
        writeReport(publish ? WsGoal.FEATURE_FINISH_SQUASH_PUBLISH : WsGoal.FEATURE_FINISH_SQUASH_DRAFT, buildSquashReport(
                eligible, branchName, targetBranch, merged, draft, keepBranch));
    }

    private String buildSquashReport(List<String> components, String branch,
                                      String target, int merged,
                                      boolean isDraft, boolean kept) {
        var sb = new StringBuilder();
        sb.append("**Branch:** `").append(branch).append("` → `")
          .append(target).append("`\n");
        sb.append("**Strategy:** squash-merge\n\n");

        sb.append("| Subproject | Status |\n");
        sb.append("|-----------|--------|\n");
        for (String name : components) {
            sb.append("| ").append(name).append(" | ")
              .append(isDraft ? "would squash" : "squashed").append(" |\n");
        }

        sb.append("\n**").append(merged).append(" subproject(s)** ")
          .append(isDraft ? "would be squash-merged" : "squash-merged")
          .append(". Branch ").append(kept ? "kept" : "deleted").append(".\n");
        return sb.toString();
    }

    private void executeBareMode(String branchName) throws MojoException {
        boolean draft = !publish;
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");
        getLog().info("IKE Feature Finish — Squash (bare repo)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
        if (draft) getLog().info("  Mode:     DRAFT");
        getLog().info("");

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
            getLog().info("  [draft] Would squash-merge → " + targetBranch);
            return;
        }

        FeatureFinishSupport.stripBranchVersionBare(dir, branchName, getLog());

        VcsOperations.checkout(dir, getLog(), targetBranch);
        VcsOperations.mergeSquash(dir, getLog(), branchName);

        if (VcsOperations.hasStagedChanges(dir)) {
            VcsOperations.commit(dir, getLog(), message);
            FeatureFinishSupport.verifyAndFixQualifiers(dir, branchName, getLog());
            if (push) {
                VcsOperations.pushIfRemoteExists(dir, getLog(), "origin", targetBranch);
            }
        } else {
            getLog().info("  No changes after squash — skipping commit");
            // #162: see executeWorkspaceMode for rationale.
            VcsOperations.resetHard(dir, getLog(), "HEAD");
        }

        if (!keepBranch) {
            FeatureFinishSupport.deleteBranch(dir, getLog(), branchName);
        }

        VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);

        getLog().info("");
        getLog().info("  Done.");
        getLog().info("");
    }
}
