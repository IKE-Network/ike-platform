package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.util.List;

/**
 * Preview what {@code ws:commit-publish} would commit across the
 * workspace — read-only.
 *
 * <p>The {@code -draft} half of the commit pair. Scans every repository
 * (workspace root plus each cloned subproject) and reports, per repo,
 * the uncommitted work that {@link WsCommitPublishMojo ws:commit-publish}
 * would stage and commit: tracked-modified file counts plus
 * untracked-not-ignored paths. Repos with nothing to commit are
 * reported clean.
 *
 * <p>Read-only: no VCS bridge catch-up, no {@code git add}, no
 * {@code git commit}, no push, and no {@code -Dmessage} required. The
 * {@code .mvn/jvm.config} preflight lint still runs as a hard gate — a
 * hash-comment'd {@code jvm.config} would block the real commit, so the
 * draft surfaces it the same way rather than previewing a commit that
 * could not happen (ike-issues#217).
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ws:commit-draft
 * }</pre>
 *
 * @see WsCommitPublishMojo
 */
@Mojo(name = "commit-draft", projectRequired = false, aggregator = true)
public class WsCommitDraftMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public WsCommitDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (isWorkspaceMode()) {
            return previewWorkspace();
        }
        return previewSingleRepo(new File(System.getProperty("user.dir")));
    }

    private WorkspaceReportSpec previewWorkspace() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        List<String> sorted = graph.topologicalSort();

        // Same pre-commit hygiene gate as ws:commit-publish — a
        // #-comment'd .mvn/jvm.config would block the real commit, so
        // the draft fails the same way rather than previewing a commit
        // that cannot happen (ike-issues#217).
        network.ike.plugin.ws.preflight.Preflight.of(
                java.util.List.of(network.ike.plugin.ws.preflight
                        .PreflightCondition.JVM_CONFIG_NO_HASH_COMMENTS),
                network.ike.plugin.ws.preflight.PreflightContext.of(
                        root, graph, sorted))
                .requirePassed(WsGoal.COMMIT_DRAFT);

        getLog().info("");
        getLog().info(header("Commit (draft)"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        int pending = 0;
        int clean = 0;

        if (new File(root, ".git").exists()) {
            if (previewOne(root, "workspace root")) {
                pending++;
            } else {
                clean++;
            }
        }
        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                getLog().debug(name + " — not cloned, skipping");
                continue;
            }
            if (previewOne(dir, name)) {
                pending++;
            } else {
                clean++;
            }
        }

        String summary = pending + " repo(s) with uncommitted work, "
                + clean + " clean";
        getLog().info("");
        getLog().info("  " + summary);
        if (pending > 0) {
            getLog().info("  Run ws:commit-publish -Dmessage=\"...\" to commit.");
        }
        getLog().info("");

        return new WorkspaceReportSpec(WsGoal.COMMIT_DRAFT, summary + "\n");
    }

    private WorkspaceReportSpec previewSingleRepo(File dir) {
        getLog().info("");
        getLog().info("IKE VCS Bridge — Commit (draft)");
        getLog().info("══════════════════════════════════════════════════════════════");
        boolean pending = previewOne(dir, dir.getName());
        String summary = pending
                ? "Uncommitted work present — run ws:commit-publish to commit."
                : "Clean — nothing to commit.";
        getLog().info("");
        getLog().info("  " + summary);
        getLog().info("");
        return new WorkspaceReportSpec(WsGoal.COMMIT_DRAFT, summary + "\n");
    }

    /**
     * Report one repository's pending work without mutating it.
     *
     * @param dir   the repository directory
     * @param label the label shown in the output line
     * @return {@code true} if the repo has uncommitted work
     */
    private boolean previewOne(File dir, String label) {
        int modCount = VcsOperations.modifiedTrackedCount(dir);
        List<String> newFiles = VcsOperations.untrackedFiles(dir);

        if (modCount == 0 && newFiles.isEmpty()) {
            if (VcsOperations.hasStagedChanges(dir)) {
                getLog().info(Ansi.yellow("  ⟳ ") + label
                        + " — would commit: staged changes");
                return true;
            }
            getLog().info("  · " + label + " — clean");
            return false;
        }
        getLog().info(Ansi.yellow("  ⟳ ") + label + " — would commit: "
                + WsCommitPublishMojo.previewSummary(modCount, newFiles));
        return true;
    }
}
