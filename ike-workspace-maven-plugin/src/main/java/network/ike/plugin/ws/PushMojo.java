package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Push with a VCS bridge catch-up preamble.
 *
 * <p>When run from a workspace root (where {@code workspace.yaml} exists),
 * iterates all subproject repositories in topological order and pushes each.
 * When run from a single repository, operates on the current directory only.
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ws:push
 * }</pre>
 */
@Mojo(name = "push", projectRequired = false, aggregator = true)
public class PushMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public PushMojo() {}

    /**
     * Remote name to push to.
     */
    @Parameter(property = "remote", defaultValue = "origin")
    String remote;

    /**
     * When {@code true}, the first push failure (e.g. non-fast-forward,
     * authentication error) halts the goal with a {@link MojoException}
     * instead of warning and continuing. Default {@code false} preserves
     * the per-subproject best-effort behavior; {@link WsSyncMojo} sets
     * this to {@code true} so a non-fast-forward leaves the workspace
     * in a known state for the user to resolve, rather than appearing
     * to succeed for some subprojects and silently failing for others.
     */
    @Parameter(property = "failFast", defaultValue = "false")
    boolean failFast;

    @Override
    public void execute() throws MojoException {
        if (isWorkspaceMode()) {
            executeWorkspace();
        } else {
            executeSingleRepo(new File(System.getProperty("user.dir")));
        }
    }

    private void executeWorkspace() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        Set<String> targets = graph.manifest().subprojects().keySet();

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        getLog().info("");
        getLog().info(header("Push"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        int pushed = 0;
        int skipped = 0;
        int failed = 0;
        int uncommittedWarnings = 0;

        // Push workspace root if it has a .git directory
        if (new File(root, ".git").exists()) {
            try {
                VcsOperations.catchUp(root, getLog());
                String branch = VcsOperations.currentBranch(root);
                VcsOperations.push(root, getLog(), remote, branch);
                VcsOperations.writeVcsState(root, VcsState.Action.PUSH);
                getLog().info(Ansi.green("  ✓ ") + "workspace root → "
                        + remote + "/" + branch);
                pushed++;
                if (!VcsOperations.isClean(root)) {
                    getLog().warn(Ansi.yellow("  ⚠ ") + "workspace root"
                            + " — has uncommitted changes (not pushed)");
                    uncommittedWarnings++;
                }
            } catch (MojoException e) {
                if (failFast) {
                    throw new MojoException("push failed at workspace root: "
                            + e.getMessage(), e);
                }
                getLog().warn(Ansi.red("  ✗ ") + "workspace root — "
                        + e.getMessage());
                failed++;
            }
        }

        for (String name : sorted) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().debug(name + " — not cloned, skipping");
                skipped++;
                continue;
            }

            try {
                VcsOperations.catchUp(dir, getLog());

                String branch = VcsOperations.currentBranch(dir);
                try {
                    VcsOperations.push(dir, getLog(), remote, branch);
                } catch (MojoException e) {
                    // Handle missing upstream — retry with -u (#132)
                    if (e.getMessage() != null
                            && e.getMessage().contains("has no upstream")) {
                        getLog().info("  " + name
                                + " — setting upstream and pushing...");
                        VcsOperations.pushWithUpstream(
                                dir, getLog(), remote, branch);
                    } else {
                        throw e;
                    }
                }
                VcsOperations.writeVcsState(dir, VcsState.Action.PUSH);

                getLog().info(Ansi.green("  ✓ ") + name + " → "
                        + remote + "/" + branch);
                pushed++;

                // Warn if repo has uncommitted changes (#132)
                if (!VcsOperations.isClean(dir)) {
                    getLog().warn(Ansi.yellow("  ⚠ ") + name
                            + " — has uncommitted changes (not pushed)");
                    uncommittedWarnings++;
                }
            } catch (MojoException e) {
                if (failFast) {
                    throw new MojoException("push failed at " + name + ": "
                            + e.getMessage(), e);
                }
                getLog().warn(Ansi.red("  ✗ ") + name + " — "
                        + e.getMessage());
                failed++;
            }
        }

        getLog().info("");
        var summary = new StringBuilder();
        summary.append(pushed).append(" pushed");
        if (skipped > 0) {
            summary.append(", ").append(skipped).append(" skipped");
        }
        if (uncommittedWarnings > 0) {
            summary.append(", ").append(uncommittedWarnings)
                    .append(" with uncommitted changes");
        }
        if (failed > 0) {
            summary.append(", ").append(failed).append(" failed");
        }
        getLog().info("  Done: " + summary);
        getLog().info("");

        if (failed > 0) {
            getLog().warn("  Some pushes failed — check output above for details.");
        }

        writeReport(WsGoal.PUSH, summary + "\n");
    }

    private void executeSingleRepo(File dir) throws MojoException {
        getLog().info("");
        getLog().info("IKE VCS Bridge — Push");
        getLog().info("══════════════════════════════════════════════════════════════");

        VcsOperations.catchUp(dir, getLog());

        String branch = VcsOperations.currentBranch(dir);
        getLog().info("  Pushing to " + remote + "/" + branch + "...");
        VcsOperations.push(dir, getLog(), remote, branch);

        VcsOperations.writeVcsState(dir, VcsState.Action.PUSH);

        getLog().info("");
        getLog().info("  Done.");
        getLog().info("");
    }
}
