package network.ike.plugin.ws;

/**
 * How a goal commits the tracked changes it itself authors — one half of a
 * goal's declared {@link GoalBehavior} (IKE-Network/ike-issues#780).
 *
 * <p>The cardinal rule across every value: a goal never runs
 * {@code git add -A}. It commits only the paths it authored, each gated on
 * having been unmodified before the goal ran (via {@link GoalAuthoredChanges}),
 * so a caller's concurrent WIP, a {@code -DstagedOnly} subset, or work
 * syncing in from the other machine over Syncthing is never swept in
 * (cf. IKE-Network/ike-issues#358, #774).
 */
public enum AuthoredCommit {

    /**
     * Auto-commit, in isolation, exactly the paths this goal authored that
     * were unmodified before it ran. Uses a standard {@code <type>: <summary>}
     * message with a {@code Refs:} trailer.
     */
    IN_ISOLATION,

    /**
     * The goal writes its paths but leaves the commit to the top-level goal
     * that invoked it (e.g. {@code align} run as a sub-step of {@code scaffold}
     * or {@code feature-start}). A standalone invocation of the same goal
     * commits {@link #IN_ISOLATION}.
     */
    DEFER_TO_CALLER,

    /**
     * Committing is the goal's whole purpose ({@code ws:commit-publish}); it
     * honors {@code -DstagedOnly} and consumes a working tree with uncommitted
     * changes.
     */
    IS_THE_COMMIT,

    /**
     * The goal authors no tracked commit — read-only goals, and VCS-state
     * goals (push/pull/sync) that move refs but write no tracked files.
     */
    NONE
}
