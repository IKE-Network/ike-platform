package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.WorkspaceGraph;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.util.List;

/**
 * Execute a workspace checkpoint with auto-alignment.
 *
 * <p>This is the {@code -publish} counterpart of {@code ws:checkpoint}
 * (which defaults to a draft preview). The goal's flow is:
 * <ol>
 *   <li>Verify the working trees are clean (user has nothing uncommitted).</li>
 *   <li>Run {@code ws:align-publish} to apply inter-subproject version
 *       alignment.</li>
 *   <li>Commit any tracked changes the alignment step produced under a
 *       goal-owned message so that the subsequent preflight inside the
 *       superclass sees a clean tree (IKE-Network/ike-issues#537 — the
 *       preflight must not fail on changes the goal itself produced).</li>
 *   <li>Delegate to {@link WsCheckpointDraftMojo} with {@code publish = true}
 *       to do the actual tagging and manifest write.</li>
 * </ol>
 *
 * <p>Usage: {@code mvn ws:checkpoint-publish}
 *
 * @see WsCheckpointDraftMojo
 */
@Mojo(name = "checkpoint-publish", projectRequired = false, aggregator = true)
public class WsCheckpointPublishMojo extends WsCheckpointDraftMojo {

    /** Commit message used for the auto-aligned state, per #537. */
    static final String ALIGNMENT_COMMIT_MESSAGE =
            "workspace: pre-checkpoint alignment";

    /** Creates this goal instance. */
    public WsCheckpointPublishMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        publish = true;

        // Preflight up front so we know any subsequent dirtiness came
        // from our own auto-alignment step, not from work the user
        // forgot to commit. This is the first half of the #537 fix —
        // it lets us safely auto-commit alignment output without risk
        // of sweeping user-staged work into a goal-owned commit.
        requireCleanUserState();

        autoAlign();

        // Second half of #537: alignment can leave the workspace dirty
        // (modified POMs for actual alignment changes, or a self-healed
        // .gitignore from WorkspaceReport.write). Without this commit,
        // the preflight inside super.runGoal() trips on changes we just
        // produced and the goal fails itself. Commit them under a
        // goal-owned message so the checkpoint records the aligned state.
        commitAlignmentSideEffects();

        return super.runGoal();
    }

    /**
     * Run {@code ws:align-publish} as a subprocess against the
     * workspace root. Overridable from tests so the regression
     * coverage for #537 can simulate alignment side effects without
     * invoking a nested Maven build.
     *
     * @throws MojoException if the subprocess setup fails (subprocess
     *                       failures are caught and logged as warnings —
     *                       the checkpoint proceeds regardless)
     */
    protected void autoAlign() throws MojoException {
        File root = workspaceRoot();
        String mvn = WsReleaseDraftMojo.resolveMvnCommand(root);
        getLog().info("Auto-aligning workspace versions...");
        try {
            ReleaseSupport.exec(root, getLog(), mvn,
                    WsGoal.ALIGN_PUBLISH.qualified(), "-B");
        } catch (MojoException e) {
            getLog().warn("Auto-alignment completed with warnings: "
                    + e.getMessage());
        }
    }

    /**
     * Run the working-tree-clean preflight against user state before
     * we touch the workspace. Same condition the superclass enforces
     * inside its own {@code runGoal}; running it here ensures that any
     * dirtiness observed after {@link #autoAlign()} originated from the
     * goal's own work rather than from work the user forgot to commit.
     *
     * @throws MojoException if any working tree has uncommitted changes
     */
    private void requireCleanUserState() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, graph.topologicalSort()))
                .requirePassed(WsGoal.CHECKPOINT_PUBLISH);
    }

    /**
     * Commit any tracked-but-uncommitted changes left behind by
     * {@link #autoAlign()} under {@link #ALIGNMENT_COMMIT_MESSAGE}.
     * Iterates the workspace root and each subproject, no-op when the
     * tree is clean. Per-subproject failures are reported as warnings
     * so that one bad subproject doesn't abort the checkpoint (the
     * subsequent preflight will still fail loudly if anything remains
     * dirty).
     */
    private void commitAlignmentSideEffects() {
        File root = workspaceRoot();
        WorkspaceGraph graph;
        try {
            graph = loadGraph();
        } catch (MojoException e) {
            getLog().warn("Could not load graph for pre-checkpoint commit: "
                    + e.getMessage());
            return;
        }

        if (new File(root, ".git").exists()) {
            commitIfDirty(root, "workspace root");
        }
        for (String name : graph.topologicalSort()) {
            File dir = new File(root, name);
            if (new File(dir, ".git").exists()) {
                commitIfDirty(dir, name);
            }
        }
    }

    private void commitIfDirty(File dir, String label) {
        if (VcsOperations.isClean(dir)) {
            return;
        }
        try {
            VcsOperations.addAll(dir, getLog());
            VcsOperations.commit(dir, getLog(), ALIGNMENT_COMMIT_MESSAGE);
            getLog().info("  Auto-committed pre-checkpoint alignment in "
                    + label);
        } catch (MojoException e) {
            getLog().warn("Could not auto-commit pre-checkpoint alignment in "
                    + label + ": " + e.getMessage());
        }
    }
}
