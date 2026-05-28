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

        if (eligible.isEmpty()) {
            getLog().info("  No components on " + branchName + " — nothing to do.");
            return new WorkspaceReportSpec(
                    publish ? WsGoal.FEATURE_FINISH_SQUASH_PUBLISH
                            : WsGoal.FEATURE_FINISH_SQUASH_DRAFT,
                    "No components on `" + branchName + "` — nothing to do.\n");
        }

        // Refresh local main from origin/main before squash-merging the
        // feature branch in. Avoids shipping the feature on top of stale
        // main. See ike-issues#284.
        RefreshMainSupport.refreshOrThrow(root, eligible, targetBranch, getLog());

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
                VcsOperations.commit(dir, getLog(), effectiveMessage);
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
                    root, eligible, branchName, targetBranch,
                    getPrompter(), getLog());
        }

        getLog().info("");
        getLog().info("  Squash-merged: " + merged + " components");
        if (!keepBranch) {
            getLog().info("  Branch deleted: " + branchName);
        }
        getLog().info("");

        // Structured markdown report
        return new WorkspaceReportSpec(
                publish ? WsGoal.FEATURE_FINISH_SQUASH_PUBLISH
                        : WsGoal.FEATURE_FINISH_SQUASH_DRAFT,
                buildSquashReport(
                        eligible, branchName, targetBranch, merged, draft,
                        keepBranch, effectiveMessage,
                        message == null || message.isBlank()));
    }

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
     */
    private String buildSquashReport(List<String> components, String branch,
                                      String target, int merged,
                                      boolean isDraft, boolean kept,
                                      String effectiveMessage,
                                      boolean messageAutoGenerated) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Branch:** `" + branch + "` → `" + target + "`  \n"
                + "**Strategy:** squash-merge");

        List<String[]> rows = new ArrayList<>();
        for (String name : components) {
            rows.add(new String[]{name, isDraft ? "would squash" : "squashed"});
        }
        report.table(List.of("Subproject", "Status"), rows);

        report.paragraph("**" + merged + " subproject(s)** "
                + (isDraft ? "would be squash-merged" : "squash-merged")
                + ". Branch " + (kept ? "kept" : "deleted") + ".");

        report.section("Commit message");
        report.paragraph(messageAutoGenerated
                ? "Auto-generated from feature-branch history. Override "
                        + "with `-Dmessage=\"...\"` if you'd prefer a different "
                        + "subject."
                : "Supplied via `-Dmessage`.");
        report.codeBlock("", effectiveMessage);

        if (isDraft) {
            report.section("To publish");
            String publishCmd = "mvn " + WsGoal.FEATURE_FINISH_SQUASH_PUBLISH.qualified()
                    + " -Dfeature=" + (feature == null ? "<name>" : feature);
            if (!messageAutoGenerated) {
                publishCmd += " -Dmessage=\"" + message.replace("\"", "\\\"") + "\"";
            }
            if (keepBranch) publishCmd += " -DkeepBranch=true";
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

        if (!keepBranch) {
            FeatureFinishSupport.deleteBranch(dir, getLog(), branchName);
        }

        VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);

        getLog().info("");
        getLog().info("  Done.");
        getLog().info("");
        return new WorkspaceReportSpec(WsGoal.FEATURE_FINISH_SQUASH_PUBLISH,
                "Bare repo: squash-merged `" + branchName + "` → `"
                        + targetBranch + "`.\n");
    }
}
