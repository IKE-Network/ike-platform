package network.ike.plugin.ws;

import org.apache.maven.api.plugin.annotations.Mojo;

import java.util.Optional;

/**
 * Compile-time identity for every {@code ws:*} goal in this plugin. Each
 * value wraps the bare goal name, the mojo class that implements it, and
 * a short human description. Draft/publish siblings expose each other
 * through {@link #pair()}.
 *
 * <p>Callers that invoke ws goals from Java — for subprocess exec, for
 * {@code writeReport} / {@code startReport} / {@code finishReport}, for
 * javadoc examples that survive a rename — should reference these
 * enum values rather than string literals. {@code Find Usages} then
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
            "Warn when a subproject branch deviates from workspace.yaml."),
    CHECKPOINT_DRAFT("checkpoint-draft", WsCheckpointDraftMojo.class,
            "Preview a workspace checkpoint."),
    CHECKPOINT_PUBLISH("checkpoint-publish", WsCheckpointPublishMojo.class,
            "Create a workspace checkpoint (tags + yaml)."),
    CLEANUP_DRAFT("cleanup-draft", CleanupWorkspaceMojo.class,
            "Preview workspace cleanup (merged branches, stale tags)."),
    CLEANUP_PUBLISH("cleanup-publish", CleanupWorkspacePublishMojo.class,
            "Execute workspace cleanup."),
    COMMIT("commit", CommitMojo.class,
            "Commit uncommitted changes across subprojects."),
    CREATE("create", WsCreateMojo.class,
            "Create a new workspace from scratch."),
    FEATURE_ABANDON_DRAFT("feature-abandon-draft", FeatureAbandonDraftMojo.class,
            "Preview abandoning a feature branch across subprojects."),
    FEATURE_ABANDON_PUBLISH("feature-abandon-publish", FeatureAbandonPublishMojo.class,
            "Abandon a feature branch across subprojects."),
    FEATURE_FINISH_MERGE_DRAFT("feature-finish-merge-draft", FeatureFinishMergeDraftMojo.class,
            "Preview a no-fast-forward merge of a feature branch."),
    FEATURE_FINISH_MERGE_PUBLISH("feature-finish-merge-publish", FeatureFinishMergePublishMojo.class,
            "Execute a no-fast-forward merge of a feature branch."),
    FEATURE_FINISH_SQUASH_DRAFT("feature-finish-squash-draft", FeatureFinishSquashDraftMojo.class,
            "Preview a squash-merge of a feature branch."),
    FEATURE_FINISH_SQUASH_PUBLISH("feature-finish-squash-publish", FeatureFinishSquashPublishMojo.class,
            "Execute a squash-merge of a feature branch."),
    FEATURE_START_DRAFT("feature-start-draft", FeatureStartDraftMojo.class,
            "Preview starting a feature branch across subprojects."),
    FEATURE_START_PUBLISH("feature-start-publish", FeatureStartPublishMojo.class,
            "Start a feature branch across subprojects."),
    FIX("fix", WsFixMojo.class,
            "Sync denormalized fields in workspace.yaml."),
    GRAPH("graph", GraphWorkspaceMojo.class,
            "Emit a Mermaid dependency graph for the workspace."),
    HELP("help", WsHelpMojo.class,
            "List ws:* goals discovered from the plugin descriptor."),
    INIT("init", InitWorkspaceMojo.class,
            "Initialize a workspace: clone subprojects per workspace.yaml."),
    OVERVIEW("overview", OverviewWorkspaceMojo.class,
            "Workspace overview: manifest, graph, status, cascade."),
    POST_RELEASE("post-release", WsPostReleaseMojo.class,
            "Post-release bump of SNAPSHOT versions."),
    PULL("pull", PullWorkspaceMojo.class,
            "Pull all subprojects."),
    PUSH("push", PushMojo.class,
            "Push all subprojects."),
    RELEASE_DRAFT("release-draft", WsReleaseDraftMojo.class,
            "Preview a workspace release."),
    RELEASE_NOTES("release-notes", WsReleaseNotesMojo.class,
            "Generate release notes from a milestone."),
    RELEASE_PUBLISH("release-publish", WsReleasePublishMojo.class,
            "Execute a workspace release."),
    RELEASE_STATUS("release-status", WsReleaseStatusMojo.class,
            "Diagnose state of any in-flight workspace release."),
    REMOVE("remove", WsRemoveMojo.class,
            "Remove a subproject from the workspace."),
    REPORT("report", ReportMojo.class,
            "Aggregate ws:* goal reports into a single document."),
    SCAFFOLD_UPGRADE_DRAFT("scaffold-upgrade-draft", WsScaffoldUpgradeDraftMojo.class,
            "Preview workspace scaffold convention upgrades."),
    SCAFFOLD_UPGRADE_PUBLISH("scaffold-upgrade-publish", WsScaffoldUpgradePublishMojo.class,
            "Apply workspace scaffold convention upgrades."),
    SET_PARENT_DRAFT("set-parent-draft", WsSetParentDraftMojo.class,
            "Preview a parent-POM version bump across subprojects."),
    SET_PARENT_PUBLISH("set-parent-publish", WsSetParentPublishMojo.class,
            "Apply a parent-POM version bump across subprojects."),
    STIGNORE("stignore", StignoreWorkspaceMojo.class,
            "Generate Syncthing ignore files from workspace.yaml."),
    SWITCH_DRAFT("switch-draft", WsSwitchDraftMojo.class,
            "Preview switching subprojects to a coordinated branch."),
    SWITCH_PUBLISH("switch-publish", WsSwitchPublishMojo.class,
            "Switch subprojects to a coordinated branch."),
    UPDATE_FEATURE_DRAFT("update-feature-draft", UpdateFeatureDraftMojo.class,
            "Preview rebasing a feature branch onto main."),
    UPDATE_FEATURE_PUBLISH("update-feature-publish", UpdateFeaturePublishMojo.class,
            "Rebase a feature branch onto main."),
    VERIFY("verify", VerifyWorkspaceMojo.class,
            "Verify workspace invariants (parent skew, qualifier drift)."),
    VERIFY_CONVERGENCE("verify-convergence", VerifyConvergenceMojo.class,
            "Verify transitive dependency convergence across subprojects."),
    VERSIONS_UPGRADE_DRAFT("versions-upgrade-draft",
            WsVersionsUpgradeDraftMojo.class,
            "Preview version upgrades across the workspace against the configured ruleset."),
    VERSIONS_UPGRADE_PUBLISH("versions-upgrade-publish",
            WsVersionsUpgradePublishMojo.class,
            "Apply the workspace version-upgrade plan across all subprojects.");

    /** Shared {@code ws:} prefix for all goals in this plugin. */
    public static final String PLUGIN_PREFIX = "ws";

    private static final String DRAFT_SUFFIX = "-draft";
    private static final String PUBLISH_SUFFIX = "-publish";

    private final String goalName;
    private final Class<? extends org.apache.maven.api.plugin.Mojo> mojoClass;
    private final String description;

    WsGoal(String goalName,
           Class<? extends org.apache.maven.api.plugin.Mojo> mojoClass,
           String description) {
        this.goalName = goalName;
        this.mojoClass = mojoClass;
        this.description = description;
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

    /** True if this is the {@code -draft} counterpart of a draft/publish pair. */
    public boolean isDraft() {
        return goalName.endsWith(DRAFT_SUFFIX);
    }

    /** True if this is the {@code -publish} counterpart of a draft/publish pair. */
    public boolean isPublish() {
        return goalName.endsWith(PUBLISH_SUFFIX);
    }

    /**
     * The paired draft/publish sibling, if this goal belongs to a pair.
     *
     * @return the sibling goal, or empty if this goal is a singleton
     */
    public Optional<WsGoal> pair() {
        if (isDraft()) {
            return byName(stripSuffix(goalName, DRAFT_SUFFIX) + PUBLISH_SUFFIX);
        }
        if (isPublish()) {
            return byName(stripSuffix(goalName, PUBLISH_SUFFIX) + DRAFT_SUFFIX);
        }
        return Optional.empty();
    }

    /**
     * Look up a goal by its bare name (e.g. {@code "align-publish"}).
     *
     * @param goalName the bare goal name, without the {@code ws:} prefix
     * @return the matching goal, or empty if none
     */
    public static Optional<WsGoal> byName(String goalName) {
        for (WsGoal g : values()) {
            if (g.goalName.equals(goalName)) return Optional.of(g);
        }
        return Optional.empty();
    }

    private static String stripSuffix(String s, String suffix) {
        return s.substring(0, s.length() - suffix.length());
    }
}
