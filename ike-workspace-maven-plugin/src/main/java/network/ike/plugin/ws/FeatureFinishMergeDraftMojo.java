package network.ike.plugin.ws;

import network.ike.workspace.Subproject;
import network.ike.workspace.WorkingSet;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ReleaseSupport;
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
import java.util.Set;

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
     * Push the merged target branch to origin after merge, verifying each
     * member's push against the remote before any feature branch is
     * deleted (the two-phase contract of IKE-Network/ike-issues#858).
     * Default is true: a finish lands the feature on
     * {@code origin/<targetBranch>}. With {@code -Dpush=false} the merges
     * stay local AND every feature branch is kept, because deletion is
     * only permitted after a confirmed push.
     */
    @Parameter(property = "push", defaultValue = "true")
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

        Set<String> targets = graph.manifest().subprojects().keySet();
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
            StringBuilder sb = new StringBuilder();
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
        // Includes the workspace root repo (#857/#858). In draft, preview
        // read-only — never mutate local main (#570). See ike-issues#284.
        if (publish) {
            RefreshMainSupport.refreshOrThrowWithRoot(
                    root, eligible, targetBranch, getLog());
        } else {
            RefreshMainSupport.previewRefreshWithRoot(
                    root, eligible, targetBranch, getLog());
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
        // #532: soft-fail and collect — same behaviour as the squash variant.
        java.util.LinkedHashMap<String, String> undeletedRemote =
                new java.util.LinkedHashMap<>();
        // #544: per-subproject target-branch HEAD after each merge so
        // the report can show the resulting SHA next to the status.
        java.util.Map<String, String> targetSha =
                new java.util.LinkedHashMap<>();

        if (draft) {
            for (String name : eligible) {
                getLog().info("  [draft] " + name + " — would merge → " + targetBranch);
                merged++;
            }
        } else {
            // #667: front-load the version strip into its own pass over
            // every eligible subproject BEFORE any merge runs. After this
            // pass each feature-branch POM is plain -SNAPSHOT, so a crash
            // partway through the merge pass leaves a compilable tree
            // rather than a mix of stripped and branch-qualified POMs.
            for (String name : eligible) {
                Subproject subproject = graph.manifest().subprojects().get(name);
                File dir = new File(root, name);
                getLog().info(Ansi.cyan("  ⤓ ") + name + " — strip version qualifier");
                VcsOperations.catchUp(dir, getLog());
                FeatureFinishSupport.stripBranchVersion(dir, subproject, branchName, getLog());
            }

            // #667: merge pass — checkout target + no-ff merge + post-steps
            // per subproject. Track what has merged so a failure here can
            // tell the user exactly how to resume (re-running this goal
            // skips already-merged subprojects via ALREADY_DONE).
            List<String> mergedSoFar = new ArrayList<>();
            for (int i = 0; i < eligible.size(); i++) {
                String name = eligible.get(i);
                File dir = new File(root, name);

                try {
                    getLog().info(Ansi.cyan("  → ") + name);
                    VcsOperations.checkout(dir, getLog(), targetBranch);
                    VcsOperations.mergeNoFf(dir, getLog(), branchName, generatedMessage);
                    FeatureFinishSupport.verifyAndFixQualifiers(dir, branchName, getLog());
                    // #858: no push and no branch deletion here — pushing is
                    // a dedicated verified phase after every member merged
                    // (and after the aggregator's reconciliation commit,
                    // #791); deletion only follows a confirmed push phase.
                    try {
                        targetSha.put(name, VcsOperations.headSha(dir));
                    } catch (MojoException ignored) {}

                    VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);
                    mergedSoFar.add(name);
                    merged++;
                } catch (MojoException e) {
                    List<String> remaining =
                            new ArrayList<>(eligible.subList(i + 1, eligible.size()));
                    throw new MojoException(
                            FeatureFinishSupport.resumeGuidance(
                                    WsGoal.FEATURE_FINISH_MERGE_PUBLISH, feature,
                                    mergedSoFar, name, remaining),
                            e);
                }
            }
        }

        // #544 + #535: include already-done subprojects' current target-
        // branch HEAD next to their reconciliation row.
        for (String name : alreadyDone) {
            File dir = new File(root, name);
            try {
                targetSha.put(name, VcsOperations.headSha(dir));
            } catch (MojoException ignored) {}
        }

        // #535: yaml reconciliation list includes already-done from
        // prior partial runs.
        List<String> needsYamlReconcile = new ArrayList<>(eligible);
        for (String name : alreadyDone) {
            if (!needsYamlReconcile.contains(name)) {
                needsYamlReconcile.add(name);
            }
        }

        // The workspace-repo merge also runs on a pure re-run (merged==0
        // but already-done members present) so a prior crash's pending
        // root merge completes (#858).
        if (publish && (merged > 0 || !alreadyDone.isEmpty())) {
            FeatureFinishSupport.cleanFeatureSites(root, eligible, branchName, getLog());
            FeatureFinishSupport.mergeWorkspaceRepo(
                    manifestPath, branchName, targetBranch, getLog());
        }

        // Runs BEFORE the push phase so the aggregator's reconciliation
        // commit is included in the root push (#791).
        if (publish && !needsYamlReconcile.isEmpty()) {
            FeatureFinishSupport.updateWorkspaceYaml(
                    manifestPath, needsYamlReconcile, targetBranch, feature, getLog());
        }

        // ── Push phase (#858/#791): verified pushes for every member +
        // the workspace root; no branch deletion until all confirmed.
        List<FeatureFinishSupport.PushResult> pushResults = List.of();
        if (publish && push && (merged > 0 || !alreadyDone.isEmpty())) {
            getLog().info("");
            getLog().info("  " + Ansi.cyan("→ ") + "Pushing " + targetBranch
                    + " for every member (verified)...");
            java.util.LinkedHashMap<String, File> memberDirs =
                    new java.util.LinkedHashMap<>();
            for (String name : needsYamlReconcile) {
                memberDirs.put(name, new File(root, name));
            }
            memberDirs.put(RefreshMainSupport.ROOT_LABEL, root);
            pushResults = FeatureFinishSupport.pushTargetsVerified(
                    memberDirs, targetBranch, getLog());
        }
        List<FeatureFinishSupport.PushResult> pushFailures = pushResults.stream()
                .filter(r -> !r.pushed()).toList();

        // ── Delete phase (#858): gated on the confirmed push phase (or a
        // fully local working set with no remote to strand against).
        String branchesKeptReason = null;
        if (publish) {
            branchesKeptReason = FeatureFinishSupport.deletePhase(
                    root, needsYamlReconcile, branchName, targetBranch,
                    keepBranch, push, !pushFailures.isEmpty(),
                    keepRemoteBranch, undeletedRemote, getLog());
        }

        // Offer stale branch cleanup (#100)
        if (publish && merged > 0 && pushFailures.isEmpty()) {
            FeatureFinishSupport.promptStaleBranchCleanup(
                    root, eligible, branchName, targetBranch,
                    getPrompter(), getLog());
        }

        if (!pushFailures.isEmpty()) {
            throw new MojoException(FeatureFinishSupport.pushPhaseFailureMessage(
                    pushFailures, targetBranch));
        }

        getLog().info("");
        getLog().info("  Merged: " + merged + " components (no-ff)");
        if (!alreadyDone.isEmpty()) {
            getLog().info("  Already-done from prior run: " + alreadyDone.size()
                    + " (workspace.yaml reconciled)");
        }
        if (publish && push && !pushResults.isEmpty()) {
            getLog().info("  Pushed " + targetBranch + ": "
                    + pushResults.stream().filter(
                            FeatureFinishSupport.PushResult::pushed).count()
                    + "/" + pushResults.size() + " members verified");
        }
        if (branchesKeptReason != null) {
            getLog().info("  Branch kept: " + branchName
                    + " (" + branchesKeptReason + ")");
        } else {
            getLog().info("  Branch "
                    + (keepBranch ? "kept" : "deleted") + ": " + branchName);
        }
        if (!undeletedRemote.isEmpty()) {
            getLog().warn("");
            getLog().warn("  " + undeletedRemote.size()
                    + " remote feature branch(es) could not be deleted "
                    + "(soft-fail per #532):");
            for (java.util.Map.Entry<String, String> entry : undeletedRemote.entrySet()) {
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
                        alreadyDone, targetSha));
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
                                     List<String> alreadyDone,
                                     java.util.Map<String, String> targetSha) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Branch:** `" + branch + "` → `" + target + "`  \n"
                + "**Strategy:** no-fast-forward merge");

        // #764/#763: one row per working-set member — every subproject AND
        // the workspace-root aggregator — rendered through the shared
        // WorkingSetReportTable. The aggregator's version/branch/SHA are
        // gathered the same way as a subproject (the #763 fix); previously
        // the workspace repo was no-ff merged but never shown in the table.
        // The Effect column carries the per-member merge outcome and the
        // resulting target-branch HEAD SHA lands in the SHA column (#544).
        WorkingSet workingSet = resolveWorkingSet();
        List<WorkingSetReportTable.Row> rows = new ArrayList<>();
        for (WorkingSet.Member member : workingSet.members()) {
            File dir = member.directory().toFile();
            String version = readPomVersion(dir);
            String memberBranch = gitBranch(dir);
            String memberSha;
            String effect;

            if (member.isAggregator()) {
                // The workspace repo is no-ff merged by mergeWorkspaceRepo
                // only when at least one subproject merged and we're
                // publishing; the draft previews the same intent (#763 fix:
                // the aggregator now appears in the table either way).
                memberSha = isDraft ? "—" : gitShortSha(dir);
                if (merged > 0) {
                    effect = isDraft
                            ? "would merge `" + branch + "` → `" + target + "`"
                            : "merged `" + branch + "` → `" + target + "`";
                } else {
                    effect = "no-op (no subprojects merged)";
                }
            } else if (components.contains(member.name())) {
                String sha = targetSha.getOrDefault(member.name(), "—");
                effect = isDraft
                        ? "would merge `" + branch + "` → `" + target + "`"
                        : "merged `" + branch + "` → `" + target + "`";
                memberSha = "—".equals(sha) ? "—" : shorten(sha);
            } else if (alreadyDone.contains(member.name())) {
                String sha = targetSha.getOrDefault(member.name(), "—");
                effect = isDraft
                        ? "would reconcile workspace.yaml only"
                        : "reconciled workspace.yaml only (already on "
                                + target + " from a prior run)";
                memberSha = "—".equals(sha) ? "—" : shorten(sha);
            } else {
                // A subproject not on the feature branch — skipped this run.
                effect = "skipped (not on `" + branch + "`)";
                memberSha = "—";
            }

            rows.add(new WorkingSetReportTable.Row(
                    member, version, memberBranch, memberSha, effect));
        }
        WorkingSetReportTable.render(report, "Subprojects", rows);

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
            for (java.util.Map.Entry<String, String> entry : undeletedRemote.entrySet()) {
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

        if (isDraft) {
            report.section("What publish will do");
            report.bullet("Strip the feature version qualifier across the "
                    + merged + " eligible subproject(s) (a `merge-prep` commit).");
            report.bullet("No-fast-forward merge `" + branch + "` → `"
                    + target + "` in each subproject.");
            report.bullet(kept
                    ? "Keep the feature branch `" + branch + "`."
                    : "Delete the feature branch `" + branch + "` (local"
                            + (keepRemoteBranch ? "" : " + remote") + ").");

            report.section("To publish");
            String publishCmd = "mvn "
                    + WsGoal.FEATURE_FINISH_MERGE_PUBLISH.qualified()
                    + " -Dfeature=" + (feature == null ? "<name>" : feature);
            if (message != null && !message.isBlank()) {
                publishCmd += " -Dmessage=\"" + message.replace("\"", "\\\"") + "\"";
            }
            if (!keepBranch) publishCmd += " -DkeepBranch=false";
            if (keepRemoteBranch) publishCmd += " -DkeepRemoteBranch=true";
            if (push) publishCmd += " -Dpush=true";
            report.codeBlock("bash", publishCmd);
        }
        return report.build();
    }

    private WorkspaceReportSpec executeBareMode(String branchName)
            throws MojoException {
        boolean draft = !publish;
        // Bare mode = a working set of one (ike-issues#611).
        File dir = resolveWorkingSet().members().getFirst().directory().toFile();

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

        // #858: push (verified) BEFORE any branch deletion; with
        // -Dpush=false or a failed push the branch is kept.
        List<FeatureFinishSupport.PushResult> barePush = List.of();
        if (push) {
            java.util.LinkedHashMap<String, File> one =
                    new java.util.LinkedHashMap<>();
            one.put(dir.getName(), dir);
            barePush = FeatureFinishSupport.pushTargetsVerified(
                    one, targetBranch, getLog());
        }
        boolean pushConfirmed = push
                && barePush.stream().allMatch(
                        FeatureFinishSupport.PushResult::pushed);

        String remoteFailReason = null;
        String bareKeptReason = null;
        if (keepBranch) {
            bareKeptReason = "-DkeepBranch=true";
        } else if (!push && FeatureFinishSupport.anyHasOriginRemote(List.of(dir))) {
            bareKeptReason = "-Dpush=false — the branch is only deleted "
                    + "after a confirmed push of " + targetBranch;
        } else if (push && !pushConfirmed) {
            bareKeptReason = "push failure (see below)";
        } else {
            remoteFailReason = FeatureFinishSupport.deleteBranch(
                    dir, getLog(), branchName, keepRemoteBranch);
        }
        if (bareKeptReason != null) {
            getLog().info("  Branch kept: " + branchName
                    + " (" + bareKeptReason + ")");
        }

        VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);

        if (push && !pushConfirmed) {
            throw new MojoException(FeatureFinishSupport.pushPhaseFailureMessage(
                    barePush.stream().filter(r -> !r.pushed()).toList(),
                    targetBranch));
        }

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

    private static String shorten(String sha) {
        if (sha == null || sha.length() <= 7) return sha;
        return sha.substring(0, 7);
    }

    /**
     * Read a working-set member's POM version (the {@code <version>} of
     * {@code <dir>/pom.xml}), returning {@link WorkingSetReportTable#NONE}
     * when no POM is present or it cannot be parsed. Applied uniformly to
     * subprojects and the workspace-root aggregator — the aggregator's
     * version is part of the #763 fix (its row was previously absent).
     *
     * @param dir the member directory
     * @return the POM version, or {@code "—"} when unavailable
     */
    private String readPomVersion(File dir) {
        File pom = new File(dir, "pom.xml");
        if (!pom.isFile()) return WorkingSetReportTable.NONE;
        try {
            return ReleaseSupport.readPomVersion(pom);
        } catch (MojoException e) {
            return WorkingSetReportTable.NONE;
        }
    }
}
