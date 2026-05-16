package network.ike.plugin.ws;

import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;
import network.ike.workspace.WorkspaceGraph;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Surface workspace-hygiene preflight conditions as a standalone gate
 * (ike-issues#217).
 *
 * <p>Runs every {@link PreflightCondition} entry in <em>report-only</em>
 * mode against the current workspace and emits a markdown summary.
 * Always exits 0 — the goal is to make hygiene problems visible
 * (typo'd {@code .mvn/jvm.config} comments, uncommitted state, leaking
 * SNAPSHOT properties) before they propagate to git or Syncthing,
 * not to gate the build itself. Mutating goals already require their
 * relevant subset of conditions; this goal is the standalone reporting
 * surface.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * mvn ws:lint                        # run every preflight check, warn on failures
 * }</pre>
 *
 * <p>{@code ws:commit} also invokes the {@code JVM_CONFIG_NO_HASH_COMMENTS}
 * subset by default so the typo'd-comment failure mode (which crashes
 * the JVM before any Maven plugin can run) is caught at the transport
 * boundary.
 */
@Mojo(name = "lint", projectRequired = false, aggregator = true)
public class WsLintMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public WsLintMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        List<String> sorted = graph.topologicalSort();

        getLog().info("");
        getLog().info(header("Lint"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        List<PreflightCondition> all = Arrays.asList(PreflightCondition.values());
        PreflightResult result = Preflight.of(all,
                PreflightContext.of(root, graph, sorted));

        // warnIfFailed prints failure remediation to the log; it never
        // throws. Pass LINT so the diagnostic context names this goal.
        result.warnIfFailed(getLog(), WsGoal.LINT);

        StringBuilder md = new StringBuilder();
        md.append("**Conditions checked:** ").append(all.size()).append("\n\n");

        if (result.passed()) {
            md.append("All preflight conditions passed.  ✓\n");
            getLog().info("  All preflight conditions passed  ✓");
        } else {
            md.append(result.failures().size())
              .append(" preflight condition(s) reported issues:\n\n");
            for (PreflightResult.Failure f : result.failures()) {
                md.append("- **").append(f.condition().name()).append("** — ")
                  .append(f.condition().description()).append("\n");
            }
            md.append("\n_See goal log for remediation details._\n");
        }
        getLog().info("");

        return new WorkspaceReportSpec(WsGoal.LINT, md.toString());
    }
}
