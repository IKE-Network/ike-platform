package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;

import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pull latest changes across the workspace.
 *
 * <p>When the workspace root is itself a git repository (i.e., it has a
 * {@code .git} directory), it is pulled first so any changes to the root
 * POM or {@code workspace.yaml} land before subproject operations run.
 * Runs {@code git pull --rebase} in each cloned subproject directory in
 * topological order (dependencies first). Uninitialized components are
 * skipped with a warning.
 *
 * <pre>{@code
 * mvn ws:pull
 * }</pre>
 */
@Mojo(name = "pull", projectRequired = false, aggregator = true)
public class PullWorkspaceMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public PullWorkspaceMojo() {}

    @Override
    public void execute() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        Set<String> targets = graph.manifest().subprojects().keySet();

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        // Preflight: all working trees must be clean (#132, #154)
        Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, sorted))
                .requirePassed(WsGoal.PULL);

        getLog().info("");
        getLog().info(header("Pull"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        int pulled = 0;
        int skipped = 0;
        int failed = 0;

        // Pull workspace root if it has a .git directory (#179). Must run
        // before subproject pulls so any update to the root POM or
        // workspace.yaml is observed by downstream steps.
        if (new File(root, ".git").exists()) {
            getLog().info(Ansi.cyan("  ↓ ") + "workspace root");
            try {
                ReleaseSupport.exec(root, getLog(),
                        "git", "pull", "--rebase");
                pulled++;
            } catch (MojoException e) {
                getLog().warn(Ansi.red("  ✗ ") + "workspace root — pull failed: "
                        + e.getMessage());
                failed++;
            }
        }

        for (String name : sorted) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().info(Ansi.yellow("  ⚠ ") + name + " — not cloned, skipping");
                skipped++;
                continue;
            }

            getLog().info(Ansi.cyan("  ↓ ") + name);
            try {
                ReleaseSupport.exec(dir, getLog(),
                        "git", "pull", "--rebase");
                pulled++;
            } catch (MojoException e) {
                getLog().warn(Ansi.red("  ✗ ") + name + " — pull failed: " + e.getMessage());
                failed++;
            }
        }

        getLog().info("");
        getLog().info("  Done: " + pulled + " pulled, " + skipped
                + " skipped, " + failed + " failed");
        getLog().info("");

        if (failed > 0) {
            getLog().warn("  Some pulls failed — check output above for details.");
        }

        // Structured markdown report
        writeReport(WsGoal.PULL, pulled + " pulled, " + skipped
                + " skipped, " + failed + " failed.\n");

        IdeProfileSync.run(root, getLog());
    }
}
