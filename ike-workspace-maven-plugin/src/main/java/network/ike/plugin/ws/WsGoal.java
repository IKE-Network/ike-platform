package network.ike.plugin.ws;

import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Compile-time identity for every {@code ws:*} goal in this plugin. Each
 * value wraps the bare goal name, the mojo class that implements it, and
 * a short human description.
 *
 * <p>Callers that invoke ws goals from Java — for subprocess exec, for
 * the {@link WorkspaceReportSpec} a goal returns, for javadoc examples
 * that survive a rename — should reference these enum values rather
 * than string literals. {@code Find Usages} then
 * surfaces every consumer when a goal is renamed, and the
 * exhaustiveness guard in {@code WsGoalExhaustivenessTest} ensures the
 * enum stays in lockstep with {@link Mojo @Mojo} declarations.
 *
 * <p>See issue #165.
 */
public enum WsGoal {

    ADD("add", WsAddMojo.class,
            "Add a subproject to the workspace."),
    ALIGN_DRAFT("align-draft", WsAlignDraftMojo.class,
            "Preview inter-subproject version alignment."),
    ALIGN_PUBLISH("align-publish", WsAlignPublishMojo.class,
            "Apply inter-subproject version alignment."),
    CHECK_BRANCH("check-branch", CheckBranchMojo.class,
            "Warn when a subproject branch deviates from workspace.yaml."
                    + " (No-op in a single repo — no manifest to check.)"),
    CHECKPOINT_DRAFT("checkpoint-draft", WsCheckpointDraftMojo.class,
            "Preview a workspace checkpoint."),
    CHECKPOINT_PUBLISH("checkpoint-publish", WsCheckpointPublishMojo.class,
            "Create a workspace checkpoint (tags + yaml)."),
    CLEANUP_DRAFT("cleanup-draft", CleanupWorkspaceMojo.class,
            "Preview workspace cleanup (merged branches, stale tags)."),
    CLEANUP_PUBLISH("cleanup-publish", CleanupWorkspacePublishMojo.class,
            "Execute workspace cleanup."),
    COMMIT_DRAFT("commit-draft", WsCommitDraftMojo.class,
            "Preview what would be committed across the working set (read-only).",
            WorkspaceScope.BARE_AND_WORKSPACE),
    COMMIT_PUBLISH("commit-publish", WsCommitPublishMojo.class,
            "Commit uncommitted changes across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    FEATURE_ABANDON_DRAFT("feature-abandon-draft", FeatureAbandonDraftMojo.class,
            "Preview abandoning a feature branch across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    FEATURE_ABANDON_PUBLISH("feature-abandon-publish", FeatureAbandonPublishMojo.class,
            "Abandon a feature branch across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    FEATURE_FINISH_MERGE_DRAFT("feature-finish-merge-draft", FeatureFinishMergeDraftMojo.class,
            "Preview a no-fast-forward merge of a feature branch.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    FEATURE_FINISH_MERGE_PUBLISH("feature-finish-merge-publish", FeatureFinishMergePublishMojo.class,
            "Execute a no-fast-forward merge of a feature branch.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    FEATURE_FINISH_SQUASH_DRAFT("feature-finish-squash-draft", FeatureFinishSquashDraftMojo.class,
            "Preview a squash-merge of a feature branch.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    FEATURE_FINISH_SQUASH_PUBLISH("feature-finish-squash-publish", FeatureFinishSquashPublishMojo.class,
            "Execute a squash-merge of a feature branch.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    FEATURE_START_DRAFT("feature-start-draft", FeatureStartDraftMojo.class,
            "Preview starting a feature branch across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    FEATURE_START_PUBLISH("feature-start-publish", FeatureStartPublishMojo.class,
            "Start a feature branch across the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    GRAPH("graph", GraphWorkspaceMojo.class,
            "Emit a Mermaid dependency graph for the workspace."),
    HELP("help", WsHelpMojo.class,
            "List ws:* goals discovered from the plugin descriptor.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    LINT("lint", WsLintMojo.class,
            "Surface preflight conditions as a hygiene gate (read-only)."),
    OVERVIEW("overview", OverviewWorkspaceMojo.class,
            "Workspace overview: manifest, graph, status, cascade."),
    POST_RELEASE("post-release", WsPostReleaseMojo.class,
            "Post-release bump of SNAPSHOT versions."),
    PULL("pull", PullWorkspaceMojo.class,
            "Pull the working set — a single repo, or every subproject.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    PUSH("push", PushMojo.class,
            "Push the working set — a single repo, or every subproject.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    RECONCILE_BRANCHES_DRAFT("reconcile-branches-draft",
            WsReconcileBranchesDraftMojo.class,
            "Preview reconciliation of workspace.yaml branch fields with on-disk state."),
    RECONCILE_BRANCHES_PUBLISH("reconcile-branches-publish",
            WsReconcileBranchesPublishMojo.class,
            "Apply branch reconciliation across the workspace."),
    REFRESH_MAIN("refresh-main", WsRefreshMainMojo.class,
            "Refresh local main from origin/main across the workspace."),
    RELEASE_DRAFT("release-draft", WsReleaseDraftMojo.class,
            "Preview a release of the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    RELEASE_NOTES("release-notes", WsReleaseNotesMojo.class,
            "Generate release notes from a milestone.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    RELEASE_PUBLISH("release-publish", WsReleasePublishMojo.class,
            "Execute a release of the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    RELEASE_STATUS("release-status", WsReleaseStatusMojo.class,
            "Diagnose state of any in-flight workspace release."),
    REMOVE("remove", WsRemoveMojo.class,
            "Remove a subproject from the workspace."),
    REPORT("report", ReportMojo.class,
            "List the ws:* goal reports for the working set.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    SCAFFOLD_DRAFT("scaffold-draft", WsScaffoldDraftMojo.class,
            "Report scaffold + foundation drift across the working set (#350).",
            WorkspaceScope.BARE_AND_WORKSPACE),
    SCAFFOLD_INIT("scaffold-init", WsScaffoldInitMojo.class,
            "Bootstrap a new workspace, or clone declared-but-missing subprojects."
                    + " Idempotent (#393).",
            WorkspaceScope.BARE_AND_WORKSPACE),
    SCAFFOLD_PUBLISH("scaffold-publish", WsScaffoldPublishMojo.class,
            "Apply scaffold + foundation drift across the working set (#350).",
            WorkspaceScope.BARE_AND_WORKSPACE),
    SIBLING_CREATE("sibling-create", SiblingCreateMojo.class,
            "Create a sibling clone on a feature branch.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    STIGNORE("stignore", StignoreWorkspaceMojo.class,
            "Generate Syncthing ignore files from workspace.yaml."),
    SWITCH_DRAFT("switch-draft", WsSwitchDraftMojo.class,
            "Preview switching subprojects to a coordinated branch."),
    SWITCH_PUBLISH("switch-publish", WsSwitchPublishMojo.class,
            "Switch subprojects to a coordinated branch."),
    SYNC("sync", WsSyncMojo.class,
            "Pull then push across the working set (the daily sync op).",
            WorkspaceScope.BARE_AND_WORKSPACE),
    UPDATE_FEATURE_DRAFT("update-feature-draft", UpdateFeatureDraftMojo.class,
            "Preview updating a feature branch by merging main in.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    UPDATE_FEATURE_PUBLISH("update-feature-publish", UpdateFeaturePublishMojo.class,
            "Update a feature branch by merging main in.",
            WorkspaceScope.BARE_AND_WORKSPACE),
    VERIFY_CONVERGENCE("verify-convergence", VerifyConvergenceMojo.class,
            "Verify transitive dependency convergence across subprojects.");

    /** Shared {@code ws:} prefix for all goals in this plugin. */
    public static final String PLUGIN_PREFIX = "ws";

    private final String goalName;
    private final Class<? extends org.apache.maven.api.plugin.Mojo> mojoClass;
    private final String description;
    private final WorkspaceScope scope;

    /**
     * Convenience constructor for the common workspace-only goal. New
     * goals default to {@link WorkspaceScope#WORKSPACE_ONLY}: a goal must
     * opt in to advertising single-repo support so a forgotten scope never
     * over-claims a bare mode the mojo does not implement (#702).
     */
    WsGoal(String goalName,
           Class<? extends org.apache.maven.api.plugin.Mojo> mojoClass,
           String description) {
        this(goalName, mojoClass, description, WorkspaceScope.WORKSPACE_ONLY);
    }

    WsGoal(String goalName,
           Class<? extends org.apache.maven.api.plugin.Mojo> mojoClass,
           String description,
           WorkspaceScope scope) {
        this.goalName = goalName;
        this.mojoClass = mojoClass;
        this.description = description;
        this.scope = scope;
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
}
