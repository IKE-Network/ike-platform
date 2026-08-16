package network.ike.plugin.ws;

import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Compile-time identity for every {@code ws:*} goal in this plugin. Each
 * value wraps the bare goal name, the mojo class that implements it, a short
 * human description, its {@link WorkspaceScope}, and its declared
 * {@link GoalBehavior} (the working-tree contract — #780).
 *
 * <p>Callers that invoke ws goals from Java — for subprocess exec, for
 * the {@link WorkspaceReportSpec} a goal returns, for javadoc examples
 * that survive a rename — should reference these enum values rather
 * than string literals. {@code Find Usages} then
 * surfaces every consumer when a goal is renamed, and the
 * exhaustiveness guard in {@code WsGoalExhaustivenessTest} ensures the
 * enum stays in lockstep with {@link Mojo @Mojo} declarations.
 *
 * <p>See issue #165 (identity), #702 (scope), #780 (behavior).
 */
public enum WsGoal {

    ADD("add", WsAddMojo.class,
            "Add a subproject to the workspace.",
            GoalBehavior.COORDINATING),
    ALIGN_DRAFT("align-draft", WsAlignDraftMojo.class,
            "Preview inter-subproject version alignment.",
            GoalBehavior.DRAFT),
    ALIGN_PUBLISH("align-publish", WsAlignPublishMojo.class,
            "Apply inter-subproject version alignment.",
            GoalBehavior.COORDINATING),
    CHECK_BRANCH("check-branch", CheckBranchMojo.class,
            "Warn when a subproject branch deviates from workspace.yaml."
                    + " (No-op in a single repo — no manifest to check.)",
            GoalBehavior.READ_ONLY),
    CHECKPOINT_DRAFT("checkpoint-draft", WsCheckpointDraftMojo.class,
            "Preview a workspace checkpoint.",
            GoalBehavior.DRAFT),
    CHECKPOINT_PUBLISH("checkpoint-publish", WsCheckpointPublishMojo.class,
            "Create a workspace checkpoint (tags + yaml).",
            GoalBehavior.COORDINATING),
    CLEANUP_DRAFT("cleanup-draft", CleanupWorkspaceMojo.class,
            "Preview workspace cleanup (merged branches, stale tags).",
            GoalBehavior.DRAFT),
    CLEANUP_PUBLISH("cleanup-publish", CleanupWorkspacePublishMojo.class,
            "Execute workspace cleanup.",
            GoalBehavior.SYNC),
    COMMIT_DRAFT("commit-draft", WsCommitDraftMojo.class,
            "Preview what would be committed across the working set (read-only).",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.READ_ONLY),
    COMMIT_PUBLISH("commit-publish", WsCommitPublishMojo.class,
            "Commit uncommitted changes across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.COMMIT),
    FEATURE_ABANDON_DRAFT("feature-abandon-draft", FeatureAbandonDraftMojo.class,
            "Preview abandoning a feature branch across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.DRAFT),
    FEATURE_ABANDON_PUBLISH("feature-abandon-publish", FeatureAbandonPublishMojo.class,
            "Abandon a feature branch across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.COORDINATING),
    FEATURE_FINISH_MERGE_DRAFT("feature-finish-merge-draft", FeatureFinishMergeDraftMojo.class,
            "Preview a no-fast-forward merge of a feature branch.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.DRAFT),
    FEATURE_FINISH_MERGE_PUBLISH("feature-finish-merge-publish", FeatureFinishMergePublishMojo.class,
            "Execute a no-fast-forward merge of a feature branch.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.COORDINATING),
    FEATURE_FINISH_SQUASH_DRAFT("feature-finish-squash-draft", FeatureFinishSquashDraftMojo.class,
            "Preview a squash-merge of a feature branch.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.DRAFT),
    FEATURE_FINISH_SQUASH_PUBLISH("feature-finish-squash-publish", FeatureFinishSquashPublishMojo.class,
            "Execute a squash-merge of a feature branch.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.COORDINATING),
    FEATURE_PR_DRAFT("feature-pr-draft", FeaturePrDraftMojo.class,
            "Preview opening review pull requests for a feature branch.",
            GoalBehavior.DRAFT),
    FEATURE_PR_PUBLISH("feature-pr-publish", FeaturePrPublishMojo.class,
            "Open cross-linked review pull requests for a feature branch.",
            GoalBehavior.SYNC),
    FEATURE_START_DRAFT("feature-start-draft", FeatureStartDraftMojo.class,
            "Preview starting a feature branch across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.DRAFT),
    FEATURE_START_PUBLISH("feature-start-publish", FeatureStartPublishMojo.class,
            "Start a feature branch across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.COORDINATING),
    FEATURE_START_SIBLING_DRAFT("feature-start-sibling-draft",
            FeatureStartSiblingDraftMojo.class,
            "Preview a sibling-clone feature start.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.DRAFT),
    FEATURE_START_SIBLING_PUBLISH("feature-start-sibling-publish",
            FeatureStartSiblingPublishMojo.class,
            "Start a feature in a sibling clone beside the primary.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.COORDINATING),
    FEATURE_START_SIBLING_PUBLISH_VERIFY("feature-start-sibling-publish-verify",
            FeatureStartSiblingPublishVerifyMojo.class,
            "Start a feature in a sibling clone, then build the reactor to verify it.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.COORDINATING),
    FEATURE_TRACK_DRAFT("feature-track-draft", FeatureTrackDraftMojo.class,
            "Preview adopting an existing feature branch in selected subprojects.",
            GoalBehavior.DRAFT),
    FEATURE_TRACK_PUBLISH("feature-track-publish", FeatureTrackPublishMojo.class,
            "Adopt an existing feature branch in selected subprojects.",
            GoalBehavior.COORDINATING),
    GRAPH("graph", GraphWorkspaceMojo.class,
            "Emit a Mermaid dependency graph for the workspace.",
            GoalBehavior.READ_ONLY),
    HELP("help", WsHelpMojo.class,
            "List ws:* goals discovered from the plugin descriptor.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.READ_ONLY),
    LINT("lint", WsLintMojo.class,
            "Surface preflight conditions as a hygiene gate (read-only).",
            GoalBehavior.READ_ONLY),
    OVERVIEW("overview", OverviewWorkspaceMojo.class,
            "Workspace overview: manifest, graph, status, cascade.",
            GoalBehavior.READ_ONLY),
    POST_RELEASE("post-release", WsPostReleaseMojo.class,
            "Post-release bump of SNAPSHOT versions.",
            GoalBehavior.COORDINATING),
    PULL("pull", PullWorkspaceMojo.class,
            "Pull the working set — a single repo, or every subproject.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.SYNC),
    PUSH("push", PushMojo.class,
            "Push the working set — a single repo, or every subproject.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.SYNC),
    RECONCILE_BRANCHES_DRAFT("reconcile-branches-draft",
            WsReconcileBranchesDraftMojo.class,
            "Preview reconciliation of workspace.yaml branch fields with on-disk state.",
            GoalBehavior.DRAFT),
    RECONCILE_BRANCHES_PUBLISH("reconcile-branches-publish",
            WsReconcileBranchesPublishMojo.class,
            "Apply branch reconciliation across the workspace.",
            GoalBehavior.COORDINATING),
    RECORD_RELEASE_DRAFT("record-release-draft", WsRecordReleaseDraftMojo.class,
            "Preview recording a member's release (tag-aligned pin + releases/ record).",
            GoalBehavior.DRAFT),
    RECORD_RELEASE_PUBLISH("record-release-publish", WsRecordReleasePublishMojo.class,
            "Record a member's release: pin it tag-aligned and append the releases/ record row.",
            GoalBehavior.COORDINATING),
    REFRESH_MAIN("refresh-main", WsRefreshMainMojo.class,
            "Refresh local main from origin/main across the workspace.",
            GoalBehavior.SYNC),
    RELEASE_DRAFT("release-draft", WsReleaseDraftMojo.class,
            "Preview a release of the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.DRAFT),
    RELEASE_NOTES("release-notes", WsReleaseNotesMojo.class,
            "Generate release notes from a milestone.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.READ_ONLY),
    RELEASE_PUBLISH("release-publish", WsReleasePublishMojo.class,
            "Execute a release of the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.NO_PREFLIGHT_COMMIT),
    RELEASE_ROLLBACK_DRAFT("release-rollback-draft",
            WsReleaseRollbackDraftMojo.class,
            "Preview rolling back a failed release cycle's unpushed commits.",
            GoalBehavior.DRAFT),
    RELEASE_ROLLBACK_PUBLISH("release-rollback-publish",
            WsReleaseRollbackPublishMojo.class,
            "Roll back a failed release cycle's unpushed commits and tags.",
            GoalBehavior.NO_PREFLIGHT_COMMIT),
    RELEASE_STATUS("release-status", WsReleaseStatusMojo.class,
            "Diagnose state of any in-flight workspace release.",
            GoalBehavior.READ_ONLY),
    REMOVE("remove", WsRemoveMojo.class,
            "Remove a subproject from the workspace.",
            GoalBehavior.COORDINATING),
    REPORT("report", ReportMojo.class,
            "List the ws:* goal reports for the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.READ_ONLY),
    SCAFFOLD_DRAFT("scaffold-draft", WsScaffoldDraftMojo.class,
            "Report scaffold + foundation drift across the working set (#350).",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.DRAFT),
    SCAFFOLD_INIT("scaffold-init", WsScaffoldInitMojo.class,
            "Bootstrap a new workspace, or clone declared-but-missing subprojects."
                    + " Idempotent (#393).",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.SYNC),
    SCAFFOLD_PUBLISH("scaffold-publish", WsScaffoldPublishMojo.class,
            "Apply scaffold + foundation drift across the working set (#350).",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.COORDINATING),
    STIGNORE("stignore", StignoreWorkspaceMojo.class,
            "Generate Syncthing ignore files from workspace.yaml.",
            GoalBehavior.COORDINATING),
    SWITCH_DRAFT("switch-draft", WsSwitchDraftMojo.class,
            "Preview switching subprojects to a coordinated branch.",
            GoalBehavior.DRAFT),
    SWITCH_PUBLISH("switch-publish", WsSwitchPublishMojo.class,
            "Switch subprojects to a coordinated branch.",
            GoalBehavior.NO_PREFLIGHT_COMMIT),
    SYNC("sync", WsSyncMojo.class,
            "Pull then push across the working set (the daily sync op).",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.SYNC),
    UPDATE_FEATURE_DRAFT("update-feature-draft", UpdateFeatureDraftMojo.class,
            "Preview updating a feature branch by merging main in.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.DRAFT),
    UPDATE_FEATURE_PUBLISH("update-feature-publish", UpdateFeaturePublishMojo.class,
            "Update a feature branch by merging main in.",
            WorkspaceScope.BARE_AND_WORKSPACE, GoalBehavior.COORDINATING),
    VERIFY_CONVERGENCE("verify-convergence", VerifyConvergenceMojo.class,
            "Verify transitive dependency convergence across subprojects.",
            GoalBehavior.READ_ONLY);

    /** Shared {@code ws:} prefix for all goals in this plugin. */
    public static final String PLUGIN_PREFIX = "ws";

    private final String goalName;
    private final Class<? extends org.apache.maven.api.plugin.Mojo> mojoClass;
    private final String description;
    private final WorkspaceScope scope;
    private final GoalBehavior behavior;

    /**
     * Convenience constructor for the common workspace-only goal. New goals
     * default to {@link WorkspaceScope#WORKSPACE_ONLY}: a goal must opt in to
     * advertising single-repo support so a forgotten scope never over-claims a
     * bare mode the mojo does not implement (#702). Every goal must declare its
     * {@link GoalBehavior} (#780) — there is deliberately no default for it.
     */
    WsGoal(String goalName,
           Class<? extends org.apache.maven.api.plugin.Mojo> mojoClass,
           String description,
           GoalBehavior behavior) {
        this(goalName, mojoClass, description, WorkspaceScope.WORKSPACE_ONLY,
                behavior);
    }

    WsGoal(String goalName,
           Class<? extends org.apache.maven.api.plugin.Mojo> mojoClass,
           String description,
           WorkspaceScope scope,
           GoalBehavior behavior) {
        this.goalName = goalName;
        this.mojoClass = mojoClass;
        this.description = description;
        this.scope = scope;
        this.behavior = behavior;
    }

    /** The bare goal name as it appears in {@code @Mojo(name = ...)}. */
    public String goalName() {
        return goalName;
    }

    /** The fully-qualified goal invocation, e.g. {@code "ws:align-publish"}. */
    public String qualified() {
        return PLUGIN_PREFIX + ":" + goalName;
    }

    /** The mojo class that implements this goal. */
    public Class<? extends org.apache.maven.api.plugin.Mojo> mojoClass() {
        return mojoClass;
    }

    /** One-line human description of what this goal does. */
    public String description() {
        return description;
    }

    /**
     * Whether this goal runs on a single repository (a working set of one)
     * as well as a workspace, or requires a {@code workspace.yaml} (#702).
     *
     * @return the goal's workspace scope
     */
    public WorkspaceScope scope() {
        return scope;
    }

    /**
     * This goal's declared working-tree contract — how it treats an uncommitted
     * tree on entry ({@link TreePreflight}) and how it commits what it authors
     * ({@link AuthoredCommit}). The locked taxonomy of #780; runtime enforcement
     * is being brought up to it goal-by-goal.
     *
     * @return the goal's behavior
     */
    public GoalBehavior behavior() {
        return behavior;
    }
}
