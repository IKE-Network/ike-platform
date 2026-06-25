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
import java.util.Map;
import java.util.Set;

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
 * <p>Before performing the squash-merge, this goal refreshes local
 * {@code main} from {@code origin/main} via {@link RefreshMainSupport}
 * so the feature is not landed on top of stale main. If the refresh
 * would produce file conflicts, the goal hard-errors before touching
 * any feature branch. See ike-issues#284.
 *
 * <p>When to use: most features. Feature branch history is disposable.
 * Target branch gets one clean commit.
 *
 * <pre>{@code
 * mvn ws:feature-finish-squash-draft   -Dfeature=my-feature -Dmessage="Add widget"
 * mvn ws:feature-finish-squash-publish -Dfeature=my-feature -Dmessage="Add widget"
 * }</pre>
 *
 * @see RefreshMainSupport for the local-main refresh contract
 * @see FeatureFinishMergeDraftMojo for long-lived branches
 */
@Mojo(name = "feature-finish-squash-draft", projectRequired = false, aggregator = true)
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
     * Skip the remote-branch deletion step entirely (still deletes the
     * local branch unless {@code keepBranch=true}). Useful when branch
     * protection forbids deletion and you don't want the goal to even
     * try. Remote-deletion failures are <em>soft</em> by default — the
     * goal warns and continues; this flag suppresses the warning by
     * not attempting the delete at all. IKE-Network/ike-issues#532.
     */
    @Parameter(property = "keepRemoteBranch", defaultValue = "false")
    boolean keepRemoteBranch;

    /**
     * Squash commit message. Optional — when omitted, an auto-generated
     * message is built from the feature-branch commit history of every
     * eligible subproject (see {@link FeatureFinishSupport#generateFeatureMessage},
     * matching the merge-variant behaviour and the {@code git merge
     * --squash} convention). Pass {@code -Dmessage="..."} to override.
     * Fixes #160 (pre-validation) and #531 (auto-generation).
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
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (!isWorkspaceMode()) {
            if (feature == null || feature.isBlank()) {
                feature = requireParam(feature, "feature",
                        "Feature to squash-merge (without feature/ prefix)");
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
        // No pre-validation of message: per #531 the squash commit
        // message is now auto-generated from feature-branch commit
        // history when -Dmessage is missing, matching the merge variant
        // and git's own `git merge --squash` ergonomics. The #160 NPE
        // it used to protect is gone — generateFeatureMessage always
        // returns a non-blank string.
        return executeWorkspaceMode("feature/" + feature);
    }

    private WorkspaceReportSpec executeWorkspaceMode(String branchName) throws MojoException {
        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        Set<String> targets = graph.manifest().subprojects().keySet();
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
        // #535: subprojects whose local checkout is on the target branch
        // but whose workspace.yaml branch field still points at the
        // feature branch — fallout from a prior partially-failed
        // feature-finish run. They don't need a re-squash; they only
        // need workspace.yaml reconciliation, which happens below.
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
                    publish ? WsGoal.FEATURE_FINISH_SQUASH_PUBLISH
                            : WsGoal.FEATURE_FINISH_SQUASH_DRAFT,
                    "No components on `" + branchName + "` — nothing to do.\n");
        }

        // Refresh local main from origin/main before squash-merging the
        // feature branch in. Avoids shipping the feature on top of stale
        // main. In draft, preview read-only — never mutate local main
        // (#570). See ike-issues#284.
        if (publish) {
            RefreshMainSupport.refreshOrThrow(root, eligible, targetBranch, getLog());
        } else {
            RefreshMainSupport.previewRefresh(root, eligible, targetBranch, getLog());
        }

        // #531: auto-generate the squash commit message from per-subproject
        // feature-branch history when -Dmessage was not supplied. When the
        // user did supply -Dmessage, generateFeatureMessage prepends it
        // and appends the per-subproject sections below.
        String effectiveMessage = FeatureFinishSupport.generateFeatureMessage(
                root, eligible, branchName, targetBranch, message, getLog());
        getLog().info("  Commit message:");
        for (String line : effectiveMessage.split("\n")) {
            getLog().info("    " + line);
        }
        getLog().info("");

        // Merge each subproject
        int merged = 0;
        // #532: undeleted remote feature branches surface in the summary
        // rather than aborting the goal mid-flight.
        java.util.LinkedHashMap<String, String> undeletedRemote =
                new java.util.LinkedHashMap<>();
        // #544: per-subproject outcome capture so the report can
        // distinguish real-content squashes from version-only no-ops
        // and surface the resulting target-branch HEAD.
        java.util.Map<String, SquashKind> squashKind =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, String> targetSha =
                new java.util.LinkedHashMap<>();

        if (draft) {
            for (String name : eligible) {
                File dir = new File(root, name);
                // Predict the version-only no-op (read-only): a feature
                // branch that changes only pom.xml carries just the
                // version bump, which publish strips before merging —
                // leaving nothing to commit. The publish path remains
                // authoritative. Empty diff → also a no-op.
                List<String> changed = VcsOperations.changedFiles(
                        dir, targetBranch, branchName);
                boolean versionOnly = changed.stream().allMatch(
                        p -> p.equals("pom.xml") || p.endsWith("/pom.xml"));
                squashKind.put(name, versionOnly
                        ? SquashKind.VERSION_ONLY_NOOP
                        : SquashKind.CONTENT_SQUASHED);
                getLog().info("  [draft] " + name + " — would squash-merge → "
                        + targetBranch
                        + (versionOnly ? " (version-only — no commit expected)" : ""));
                merged++;
            }
        } else {
            // #667: front-load the version strip into its own pass over
            // every eligible subproject BEFORE any squash-merge runs. After
            // this pass each feature-branch POM is plain -SNAPSHOT, so a
            // crash partway through the merge pass leaves a compilable tree
            // rather than a mix of stripped and branch-qualified POMs.
            for (String name : eligible) {
                Subproject subproject = graph.manifest().subprojects().get(name);
                File dir = new File(root, name);
                getLog().info(Ansi.cyan("  ⤓ ") + name + " — strip version qualifier");
                VcsOperations.catchUp(dir, getLog());
                FeatureFinishSupport.stripBranchVersion(dir, subproject, branchName, getLog());
            }

            // #667: merge pass — checkout target + squash-merge + post-steps
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
                    VcsOperations.mergeSquash(dir, getLog(), branchName);

                    SquashKind kind;
                    if (VcsOperations.hasStagedChanges(dir)) {
                        VcsOperations.commit(dir, getLog(), effectiveMessage);
                        FeatureFinishSupport.verifyAndFixQualifiers(dir, branchName, getLog());
                        if (push) {
                            VcsOperations.pushIfRemoteExists(dir, getLog(), "origin", targetBranch);
                        }
                        kind = SquashKind.CONTENT_SQUASHED;
                    } else {
                        getLog().info("    no changes after squash (version-only branch) — skipping commit");
                        // #162: clear .git/SQUASH_MSG & .git/MERGE_MSG so a later
                        // git commit doesn't pick up the template and land an
                        // empty "Squashed commit of the following:" on main.
                        VcsOperations.resetHard(dir, getLog(), "HEAD");
                        kind = SquashKind.VERSION_ONLY_NOOP;
                    }
                    squashKind.put(name, kind);
                    try {
                        targetSha.put(name, VcsOperations.headSha(dir));
                    } catch (MojoException ignored) {
                        // Best-effort enrichment; report tolerates absence.
                    }

                    if (!keepBranch) {
                        String remoteFailReason = FeatureFinishSupport.deleteBranch(
                                dir, getLog(), branchName, keepRemoteBranch);
                        if (remoteFailReason != null) {
                            undeletedRemote.put(name, remoteFailReason);
                        }
                    }

                    VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);
                    mergedSoFar.add(name);
                    merged++;
                } catch (MojoException e) {
                    List<String> remaining =
                            new ArrayList<>(eligible.subList(i + 1, eligible.size()));
                    throw new MojoException(
                            FeatureFinishSupport.resumeGuidance(
                                    WsGoal.FEATURE_FINISH_SQUASH_PUBLISH, feature,
                                    mergedSoFar, name, remaining),
                            e);
                }
            }
        }

        // #544: include already-done subprojects' current target-branch
        // SHA so the report's table row can carry it next to the
        // reconciliation status.
        for (String name : alreadyDone) {
            File dir = new File(root, name);
            try {
                targetSha.put(name, VcsOperations.headSha(dir));
            } catch (MojoException ignored) {}
        }

        // #535: workspace.yaml branch reconciliation runs for both
        // subprojects squashed in this invocation AND those that a
        // prior partial run already moved onto the target branch but
        // never got around to recording. Without this second group,
        // the manifest stays pinned to a dead feature branch and the
        // next scaffold/init/clone fails.
        List<String> needsYamlReconcile = new ArrayList<>(eligible);
        for (String name : alreadyDone) {
            if (!needsYamlReconcile.contains(name)) {
                needsYamlReconcile.add(name);
            }
        }

        // Clean up sites (only for what we actually touched this run)
        if (merged > 0 && publish) {
            FeatureFinishSupport.cleanFeatureSites(root, eligible, branchName, getLog());
            FeatureFinishSupport.mergeWorkspaceRepo(
                    manifestPath, branchName, targetBranch, keepBranch, push, getLog());
        }

        // YAML reconciliation runs regardless of whether anything was
        // squashed THIS invocation — a re-run after a partial failure
        // may have nothing to squash but still need to clear stale
        // branch fields for the already-done subprojects.
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
        getLog().info("  Squash-merged: " + merged + " components");
        if (!alreadyDone.isEmpty()) {
            getLog().info("  Already-done from prior run: " + alreadyDone.size()
                    + " (workspace.yaml reconciled)");
        }
        if (!keepBranch) {
            getLog().info("  Branch deleted: " + branchName);
        }
        if (!undeletedRemote.isEmpty()) {
            getLog().warn("");
            getLog().warn("  " + undeletedRemote.size()
                    + " remote feature branch(es) could not be deleted "
                    + "(soft-fail per #532):");
            for (Map.Entry<String, String> entry : undeletedRemote.entrySet()) {
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
                publish ? WsGoal.FEATURE_FINISH_SQUASH_PUBLISH
                        : WsGoal.FEATURE_FINISH_SQUASH_DRAFT,
                buildSquashReport(
                        eligible, branchName, targetBranch, merged, draft,
                        keepBranch, effectiveMessage,
                        message == null || message.isBlank(),
                        undeletedRemote, alreadyDone, squashKind, targetSha));
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

    /**
     * Outcome kind for a per-subproject squash row in the report (#544).
     * {@code CONTENT_SQUASHED} is the normal path; {@code VERSION_ONLY_NOOP}
     * captures the case the goal already detected internally ("no
     * changes after squash (version-only branch)") and silently
     * collapsed into the count.
     */
    enum SquashKind {CONTENT_SQUASHED, VERSION_ONLY_NOOP}

    /**
     * Build the markdown report. When {@code messageAutoGenerated} is
     * true the report flags the message as generated and shows the
     * exact override command — covering the {@code -draft} actionable-
     * remediation principle.
     *
     * @param components          subprojects participating in the squash
     * @param branch              feature branch name
     * @param target              target branch name
     * @param merged              count of subprojects squashed (or that would be)
     * @param isDraft             whether this is a draft preview
     * @param kept                whether {@code -DkeepBranch=true}
     * @param effectiveMessage    the message that will be / was used
     * @param messageAutoGenerated whether the message was auto-built
     *                            (no user-supplied {@code -Dmessage})
     * @param undeletedRemote     subproject → git error for any remote
     *                            feature branch that the soft-fail step
     *                            could not delete (#532)
     */
    private String buildSquashReport(List<String> components, String branch,
                                      String target, int merged,
                                      boolean isDraft, boolean kept,
                                      String effectiveMessage,
                                      boolean messageAutoGenerated,
                                      java.util.Map<String, String> undeletedRemote,
                                      List<String> alreadyDone,
                                      java.util.Map<String, SquashKind> squashKind,
                                      java.util.Map<String, String> targetSha) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Branch:** `" + branch + "` → `" + target + "`  \n"
                + "**Strategy:** squash-merge");

        // #764/#763: one row per working-set member — every subproject AND
        // the workspace-root aggregator — rendered through the shared
        // WorkingSetReportTable. The aggregator's version/branch/SHA are
        // gathered the same way as a subproject (the #763 fix); previously
        // the workspace repo was squash-merged but never shown in the table.
        // The Effect column carries the per-member squash outcome (#544: real
        // content vs version-only no-op) and the resulting target-branch HEAD
        // SHA lands in the SHA column so the reviewer can audit which members
        // shipped code without running git log by hand.
        WorkingSet workingSet = resolveWorkingSet();
        List<String> eligibleNames = components;
        List<WorkingSetReportTable.Row> rows = new ArrayList<>();
        for (WorkingSet.Member member : workingSet.members()) {
            File dir = member.directory().toFile();
            String version = readPomVersion(dir);
            String memberBranch = gitBranch(dir);
            String memberSha;
            String effect;

            if (member.isAggregator()) {
                // The workspace repo is squash-merged by mergeWorkspaceRepo
                // only when at least one subproject was squashed and we're
                // publishing; the draft previews the same intent (#763 fix:
                // the aggregator now appears in the table either way).
                memberSha = isDraft ? "—" : gitShortSha(dir);
                if (merged > 0) {
                    effect = isDraft
                            ? "would squash-merge `" + branch + "` → `" + target + "`"
                            : "squash-merged `" + branch + "` → `" + target + "`";
                } else {
                    effect = "no-op (no subprojects squashed)";
                }
            } else if (eligibleNames.contains(member.name())) {
                SquashKind kind = squashKind.get(member.name());
                if (isDraft) {
                    effect = kind == SquashKind.VERSION_ONLY_NOOP
                            ? "would squash (version-only — no commit expected)"
                            : "would squash-merge `" + branch + "` → `" + target + "`";
                } else if (kind == SquashKind.VERSION_ONLY_NOOP) {
                    effect = "squashed (version-only — no commit)";
                } else {
                    effect = "squash-merged `" + branch + "` → `" + target + "`";
                }
                String sha = targetSha.getOrDefault(member.name(), "—");
                memberSha = "—".equals(sha) ? "—" : shorten(sha);
            } else if (alreadyDone.contains(member.name())) {
                // #535: NOT being squashed again; only workspace.yaml is
                // being brought into line from a prior partial run.
                effect = isDraft
                        ? "would reconcile workspace.yaml only"
                        : "reconciled workspace.yaml only (already on "
                                + target + " from a prior run)";
                String sha = targetSha.getOrDefault(member.name(), "—");
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
                + (isDraft ? "would be squash-merged" : "squash-merged")
                + (alreadyDone.isEmpty()
                        ? ""
                        : "; **" + alreadyDone.size() + "** already-done "
                                + "from a prior run (workspace.yaml "
                                + (isDraft ? "would be" : "was")
                                + " reconciled)")
                + ". Branch " + (kept ? "kept" : "deleted") + ".");

        report.section("Commit message");
        report.paragraph(messageAutoGenerated
                ? "Auto-generated from feature-branch history. Override "
                        + "with `-Dmessage=\"...\"` if you'd prefer a different "
                        + "subject."
                : "Supplied via `-Dmessage`.");
        report.codeBlock("", effectiveMessage);

        // #532: surface remote-deletion failures with a copy-pasteable
        // manual cleanup block, per the drafts-actionable-remediation
        // principle. Caller still reports merged > 0 even when this
        // section is populated — the squash succeeded; only the remote
        // branch cleanup is incomplete.
        if (!undeletedRemote.isEmpty()) {
            report.section("Remote feature branches not deleted");
            report.paragraph("The squash succeeded, but origin refused "
                    + "to delete " + undeletedRemote.size()
                    + " remote feature branch(es) — typically because "
                    + "branch protection forbids deletion. The goal "
                    + "soft-failed and continued; clean these up manually:");
            for (Map.Entry<String, String> entry : undeletedRemote.entrySet()) {
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
            report.section("To publish");
            String publishCmd = "mvn " + WsGoal.FEATURE_FINISH_SQUASH_PUBLISH.qualified()
                    + " -Dfeature=" + (feature == null ? "<name>" : feature);
            if (!messageAutoGenerated) {
                publishCmd += " -Dmessage=\"" + message.replace("\"", "\\\"") + "\"";
            }
            if (keepBranch) publishCmd += " -DkeepBranch=true";
            if (keepRemoteBranch) publishCmd += " -DkeepRemoteBranch=true";
            if (push) publishCmd += " -Dpush=true";
            report.codeBlock("bash", publishCmd);
        }
        return report.build();
    }

    /**
     * Build the bare-mode squash commit message. With a user-supplied
     * {@code -Dmessage} we prepend it; otherwise we build a default
     * from the feature-branch commit subjects (matching {@code git
     * merge --squash}'s {@code SQUASH_MSG} format). #531.
     *
     * @param dir          repository root
     * @param branchName   feature branch name
     * @param targetBranch target branch (commits are listed in
     *                     {@code targetBranch..branchName} range)
     * @param userMessage  the user-supplied {@code -Dmessage} or null/blank
     * @param log          Maven logger
     * @return non-blank commit message ready for {@code git commit -m}
     */
    static String buildBareSquashMessage(File dir, String branchName,
                                          String targetBranch,
                                          String userMessage,
                                          org.apache.maven.api.plugin.Log log) {
        StringBuilder sb = new StringBuilder();
        if (userMessage != null && !userMessage.isBlank()) {
            sb.append(userMessage).append("\n\n");
        }
        sb.append("Squash ").append(branchName).append(" into ")
                .append(targetBranch).append("\n");
        try {
            List<String> commits = VcsOperations.commitLog(
                    dir, targetBranch, branchName);
            if (!commits.isEmpty()) {
                sb.append("\n* ").append(branchName).append(" commits (")
                        .append(commits.size()).append("):\n");
                for (String line : commits) {
                    String msg = line.contains(" ")
                            ? line.substring(line.indexOf(' ') + 1) : line;
                    sb.append("  - ").append(msg).append("\n");
                }
            }
        } catch (MojoException e) {
            log.debug("Could not collect bare-mode commit log: " + e.getMessage());
        }
        return sb.toString().stripTrailing();
    }

    private WorkspaceReportSpec executeBareMode(String branchName) throws MojoException {
        boolean draft = !publish;
        // Bare mode = a working set of one (ike-issues#611).
        File dir = resolveWorkingSet().members().getFirst().directory().toFile();

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

        // #531: auto-generate the squash commit message from this repo's
        // feature-branch commit history when -Dmessage was not supplied.
        String effectiveMessage = buildBareSquashMessage(
                dir, branchName, targetBranch, message, getLog());
        getLog().info("  Commit message:");
        for (String line : effectiveMessage.split("\n")) {
            getLog().info("    " + line);
        }
        getLog().info("");

        if (draft) {
            getLog().info("  [draft] Would squash-merge → " + targetBranch);
            return new WorkspaceReportSpec(WsGoal.FEATURE_FINISH_SQUASH_DRAFT,
                    "Bare repo: would squash-merge `" + branchName + "` → `"
                            + targetBranch + "`.\n\n"
                            + "**Commit message** "
                            + (message == null || message.isBlank()
                                ? "(auto-generated; override with `-Dmessage=\"...\"`)"
                                : "(supplied via `-Dmessage`)") + ":\n\n"
                            + "```\n" + effectiveMessage + "\n```\n");
        }

        FeatureFinishSupport.stripBranchVersionBare(dir, branchName, getLog());

        VcsOperations.checkout(dir, getLog(), targetBranch);
        VcsOperations.mergeSquash(dir, getLog(), branchName);

        if (VcsOperations.hasStagedChanges(dir)) {
            VcsOperations.commit(dir, getLog(), effectiveMessage);
            FeatureFinishSupport.verifyAndFixQualifiers(dir, branchName, getLog());
            if (push) {
                VcsOperations.pushIfRemoteExists(dir, getLog(), "origin", targetBranch);
            }
        } else {
            getLog().info("  No changes after squash — skipping commit");
            // #162: see executeWorkspaceMode for rationale.
            VcsOperations.resetHard(dir, getLog(), "HEAD");
        }

        String remoteFailReason = null;
        if (!keepBranch) {
            remoteFailReason = FeatureFinishSupport.deleteBranch(
                    dir, getLog(), branchName, keepRemoteBranch);
        }

        VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);

        getLog().info("");
        if (remoteFailReason != null) {
            getLog().warn("  Remote feature branch could not be deleted "
                    + "(soft-fail per #532): " + remoteFailReason);
            getLog().warn("  Clean up manually: git push origin --delete "
                    + branchName);
        }
        getLog().info("  Done.");
        getLog().info("");
        StringBuilder body = new StringBuilder();
        body.append("Bare repo: squash-merged `").append(branchName)
                .append("` → `").append(targetBranch).append("`.\n");
        if (remoteFailReason != null) {
            body.append("\n**Remote feature branch not deleted** — `")
                    .append(remoteFailReason).append("`.\n\n")
                    .append("Clean up manually:\n\n```bash\n")
                    .append("git push origin --delete ").append(branchName)
                    .append("\n```\n");
        }
        return new WorkspaceReportSpec(WsGoal.FEATURE_FINISH_SQUASH_PUBLISH,
                body.toString());
    }
}
