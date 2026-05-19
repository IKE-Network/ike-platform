package network.ike.plugin.ws;

import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;
import network.ike.plugin.ws.reconcile.AlignmentReconciler;
import network.ike.plugin.ws.reconcile.DriftReport;
import network.ike.plugin.ws.reconcile.ReconcilerOptions;
import network.ike.plugin.ws.reconcile.WorkspaceContext;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Preview inter-subproject dependency, plugin, and parent version
 * alignment across every cloned subproject.
 *
 * <p>This is the {@code ws:align-draft} goal — a thin wrapper over
 * {@link AlignmentReconciler}. The same reconciler is iterated from
 * {@code ws:scaffold-{draft,publish}} (#393); this standalone goal
 * exists for the "I detected misalignment, run only that" use case
 * where iterating the full convergence pass would be needlessly
 * broad.
 *
 * <p>This goal also serves as the designated entry point for the
 * legacy schema migration ({@code components:} → {@code subprojects:},
 * #150): it calls
 * {@link ManifestReader#migrateLegacySchemaIfNeeded} before reading
 * the graph so old manifests are rewritten in place.
 *
 * <p>Branch reconciliation (the rare "manifest ↔ git state" recovery
 * operation that used to share this goal as
 * {@code -Dscope=branches}) moved to
 * {@link WsReconcileBranchesDraftMojo} ({@code ws:reconcile-branches-draft})
 * per the ike-issues#200 split. The {@code scope} parameter on this
 * goal is retained only to emit a clear migration error for users
 * with muscle memory.
 *
 * <pre>{@code
 * mvn ws:align-draft     # preview POM version updates
 * mvn ws:align-publish   # apply
 * }</pre>
 *
 * @see AlignmentReconciler
 * @see WsReconcileBranchesDraftMojo
 */
@Mojo(name = "align-draft", projectRequired = false, aggregator = true)
public class WsAlignDraftMojo extends AbstractWorkspaceMojo {

    /**
     * When true, apply changes; when false (default), preview only.
     * Package-private so {@link WsAlignPublishMojo} can flip it.
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /**
     * Migration aid for the ike-issues#200 two-axis split. The only
     * accepted value is {@code poms} (the new default). Passing
     * {@code branches} or {@code all} throws with a pointer to
     * {@code ws:reconcile-branches-{draft,publish}}, so users with
     * muscle memory get a clear error rather than silent breakage.
     */
    @Parameter(property = "scope", defaultValue = "poms")
    String scope;

    /** Creates this goal instance. */
    public WsAlignDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        boolean draft = !publish;

        // Migration gate (ike-issues#200): the align goals are POM-only.
        // Branch reconciliation moved to ws:reconcile-branches-{draft,publish}.
        if ("branches".equals(scope) || "all".equals(scope)) {
            String invokedGoal = (publish ? WsGoal.ALIGN_PUBLISH
                                           : WsGoal.ALIGN_DRAFT).qualified();
            throw new MojoException(
                    invokedGoal + " no longer supports -Dscope=" + scope
                            + " (ike-issues#200). Branch reconciliation moved to:\n"
                            + "  mvn " + (publish ? WsGoal.RECONCILE_BRANCHES_PUBLISH
                                                  : WsGoal.RECONCILE_BRANCHES_DRAFT).qualified()
                            + "\n  " + invokedGoal
                            + " is now POM-only — drop -Dscope=.");
        }
        if (!"poms".equals(scope)) {
            throw new MojoException(
                    "Invalid scope '" + scope + "' — expected poms");
        }

        // #150 migration: ws:align-publish (and the draft variant) is the
        // designated entry point that rewrites legacy schemas in place.
        // Every other reader hits the hard-cut in ManifestReader.read, so
        // users on an old workspace see a message pointing them at
        // ws:align-publish first. Do this BEFORE loadGraph() so graph
        // construction sees the migrated content.
        Path manifestPath = resolveManifest();
        ManifestReader.migrateLegacySchemaIfNeeded(
                manifestPath, msg -> getLog().info("  " + msg));

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        getLog().info("");
        getLog().info("IKE Workspace Align — reconcile inter-subproject versions");
        getLog().info("══════════════════════════════════════════════════════════════");
        if (draft) {
            getLog().info("  (draft — no files will be modified)");
        }
        getLog().info("");

        // POM alignment requires clean working trees (#132) so the
        // rewrite commit doesn't bundle unrelated edits.
        List<String> sorted = graph.topologicalSort();
        PreflightResult preflight = Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, sorted));
        if (draft) {
            preflight.warnIfFailed(getLog(), WsGoal.ALIGN_PUBLISH);
        } else {
            preflight.requirePassed(WsGoal.ALIGN_PUBLISH);
        }

        WorkspaceContext ctx = new WorkspaceContext(
                root, manifestPath, graph,
                readReconcilerOptions(), getLog());
        AlignmentReconciler reconciler = new AlignmentReconciler();

        String content;
        if (draft) {
            DriftReport report = reconciler.detect(ctx);
            printDriftReport(report);
            content = report.toMarkdown();
        } else {
            reconciler.apply(ctx);
            content = "Inter-subproject version alignment applied across "
                    + "the workspace.\n";
        }
        getLog().info("");
        return new WorkspaceReportSpec(
                publish ? WsGoal.ALIGN_PUBLISH : WsGoal.ALIGN_DRAFT, content);
    }

    /**
     * Collect Maven system properties into a {@link ReconcilerOptions}
     * bag so the reconciler can query its opt-out flag
     * ({@code -DupdateAlignment=false}).
     */
    private static ReconcilerOptions readReconcilerOptions() {
        Map<String, String> flags = new HashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            flags.put(name, System.getProperty(name));
        }
        return new ReconcilerOptions(flags);
    }

    /**
     * Render a {@link DriftReport} for {@code ws:align-draft} output
     * with the copy-paste opt-out command inline. Mirrors the format
     * used by {@code ws:scaffold-draft}'s reconciler loop.
     */
    private void printDriftReport(DriftReport report) {
        if (!report.hasDrift()) {
            getLog().info("  ✓ " + report.dimension());
            return;
        }
        getLog().info("  ⚠ " + report.dimension());
        if (!report.summary().isEmpty()) {
            getLog().info("     " + report.summary());
        }
        for (String line : report.detailLines()) {
            getLog().info("       " + line);
        }
        if (!report.defaultAction().isEmpty()) {
            getLog().info("     Default: " + report.defaultAction());
        }
        if (!report.optOutCommand().isEmpty()) {
            getLog().info("     Opt out: " + report.optOutCommand());
        }
    }
}
