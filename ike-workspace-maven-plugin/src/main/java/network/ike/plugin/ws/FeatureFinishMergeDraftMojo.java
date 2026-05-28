package network.ike.plugin.ws;

import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.support.GoalReportBuilder;
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
 * <p>Before performing the merge, this goal refreshes local
 * {@code main} from {@code origin/main} via {@link RefreshMainSupport}
 * so the feature is not merged on top of stale main. If the refresh
 * would produce file conflicts (the rare "two machines edited the
 * same file on main without push/pull" case), the goal hard-errors
 * before touching any feature branch. See ike-issues#284.
 *
 * <p>When to use: long-lived feature branches that periodically merge
 * intermediate work to the target branch. Use when you need
 * traceability of individual feature commits on the target branch.
 *
 * <pre>{@code
 * mvn ws:feature-finish-merge-draft   -Dfeature=long-running
 * mvn ws:feature-finish-merge-publish -Dfeature=long-running
 * mvn ws:feature-finish-merge-publish -Dfeature=done -DkeepBranch=false
 * }</pre>
 *
 * @see RefreshMainSupport for the local-main refresh contract
 * @see FeatureFinishSquashDraftMojo for clean single-commit merges (default)
 */
@Mojo(name = "feature-finish-merge-draft", projectRequired = false, aggregator = true)
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

    /**
     * Skip the remote-branch deletion step entirely (still deletes the
     * local branch unless {@code keepBranch=true}). Useful when branch
     * protection forbids deletion. Remote-deletion failures are
     * <em>soft</em> by default — the goal warns and continues. See
     * IKE-Network/ike-issues#532.
     */
    @Parameter(property = "keepRemoteBranch", defaultValue = "false")
    boolean keepRemoteBranch;

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
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (!isWorkspaceMode()) {
            if (feature == null || feature.isBlank()) {
                feature = requireParam(feature, "feature",
                        "Feature to merge (without feature/ prefix)");
            }
            validateFeatureName(feature);
            return executeBareMode("feature/" + feature);
        }

        // Auto-detect feature from subproject branches if not specified
        if (feature == null || feature.isBlank()) {
            WorkspaceGraph g = loadGraph();
            List<String> all = g.topologicalSort();
            feature = FeatureFinishSupport.detectFeature(
                    workspaceRoot(), all, this, getLog());
        }
        validateFeatureName(feature);
        String branchName = "feature/" + feature;

        // message is optional — auto-generated from subproject history
        return executeWorkspaceMode(branchName);
    }

    private WorkspaceReportSpec executeWorkspaceMode(String branchName)
            throws MojoException {
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
        // #535: see FeatureFinishSquashDraftMojo for the rationale.
        List<String> alreadyDone = new ArrayList<>();
        for (String name : reversed) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            String reason = FeatureFinishSupport.validateComponent(
                    root, name, branchName, targetBranch, subproject, this);
            if (reason == null) {
                eligible.add(name);
            } else if ("MODIFIED".equals(reason)) {
                uncommitted.add(name);
            } else if (FeatureFinishSupport.ALREADY_DONE.equals(reason)) {
                alreadyDone.add(name);
                getLog().info(Ansi.green("  ✓ ") + name
                        + " — already on " + targetBranch
                        + " from a prior run (workspace.yaml will be reconciled)");
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
            sb.append("Please commit these changes first (mvn "
                      + WsGoal.COMMIT_PUBLISH.qualified() + "), ")
              .append("then re-run feature-finish.");
            if (draft) {
                getLog().warn("");
                getLog().warn(sb.toString());
                getLog().warn("");
            } else {
                throw new MojoException(sb.toString());
            }
        }

        if (eligible.isEmpty() && alreadyDone.isEmpty()) {
            getLog().info("  No components on " + branchName + " — nothing to do.");
            return new WorkspaceReportSpec(
                    publish ? WsGoal.FEATURE_FINISH_MERGE_PUBLISH
                            : WsGoal.FEATURE_FINISH_MERGE_DRAFT,
                    "No components on `" + branchName + "` — nothing to do.\n");
        }

        // Refresh local main from origin/main before merging the feature
        // branch in. Avoids shipping the feature on top of stale main.
        // See ike-issues#284.
        RefreshMainSupport.refreshOrThrow(root, eligible, targetBranch, getLog());

        // Auto-generate commit message from per-subproject history
        String generatedMessage = FeatureFinishSupport.generateFeatureMessage(
                root, eligible, branchName, targetBranch, message, getLog());
        getLog().info("  Commit message:");
        for (String line : generatedMessage.split("\n")) {
            getLog().info("    " + line);
        }
        getLog().info("");

        int merged = 0;
        // #532: soft-fail and collect — same behaviour as the squash variant.
        java.util.LinkedHashMap<String, String> undeletedRemote =
                new java.util.LinkedHashMap<>();
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
                String remoteFailReason = FeatureFinishSupport.deleteBranch(
                        dir, getLog(), branchName, keepRemoteBranch);
                if (remoteFailReason != null) {
                    undeletedRemote.put(name, remoteFailReason);
                }
            }

            VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);
            merged++;
        }

        // #535: yaml reconciliation list includes already-done from
        // prior partial runs.
        List<String> needsYamlReconcile = new ArrayList<>(eligible);
        for (String name : alreadyDone) {
            if (!needsYamlReconcile.contains(name)) {
                needsYamlReconcile.add(name);
            }
        }

        if (merged > 0 && publish) {
            FeatureFinishSupport.cleanFeatureSites(root, eligible, branchName, getLog());
            FeatureFinishSupport.mergeWorkspaceRepo(
                    manifestPath, branchName, targetBranch, keepBranch, push, getLog());
        }

        if (publish && !needsYamlReconcile.isEmpty()) {
            FeatureFinishSupport.updateWorkspaceYaml(
                    manifestPath, needsYamlReconcile, targetBranch, feature, getLog());
        }

        // Offer stale branch cleanup (#100)
        if (publish && merged > 0) {
            FeatureFinishSupport.promptStaleBranchCleanup(
                    root, eligible, branchName, targetBranch,
                    getPrompter(), getLog());
        }

        getLog().info("");
        getLog().info("  Merged: " + merged + " components (no-ff)");
        if (!alreadyDone.isEmpty()) {
            getLog().info("  Already-done from prior run: " + alreadyDone.size()
                    + " (workspace.yaml reconciled)");
        }
        getLog().info("  Branch " + (keepBranch ? "kept" : "deleted") + ": " + branchName);
        if (!undeletedRemote.isEmpty()) {
            getLog().warn("");
            getLog().warn("  " + undeletedRemote.size()
                    + " remote feature branch(es) could not be deleted "
                    + "(soft-fail per #532):");
            for (var entry : undeletedRemote.entrySet()) {
                getLog().warn("    • " + entry.getKey()
                        + " — " + entry.getValue());
            }
            getLog().warn("  To clean up by hand:");
            for (String subName : undeletedRemote.keySet()) {
                getLog().warn("    (cd " + subName
                        + " && git push origin --delete " + branchName + ")");
            }
        }
        getLog().info("");

        // Structured markdown report
        return new WorkspaceReportSpec(
                publish ? WsGoal.FEATURE_FINISH_MERGE_PUBLISH
                        : WsGoal.FEATURE_FINISH_MERGE_DRAFT,
                buildMergeReport(eligible, branchName, targetBranch,
                        merged, draft, keepBranch, undeletedRemote,
                        alreadyDone));
    }

    /**
     * Build the merge-strategy markdown report. Includes the
     * remote-deletion soft-fail summary with copy-pasteable manual
     * cleanup commands (#532).
     *
     * @param components       eligible subprojects
     * @param branch           feature branch name
     * @param target           target branch name
     * @param merged           count of subprojects merged (or that would be)
     * @param isDraft          draft preview?
     * @param kept             {@code -DkeepBranch=true}?
     * @param undeletedRemote  subproject → git error for remote branches
     *                         the soft-fail step could not delete
     */
    private String buildMergeReport(List<String> components, String branch,
                                     String target, int merged,
                                     boolean isDraft, boolean kept,
                                     java.util.Map<String, String> undeletedRemote,
                                     List<String> alreadyDone) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Branch:** `" + branch + "` → `" + target + "`  \n"
                + "**Strategy:** no-fast-forward merge");

        List<String[]> rows = new ArrayList<>();
        for (String name : components) {
            rows.add(new String[]{name, isDraft ? "would merge" : "merged"});
        }
        for (String name : alreadyDone) {
            rows.add(new String[]{name, isDraft
                    ? "would reconcile workspace.yaml only"
                    : "reconciled workspace.yaml only (already on "
                            + target + " from a prior run)"});
        }
        report.table(List.of("Subproject", "Status"), rows);

        report.paragraph("**" + merged + " subproject(s)** "
                + (isDraft ? "would be merged" : "merged")
                + (alreadyDone.isEmpty()
                        ? ""
                        : "; **" + alreadyDone.size() + "** already-done "
                                + "from a prior run (workspace.yaml "
                                + (isDraft ? "would be" : "was")
                                + " reconciled)")
                + ". Branch " + (kept ? "kept" : "deleted") + ".");

        if (!undeletedRemote.isEmpty()) {
            report.section("Remote feature branches not deleted");
            report.paragraph("The merge succeeded, but origin refused "
                    + "to delete " + undeletedRemote.size()
                    + " remote feature branch(es) — typically because "
                    + "branch protection forbids deletion. The goal "
                    + "soft-failed and continued; clean these up manually:");
            for (var entry : undeletedRemote.entrySet()) {
                report.bullet("**" + entry.getKey() + "** — `"
                        + entry.getValue() + "`");
            }
            StringBuilder cleanup = new StringBuilder();
            for (String subName : undeletedRemote.keySet()) {
                cleanup.append("(cd ").append(subName)
                        .append(" && git push origin --delete ")
                        .append(branch).append(")\n");
            }
            report.codeBlock("bash", cleanup.toString().stripTrailing());
            report.paragraph("Or, to skip the remote-deletion attempt "
                    + "next time, pass `-DkeepRemoteBranch=true`.");
        }
        return report.build();
    }

    private WorkspaceReportSpec executeBareMode(String branchName)
            throws MojoException {
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
            return new WorkspaceReportSpec(WsGoal.FEATURE_FINISH_MERGE_DRAFT,
                    "Bare repo: would merge `" + branchName + "` → `"
                            + targetBranch + "`.\n");
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

        String remoteFailReason = null;
        if (!keepBranch) {
            remoteFailReason = FeatureFinishSupport.deleteBranch(
                    dir, getLog(), branchName, keepRemoteBranch);
        }

        VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);

        if (remoteFailReason != null) {
            getLog().warn("  Remote feature branch could not be deleted "
                    + "(soft-fail per #532): " + remoteFailReason);
            getLog().warn("  Clean up manually: git push origin --delete "
                    + branchName);
        }
        getLog().info("  Done.");
        getLog().info("");
        StringBuilder body = new StringBuilder();
        body.append("Bare repo: merged `").append(branchName).append("` → `")
                .append(targetBranch).append("`.\n");
        if (remoteFailReason != null) {
            body.append("\n**Remote feature branch not deleted** — `")
                    .append(remoteFailReason).append("`.\n\n")
                    .append("Clean up manually:\n\n```bash\n")
                    .append("git push origin --delete ").append(branchName)
                    .append("\n```\n");
        }
        return new WorkspaceReportSpec(WsGoal.FEATURE_FINISH_MERGE_PUBLISH,
                body.toString());
    }
}
