package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Workspace-walking variant of {@code ike:scaffold-draft} (#350).
 *
 * <p>Iterates the workspace's subprojects (and the workspace root)
 * in declaration order, invoking {@code ike:scaffold-draft} in each
 * via a subprocess {@code mvn} call. Each per-subproject invocation
 * is independent — output is streamed straight through, drift
 * reports surface inline.
 *
 * <p>Mirrors the {@code ws:release-publish} → per-subproject
 * {@code ike:release-publish} cascade pattern. Read-only: no POM
 * mutation, no lockfile writes; use {@link WsScaffoldPublishMojo}
 * to apply.
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ws:scaffold-draft        # report drift across every subproject
 * mvn ws:scaffold-publish      # apply (foundation drift opt-in)
 * }</pre>
 *
 * @see WsScaffoldPublishMojo
 */
@Mojo(name = "scaffold-draft", projectRequired = false, aggregator = true)
public class WsScaffoldDraftMojo extends AbstractWorkspaceMojo {

    /**
     * When {@code true}, the subprocess invocation is
     * {@code ike:scaffold-publish}; when {@code false} (default for
     * the draft variant), it is {@code ike:scaffold-draft}. Set by
     * {@link WsScaffoldPublishMojo} for the apply path.
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /**
     * Forwarded to per-subproject {@code ike:scaffold-publish}: opt
     * in to apply the foundation drift identified by #345's checker.
     * Only consulted when {@link #publish} is {@code true}.
     */
    @Parameter(property = "ike.scaffold.apply-foundation",
               defaultValue = "false")
    boolean applyFoundation;

    /** Creates this goal instance. */
    public WsScaffoldDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        String goal = publish ? "ike:scaffold-publish" : "ike:scaffold-draft";

        getLog().info("");
        getLog().info(publish ? "ws:scaffold-publish" : "ws:scaffold-draft");
        getLog().info("══════════════════════════════════════════════════════════════");

        // Walk each subproject in topological order, then the
        // workspace root. Topological order isn't strictly needed
        // for scaffold (each project's drift is independent), but
        // keeps output predictable.
        List<String> targets = new ArrayList<>();
        for (String name : graph.topologicalSort()) {
            File subDir = new File(root, name);
            if (!new File(subDir, "pom.xml").exists()) {
                getLog().debug("  " + name + ": not cloned — skipping");
                continue;
            }
            targets.add(name);
        }
        // Workspace root last (mirrors ws:release-publish ordering).
        boolean walkRoot = new File(root, "pom.xml").exists();

        int processed = 0;
        int failed = 0;
        for (String name : targets) {
            File subDir = new File(root, name);
            getLog().info("");
            getLog().info("── " + name + " ".repeat(Math.max(1, 60 - name.length())) + "──");
            try {
                runScaffoldInSubproject(subDir, goal);
                processed++;
            } catch (MojoException e) {
                getLog().error("  ✗ " + name + ": " + e.getMessage());
                failed++;
            }
        }
        if (walkRoot) {
            getLog().info("");
            getLog().info("── (workspace root)" + " ".repeat(43) + "──");
            try {
                runScaffoldInSubproject(root, goal);
                processed++;
            } catch (MojoException e) {
                getLog().error("  ✗ workspace root: " + e.getMessage());
                failed++;
            }
        }

        getLog().info("");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Walked " + processed + " project(s)"
                + (failed > 0 ? "; " + failed + " failed" : ""));
        if (failed > 0) {
            throw new MojoException(
                    "ws:" + (publish ? "scaffold-publish" : "scaffold-draft")
                    + " saw " + failed + " per-subproject failure(s); "
                    + "see logs above.");
        }
    }

    /**
     * Run {@code mvn <goal>} in the given subproject directory. When
     * {@code goal} is {@code ike:scaffold-publish} and the
     * {@link #applyFoundation} flag is set, forwards
     * {@code -Dike.scaffold.apply-foundation=true}.
     *
     * @param subDir the subproject directory
     * @param goal   the ike goal to invoke (draft or publish)
     */
    private void runScaffoldInSubproject(File subDir, String goal)
            throws MojoException {
        String mvn = WsReleaseDraftMojo.resolveMvnCommand(subDir);
        List<String> args = new ArrayList<>();
        args.add(mvn);
        args.add(goal);
        args.add("-B");
        if (publish && applyFoundation) {
            args.add("-Dike.scaffold.apply-foundation=true");
        }
        ReleaseSupport.exec(subDir, getLog(), args.toArray(new String[0]));
    }
}
