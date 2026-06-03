package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconcile {@code workspace.yaml} branch fields against on-disk git
 * state (preview).
 *
 * <p>This is the {@code ws:reconcile-branches-draft} goal — recovery /
 * rare-use, separated from the {@code ws:align-{draft,publish}}
 * POM-axis daily driver per ike-issues#200's two-axis split (Option B).
 * Each goal name now describes its audience: {@code ws:align-draft} /
 * {@code ws:align-publish} (backed by
 * {@link network.ike.plugin.ws.reconcile.AlignmentReconciler}) is the
 * safe daily POM convergence; {@code ws:reconcile-branches-draft} /
 * {@code -publish} is the branch-state recovery operation that runs
 * when something has already gone wrong.
 *
 * <p>Three directions are supported via {@code -Dfrom=...}:
 *
 * <ul>
 *   <li>{@code repos} (default) — read each subproject's actual branch
 *       and update {@code workspace.yaml} to match.</li>
 *   <li>{@code manifest} — {@code git checkout} each subproject to the
 *       branch declared in {@code workspace.yaml}.</li>
 *   <li>{@code workspace-head} — the workspace repo's HEAD is
 *       authoritative; reconcile both YAML fields <em>and</em> on-disk
 *       branches to that single value (ike-issues#287).</li>
 * </ul>
 *
 * <pre>{@code
 * mvn ws:reconcile-branches-draft                       # report only (from=repos)
 * mvn ws:reconcile-branches-publish                      # apply (from=repos)
 * mvn ws:reconcile-branches-publish -Dfrom=manifest      # checkout repos to declared branches
 * mvn ws:reconcile-branches-publish -Dfrom=workspace-head -Dforce=true
 * }</pre>
 */
@Mojo(name = "reconcile-branches-draft", projectRequired = false, aggregator = true)
public class WsReconcileBranchesDraftMojo extends AbstractWorkspaceMojo {

    /**
     * When true, apply changes; when false, draft (preview only).
     * Package-private so {@link WsReconcileBranchesPublishMojo} can
     * flip it in its {@code execute()} override.
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /**
     * Legacy scope parameter — retained for compatibility with the
     * pre-ike-issues#200 era. Branch reconciliation is the only valid
     * scope for this goal; any other value is rejected with an error
     * pointing the user at {@code ws:align-draft}.
     *
     * <p>The default is {@code branches} so users do not need to pass
     * it explicitly.
     */
    @Parameter(property = "scope", defaultValue = "branches")
    String scope;

    /**
     * Branch-sync direction.
     *
     * <ul>
     *   <li>{@code repos} (default) — read actual branches and update
     *       {@code workspace.yaml}.</li>
     *   <li>{@code manifest} — run {@code git checkout} per subproject
     *       so repos match the yaml.</li>
     *   <li>{@code workspace-head} — the workspace repo's current git
     *       branch is authoritative; reconcile both the {@code branch:}
     *       fields in {@code workspace.yaml} <em>and</em> each
     *       subproject's on-disk branch to that single value
     *       (ike-issues#287).</li>
     * </ul>
     */
    @Parameter(property = "from", defaultValue = "repos")
    String from;

    /**
     * When true, allow branch checkout against subprojects with
     * uncommitted changes. Consulted with
     * {@code from=manifest|workspace-head}. Default {@code false}.
     */
    @Parameter(property = "force", defaultValue = "false")
    boolean force;

    /** Creates this goal instance. */
    public WsReconcileBranchesDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        // Defensive scope validation: this goal is branch-only. Any
        // other -Dscope= value is almost certainly a user invoking the
        // wrong goal (e.g., they meant ws:align-draft).
        if (!"branches".equals(scope) && !"all".equals(scope)) {
            throw new MojoException(
                    "ws:reconcile-branches only supports -Dscope=branches "
                            + "(default). For POM alignment, use "
                            + (publish ? WsGoal.ALIGN_PUBLISH
                                       : WsGoal.ALIGN_DRAFT).qualified() + ".");
        }
        if (!"repos".equals(from)
                && !"manifest".equals(from)
                && !"workspace-head".equals(from)) {
            throw new MojoException(
                    "Invalid from '" + from
                            + "' — expected repos|manifest|workspace-head");
        }

        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        getLog().info("");
        getLog().info("IKE Workspace Reconcile Branches — "
                + describeFromMode());
        getLog().info("══════════════════════════════════════════════════════════════");
        if (draft) {
            getLog().info("  (draft — no files will be modified)");
        }
        getLog().info("");

        BranchChanges result = alignBranches(graph, root, manifestPath, draft);
        int totalChanges = result.changes().size();

        getLog().info("");
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Mode:** " + describeFromMode()
                + (draft ? "  _(draft — no changes made)_" : ""));

        if (totalChanges == 0 && result.skipped().isEmpty()) {
            getLog().info("  Nothing to reconcile  ✓");
            report.paragraph("Nothing to reconcile — branches already coherent.");
        } else {
            report.paragraph("**" + totalChanges + "** branch change(s) "
                    + (draft ? "would be applied" : "applied") + ".");

            if (!result.changes().isEmpty()) {
                report.section(draft ? "Would change" : "Changed");
                for (String c : result.changes()) report.bullet(c);
            }
            if (!result.skipped().isEmpty()) {
                report.section("Skipped (uncommitted)");
                for (String s : result.skipped()) report.bullet(s);
            }

            if (draft) {
                getLog().info("  " + totalChanges + " change(s) would be applied");
                getLog().info("  Use "
                        + WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified() + " to apply.");
                String cmd = "mvn " + WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified();
                if (!"repos".equals(from)) cmd += " -Dfrom=" + from;
                if (!result.skipped().isEmpty()) cmd += " -Dforce=true";
                report.paragraph("Apply with `" + cmd + "`.");
            } else {
                getLog().info("  Applied " + totalChanges + " change(s)");
            }
        }
        getLog().info("");
        return new WorkspaceReportSpec(
                publish ? WsGoal.RECONCILE_BRANCHES_PUBLISH
                        : WsGoal.RECONCILE_BRANCHES_DRAFT,
                report.build());
    }

    /**
     * Per-branch reconcile outcome carried into the report: one
     * human-readable line per branch that differs, plus any subprojects
     * skipped for uncommitted changes (no {@code -Dforce}).
     *
     * @param changes one line per branch change (markdown)
     * @param skipped one line per subproject skipped (uncommitted)
     */
    private record BranchChanges(List<String> changes, List<String> skipped) {}

    /**
     * Dispatch on {@code -Dfrom=...} to the right branch-reconcile
     * direction.
     *
     * @return the per-branch changes applied, or that would be applied
     *         in draft mode, plus any skipped subprojects
     */
    private BranchChanges alignBranches(WorkspaceGraph graph, File root,
                              Path manifestPath, boolean draft)
            throws MojoException {
        return switch (from) {
            case "manifest" ->
                    alignBranchesFromManifest(graph, root, draft);
            case "workspace-head" ->
                    alignBranchesFromWorkspaceHead(graph, root, manifestPath,
                            draft);
            default ->
                    alignBranchesFromRepos(graph, root, manifestPath, draft);
        };
    }

    /** Human-readable label for the active {@code from=...} mode. */
    private String describeFromMode() {
        return switch (from) {
            case "manifest" -> "manifest → repos (git checkout)";
            case "workspace-head" ->
                    "workspace HEAD → manifest + repos (authoritative branch)";
            default -> "repos → manifest (update yaml)";
        };
    }

    /**
     * Read actual branches from each cloned subproject and update
     * {@code workspace.yaml} so the declared branch fields match
     * reality.
     */
    private BranchChanges alignBranchesFromRepos(WorkspaceGraph graph, File root,
                                       Path manifestPath, boolean draft)
            throws MojoException {
        Map<String, String> updates = new LinkedHashMap<>();
        List<String> changes = new ArrayList<>();

        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            String actual = gitBranch(dir);
            String declared = subproject.branch();
            if (actual.equals(declared)) continue;

            updates.put(name, actual);
            changes.add("`" + name + "` (yaml): `" + declared
                    + "` → `" + actual + "`");
            getLog().info("  branch: " + name + ": " + declared
                    + " → " + actual + (draft ? " (draft)" : ""));
        }

        if (updates.isEmpty()) {
            getLog().info("  Branches: yaml already matches repos  ✓");
            return new BranchChanges(List.of(), List.of());
        }

        if (!draft) {
            try {
                ManifestWriter.updateBranches(manifestPath, updates);
                getLog().info("  Branches: updated workspace.yaml ("
                        + updates.size() + " change(s))");
                File wsRoot = manifestPath.getParent().toFile();
                if (new File(wsRoot, ".git").exists()) {
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "add", "workspace.yaml");
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "commit", "-m",
                            "workspace: align branch fields from repos");
                }
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to update workspace.yaml: " + e.getMessage(), e);
            }
        }

        return new BranchChanges(changes, List.of());
    }

    /**
     * Reconcile every subproject's {@code branch:} field <em>and</em>
     * on-disk git branch to the workspace repo's current HEAD.
     *
     * <p>This is the symmetric repair to {@code ws:add}'s
     * branch-coherence rule (ike-issues#286): the workspace repo's
     * branch is authoritative, and this mode brings everything else
     * (YAML state, on-disk state) into agreement with it.
     *
     * <p>Behavior per subproject:
     * <ol>
     *   <li>If the YAML {@code branch:} != workspace HEAD → queue YAML
     *       update.</li>
     *   <li>If the on-disk branch != workspace HEAD → queue checkout.
     *       Subprojects with uncommitted changes are skipped unless
     *       {@code -Dforce=true} (same semantics as
     *       {@code from=manifest}).</li>
     * </ol>
     *
     * <p>If a subproject's local repo doesn't yet have the workspace
     * branch, {@code git checkout} will fall through to creating it
     * from {@code origin/<branch>} (the standard tracking-branch path).
     * If the branch isn't on origin either, the checkout fails — that
     * case is the {@code ws:add}'s territory; this mode does not push
     * new branches to subproject origins.
     *
     * @return the per-branch changes (applied, or that would be in draft
     *         mode) plus any skipped subprojects
     * @throws MojoException if the workspace dir is not a git repo, or
     *                       any individual checkout fails
     */
    private BranchChanges alignBranchesFromWorkspaceHead(WorkspaceGraph graph,
                                                File root, Path manifestPath,
                                                boolean draft) {
        File wsRoot = manifestPath.getParent().toFile();
        if (!new File(wsRoot, ".git").exists()) {
            throw new MojoException(
                    "from=workspace-head requires the workspace directory to be "
                            + "a git repository. " + wsRoot.getAbsolutePath()
                            + " has no .git directory.");
        }
        String wsBranch = gitBranch(wsRoot);
        if (wsBranch == null || wsBranch.isBlank() || "unknown".equals(wsBranch)) {
            throw new MojoException(
                    "Could not read the workspace repo's current branch. "
                            + "Ensure HEAD points at a named branch (not a "
                            + "detached HEAD).");
        }

        getLog().info("  Workspace HEAD: " + wsBranch);

        Map<String, String> yamlUpdates = new LinkedHashMap<>();
        List<String> changes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int checkoutsPlanned = 0;
        int checkoutsApplied = 0;
        int skippedDirty = 0;

        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File dir = new File(root, name);

            // YAML reconciliation runs whether or not the repo is cloned.
            String declared = subproject.branch();
            if (declared == null || !declared.equals(wsBranch)) {
                yamlUpdates.put(name, wsBranch);
                changes.add("`" + name + "` (yaml): `"
                        + (declared == null ? "(unset)" : declared)
                        + "` → `" + wsBranch + "`");
                getLog().info("  branch: " + name + " (yaml): "
                        + (declared == null ? "(unset)" : declared)
                        + " → " + wsBranch + (draft ? " (draft)" : ""));
            }

            // Checkout reconciliation only applies to cloned subprojects.
            if (!new File(dir, ".git").exists()) continue;
            String actual = gitBranch(dir);
            if (wsBranch.equals(actual)) continue;

            String status = gitStatus(dir);
            if (!status.isEmpty() && !force) {
                getLog().warn("  ⚠ " + name + ": uncommitted changes — skipping"
                        + " checkout (pass -Dforce=true to override)");
                skipped.add("`" + name + "` — uncommitted (use -Dforce=true)");
                skippedDirty++;
                continue;
            }

            checkoutsPlanned++;
            changes.add("`" + name + "` (repo): `" + actual
                    + "` → `" + wsBranch + "`");
            getLog().info("  branch: " + name + " (repo): " + actual
                    + " → " + wsBranch + (draft ? " (draft)" : ""));

            if (!draft) {
                if (localBranchExists(dir, wsBranch)
                        || originBranchExists(dir, wsBranch)) {
                    ReleaseSupport.exec(dir, getLog(),
                            "git", "checkout", wsBranch);
                } else {
                    getLog().info("    " + name + " has no '" + wsBranch
                            + "' locally or on origin — creating from "
                            + actual + ".");
                    ReleaseSupport.exec(dir, getLog(),
                            "git", "checkout", "-b", wsBranch);
                }
                checkoutsApplied++;
            }
        }

        if (yamlUpdates.isEmpty() && checkoutsPlanned == 0 && skippedDirty == 0) {
            getLog().info("  Branches: workspace, manifest, and repos all agree  ✓");
            return new BranchChanges(List.of(), List.of());
        }

        if (!draft && !yamlUpdates.isEmpty()) {
            try {
                ManifestWriter.updateBranches(manifestPath, yamlUpdates);
                getLog().info("  Branches: updated workspace.yaml ("
                        + yamlUpdates.size() + " field(s))");
                ReleaseSupport.exec(wsRoot, getLog(),
                        "git", "add", "workspace.yaml");
                ReleaseSupport.exec(wsRoot, getLog(),
                        "git", "commit", "-m",
                        "workspace: align branch fields to " + wsBranch);
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to update workspace.yaml: " + e.getMessage(), e);
            }
        }

        if (draft) {
            getLog().info("  Branches: " + (yamlUpdates.size() + checkoutsPlanned)
                    + " change(s) would be applied"
                    + (skippedDirty > 0
                            ? " (" + skippedDirty + " skipped — uncommitted)"
                            : ""));
            return new BranchChanges(changes, skipped);
        }
        getLog().info("  Branches: " + yamlUpdates.size()
                + " yaml update(s), " + checkoutsApplied + " checkout(s), "
                + skippedDirty + " skipped (uncommitted)");
        return new BranchChanges(changes, skipped);
    }

    /**
     * Whether {@code refs/heads/<branch>} exists in the local repo.
     */
    private static boolean localBranchExists(File dir, String branch) {
        try {
            String out = ReleaseSupport.execCapture(dir,
                    "git", "branch", "--list", branch);
            return out != null && !out.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether {@code refs/heads/<branch>} exists on the origin remote.
     * Returns false on any failure (offline, no remote, etc.) — the
     * caller treats that the same as \"doesn't exist on origin\" and
     * creates the branch locally from the current HEAD.
     */
    private static boolean originBranchExists(File dir, String branch) {
        try {
            String out = ReleaseSupport.execCapture(dir,
                    "git", "ls-remote", "--heads", "origin", branch);
            return out != null && !out.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read declared branches from {@code workspace.yaml} and run
     * {@code git checkout} in each subproject whose current branch
     * differs. Subprojects with uncommitted changes are skipped unless
     * {@code -Dforce=true}.
     */
    private BranchChanges alignBranchesFromManifest(WorkspaceGraph graph,
                                          File root, boolean draft) {
        int switched = 0;
        int skippedDirty = 0;
        int planned = 0;
        List<String> changes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            String declared = subproject.branch();
            if (declared == null) continue;
            String actual = gitBranch(dir);
            if (actual.equals(declared)) continue;

            String status = gitStatus(dir);
            if (!status.isEmpty() && !force) {
                getLog().warn("  ⚠ " + name + ": uncommitted changes — skipping"
                        + " (pass -Dforce=true to override)");
                skipped.add("`" + name + "` — uncommitted (use -Dforce=true)");
                skippedDirty++;
                continue;
            }

            planned++;
            changes.add("`" + name + "` (checkout): `" + actual
                    + "` → `" + declared + "`");
            getLog().info("  branch: " + name + ": " + actual
                    + " → " + declared + (draft ? " (draft)" : ""));

            if (!draft) {
                ReleaseSupport.exec(dir, getLog(),
                        "git", "checkout", declared);
                switched++;
            }
        }

        if (switched == 0 && skippedDirty == 0 && planned == 0) {
            getLog().info("  Branches: repos already match yaml  ✓");
        } else if (draft) {
            getLog().info("  Branches: " + planned
                    + " subproject(s) would be switched");
        } else {
            getLog().info("  Branches: switched " + switched
                    + ", skipped " + skippedDirty + " (uncommitted)");
        }

        return new BranchChanges(changes, skipped);
    }
}
