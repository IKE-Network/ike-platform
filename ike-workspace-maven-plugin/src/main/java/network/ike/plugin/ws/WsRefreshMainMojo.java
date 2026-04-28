package network.ike.plugin.ws;

import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Refresh local main from {@code origin/main} across the workspace.
 *
 * <p>For each subproject, this goal fetches origin and reconciles local
 * main with {@code origin/main}: fast-forward when behind, leave alone
 * when purely ahead (unpushed work), auto-resolve via merge when
 * diverged. The auto-resolve merge stays local until the user pushes
 * via {@code ws:push} or {@code ws:sync}.
 *
 * <p>Use this when you want main current across the workspace without
 * doing anything else &mdash; before review, before starting a feature,
 * after returning to a machine. The same logic runs as a precondition
 * inside {@code ws:update-feature-*}, {@code ws:feature-finish-*},
 * {@code ws:feature-start-*}, and {@code ws:sync}, so this goal is
 * mostly a convenience wrapper.
 *
 * <p>If the auto-resolve merge would produce file conflicts (the rare
 * "two machines edited the same file on main without push/pull" case),
 * the goal hard-errors with the conflict list. The working tree is not
 * touched.
 *
 * <pre>{@code
 * mvn ws:refresh-main                       # refresh "main" from origin
 * mvn ws:refresh-main -DmainBranch=trunk    # alternative branch name
 * mvn ws:refresh-main -Dremote=upstream     # alternative remote name
 * }</pre>
 *
 * <p>See ike-issues#284.
 */
@Mojo(name = "refresh-main", projectRequired = false, aggregator = true)
public class WsRefreshMainMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public WsRefreshMainMojo() {}

    /** The conceptual main branch to refresh. */
    @Parameter(property = "mainBranch", defaultValue = "main")
    String mainBranch;

    /** The remote to refresh from. */
    @Parameter(property = "remote",
            defaultValue = RefreshMainSupport.DEFAULT_REMOTE)
    String remote;

    @Override
    public void execute() throws MojoException {
        if (!isWorkspaceMode()) {
            throw new MojoException(
                    "ws:refresh-main requires a workspace (workspace.yaml).");
        }

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Set<String> targets = graph.manifest().subprojects().keySet();
        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        getLog().info("");
        getLog().info(header("Refresh Main"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Branch:  " + mainBranch);
        getLog().info("  Remote:  " + remote);
        getLog().info("  Scope:   " + sorted.size() + " components");
        getLog().info("");

        List<RefreshMainSupport.Outcome> outcomes =
                RefreshMainSupport.refreshOrThrow(root, sorted, mainBranch, getLog());

        writeReport(WsGoal.REFRESH_MAIN, buildReport(outcomes));

        PostMutationSync.refresh(root, getLog());
    }

    private String buildReport(List<RefreshMainSupport.Outcome> outcomes) {
        var sb = new StringBuilder();
        sb.append("**Branch:** `").append(mainBranch).append("` ← `")
          .append(remote).append("/").append(mainBranch).append("`\n\n");
        sb.append("| Subproject | Outcome |\n");
        sb.append("|-----------|---------|\n");
        for (RefreshMainSupport.Outcome o : outcomes) {
            sb.append("| ").append(o.component()).append(" | ")
              .append(outcomeLabel(o)).append(" |\n");
        }
        sb.append("\n**").append(outcomes.size())
          .append("** subproject(s) refreshed.\n");
        return sb.toString();
    }

    private static String outcomeLabel(RefreshMainSupport.Outcome o) {
        return switch (o) {
            case RefreshMainSupport.Skipped(var c, var r) -> "skipped (" + r + ")";
            case RefreshMainSupport.UpToDate(var c) -> "up to date";
            case RefreshMainSupport.FastForwarded(var c, var n) ->
                    "fast-forwarded " + n + " commit" + (n == 1 ? "" : "s");
            case RefreshMainSupport.CreatedFromRemote(var c) -> "created from remote";
            case RefreshMainSupport.AheadOnly(var c, var n) ->
                    n + " unpushed commit" + (n == 1 ? "" : "s") + ", left as-is";
            case RefreshMainSupport.AutoResolved(var c, var local, var remoteCount) ->
                    "auto-resolved (kept " + local + " local, merged "
                            + remoteCount + " from remote)";
            case RefreshMainSupport.Conflicts(var c, var files) ->
                    files.size() + " conflict(s)";
        };
    }
}
