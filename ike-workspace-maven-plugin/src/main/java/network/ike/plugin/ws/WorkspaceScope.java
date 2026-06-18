package network.ike.plugin.ws;

/**
 * Whether a {@code ws:*} goal runs on a single repository (a "working set
 * of one", no {@code workspace.yaml}) or requires a workspace
 * (IKE-Network/ike-issues#702).
 *
 * <p>Carried as compile-time metadata on each {@link WsGoal} so {@code
 * ws:help} and the generated cheatsheets can state each goal's
 * applicability accurately instead of leaving it to hand-maintained prose.
 * The actual runtime behavior is decided in {@code AbstractWorkspaceMojo}
 * ({@code resolveWorkingSet()} / {@code resolveManifest()}); this enum is
 * the declared, inspectable counterpart of that behavior.
 *
 * <p>New goals default to {@link #WORKSPACE_ONLY} (see {@link WsGoal}'s
 * three-argument constructor): under-claiming bare support is safer than a
 * goal wrongly advertising a single-repo mode it does not implement.
 */
public enum WorkspaceScope {

    /**
     * Runs meaningfully on a single repository (working set of one) as
     * well as inside a workspace — e.g. {@code ws:commit}, {@code ws:push},
     * {@code ws:pull}, {@code ws:scaffold}, {@code ws:release},
     * {@code ws:feature-*}.
     */
    BARE_AND_WORKSPACE("single repo or workspace"),

    /**
     * Requires a {@code workspace.yaml}; not meaningful in a lone
     * repository. Either errors without a manifest (e.g. {@code ws:align},
     * {@code ws:graph}, {@code ws:overview}) or is a deliberate no-op there
     * (e.g. {@code ws:check-branch} has no manifest-declared branch to
     * enforce).
     */
    WORKSPACE_ONLY("workspace only");

    private final String label;

    WorkspaceScope(String label) {
        this.label = label;
    }

    /**
     * Short human label for help text and cheatsheets, e.g.
     * {@code "single repo or workspace"}.
     *
     * @return the display label
     */
    public String label() {
        return label;
    }

    /**
     * @return {@code true} when the goal also runs on a single bare
     *         repository (a working set of one)
     */
    public boolean runsBare() {
        return this == BARE_AND_WORKSPACE;
    }
}
