package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Defensive git hook — warns when a branch is created or switched
 * outside the workspace tooling.
 *
 * <p>Intended to be called from a {@code post-checkout} git hook:
 * <pre>{@code
 * #!/bin/sh
 * mvn -q ws:check-branch -- "$@"
 * }</pre>
 *
 * <p>In workspace mode, compares the current branch to the expected
 * branch in workspace.yaml and warns on mismatch. Provides
 * copy-pasteable undo commands.
 *
 * <p>In bare mode (no workspace.yaml), silently exits — nothing to check.
 *
 * <p>Never blocks — warnings only. Always exits 0.
 */
@Mojo(name = "check-branch", projectRequired = false, aggregator = true)
public class CheckBranchMojo extends AbstractWorkspaceMojo {

    /**
     * Scope of the check.
     *
     * <ul>
     *   <li>{@code subproject} (default) — check the single subproject the
     *       CWD is inside, against its declared branch in
     *       {@code workspace.yaml}. Intended as a {@code post-checkout}
     *       git-hook gate.</li>
     *   <li>{@code workspace} — survey every subproject's on-disk branch
     *       and YAML {@code branch:} field against the workspace repo's
     *       HEAD (authoritative). Reports drift, suggests
     *       {@code ws:reconcile-branches-publish -Dfrom=workspace-head}.
     *       Read-only; never blocks (ike-issues#287).</li>
     * </ul>
     */
    @Parameter(property = "scope", defaultValue = "subproject")
    String scope;

    /** Creates this goal instance. */
    public CheckBranchMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (!isWorkspaceMode()) {
            return new WorkspaceReportSpec(WsGoal.CHECK_BRANCH,
                    "_Bare mode — not inside a workspace._\n");
        }

        if ("workspace".equals(scope)) {
            return executeWorkspaceScope();
        }
        if (!"subproject".equals(scope)) {
            throw new MojoException(
                    "Invalid scope '" + scope + "' — expected subproject|workspace");
        }

        WorkspaceGraph graph = loadGraph();
        File wsRoot = workspaceRoot();
        File cwd = new File(System.getProperty("user.dir"));

        // Determine which subproject we're in by matching CWD to workspace root + subproject name
        String subprojectName = findSubprojectName(wsRoot, cwd, graph);
        if (subprojectName == null) {
            return new WorkspaceReportSpec(WsGoal.CHECK_BRANCH,
                    "_CWD is not inside a known subproject directory._\n");
        }

        Subproject subproject = graph.manifest().subprojects().get(subprojectName);
        if (subproject == null || subproject.branch() == null) {
            return new WorkspaceReportSpec(WsGoal.CHECK_BRANCH,
                    "**Subproject:** `" + subprojectName + "`\n\n"
                            + "_No expected branch declared in workspace.yaml._\n");
        }

        String expectedBranch = subproject.branch();
        String actualBranch = gitBranch(cwd);

        if (actualBranch.equals(expectedBranch)) {
            return new WorkspaceReportSpec(WsGoal.CHECK_BRANCH,
                    "**Subproject:** `" + subprojectName + "`\n"
                            + "**Branch:** `" + actualBranch
                            + "` (matches expected)  ✓\n");
        }

        // Determine if this was a branch creation (new branch that doesn't match expected)
        boolean isNewBranch = !branchExistsRemotely(cwd, actualBranch);
        String scenario;

        if (isNewBranch && actualBranch.startsWith("feature/")) {
            // Created a feature branch directly — suggest ike:feature-start
            String featureName = actualBranch.substring("feature/".length());
            getLog().warn("");
            getLog().warn("\u26A0 You created branch '" + actualBranch + "' directly in " + subprojectName + ".");
            getLog().warn("");
            getLog().warn("  To fix:");
            getLog().warn("    git checkout " + expectedBranch);
            getLog().warn("    git branch -D " + actualBranch);
            getLog().warn("    mvn " + WsGoal.FEATURE_START_PUBLISH.qualified()
                    + " -Dfeature=" + featureName);
            getLog().warn("");
            getLog().warn("  " + WsGoal.FEATURE_START_PUBLISH.qualified()
                    + " creates aligned branches across all workspace");
            getLog().warn("  subprojects and sets version-qualified SNAPSHOTs.");
            getLog().warn("");
            scenario = "new feature branch created directly (should use "
                    + WsGoal.FEATURE_START_PUBLISH.qualified() + ")";
        } else if (isNewBranch) {
            // Created a non-feature branch directly
            getLog().warn("");
            getLog().warn("\u26A0 You created branch '" + actualBranch + "' directly in " + subprojectName + ".");
            getLog().warn("");
            getLog().warn("  The workspace expects branch '" + expectedBranch + "' for this subproject.");
            getLog().warn("");
            getLog().warn("  To undo:");
            getLog().warn("    git checkout " + expectedBranch);
            getLog().warn("    git branch -D " + actualBranch);
            getLog().warn("");
            scenario = "new non-feature branch created directly";
        } else {
            // Switched to an existing branch that doesn't match workspace.yaml
            getLog().warn("");
            getLog().warn("\u26A0 You switched to branch '" + actualBranch + "' in " + subprojectName + ".");
            getLog().warn("");
            getLog().warn("  The workspace expects branch '" + expectedBranch + "' for this subproject.");
            getLog().warn("  If this is intentional, update the workspace:");
            getLog().warn("    mvn " + WsGoal.SYNC.qualified());
            getLog().warn("");
            getLog().warn("  If not:");
            getLog().warn("    git checkout " + expectedBranch);
            getLog().warn("");
            scenario = "switched to an existing branch";
        }

        return new WorkspaceReportSpec(WsGoal.CHECK_BRANCH,
                "**Subproject:** `" + subprojectName + "`\n"
                        + "**Expected:** `" + expectedBranch + "`\n"
                        + "**Actual:** `" + actualBranch + "`\n"
                        + "**Scenario:** " + scenario + "\n");
    }

    /**
     * Workspace-wide branch coherence check. Reads the workspace repo's
     * current branch as the authoritative target, then surveys each
     * subproject — both its on-disk git branch and its
     * {@code workspace.yaml} {@code branch:} field — flagging any that
     * disagree.
     *
     * <p>Read-only. Never modifies state. Intended as a quick \"is my
     * workspace coherent?\" check before running
     * {@code ws:checkpoint} or {@code ws:feature-finish-*}, or to
     * confirm whether {@code ws:reconcile-branches-publish -Dfrom=workspace-head}
     * is needed (ike-issues#287).
     */
    private WorkspaceReportSpec executeWorkspaceScope() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File wsRoot = workspaceRoot();

        if (!new File(wsRoot, ".git").exists()) {
            return new WorkspaceReportSpec(WsGoal.CHECK_BRANCH,
                    "_Workspace directory has no `.git` — cannot determine "
                            + "the authoritative workspace branch._\n");
        }
        String wsBranch = gitBranch(wsRoot);
        if (wsBranch == null || "unknown".equals(wsBranch)) {
            return new WorkspaceReportSpec(WsGoal.CHECK_BRANCH,
                    "_Workspace HEAD is not on a named branch (detached HEAD?)._\n");
        }

        List<DriftRow> rows = new ArrayList<>();
        int driftCount = 0;
        for (Map.Entry<String, Subproject> entry : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File dir = new File(wsRoot, name);

            String declared = subproject.branch();
            String actual = new File(dir, ".git").exists()
                    ? gitBranch(dir) : null;

            boolean yamlDrift = declared == null || !declared.equals(wsBranch);
            boolean repoDrift = actual != null && !actual.equals(wsBranch);
            if (yamlDrift || repoDrift) driftCount++;

            rows.add(new DriftRow(name,
                    declared == null ? "(unset)" : declared,
                    actual == null ? "(not cloned)" : actual,
                    yamlDrift, repoDrift));
        }

        getLog().info("");
        getLog().info("IKE Workspace — Branch Coherence Check");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Workspace HEAD: " + wsBranch);
        getLog().info("");

        if (driftCount == 0) {
            getLog().info("  All subprojects agree with workspace HEAD  ✓");
        } else {
            for (DriftRow r : rows) {
                if (!r.yamlDrift && !r.repoDrift) continue;
                getLog().warn("  ⚠ " + r.name
                        + " — yaml=" + r.declared + " repo=" + r.actual);
            }
            getLog().warn("");
            getLog().warn("  Repair with:");
            getLog().warn("    mvn "
                    + WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified()
                    + " -Dfrom=workspace-head");
            getLog().warn("");
        }

        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Workspace HEAD:** `" + wsBranch + "`");
        report.paragraph(driftCount == 0
                ? "All subprojects agree with workspace HEAD."
                : driftCount + " subproject(s) drift from workspace HEAD.");

        List<String[]> driftTableRows = new ArrayList<>();
        for (DriftRow r : rows) {
            String status = (!r.yamlDrift && !r.repoDrift)
                    ? "✓ aligned"
                    : (r.yamlDrift && r.repoDrift
                            ? "✗ yaml + repo drift"
                            : (r.yamlDrift ? "✗ yaml drift" : "✗ repo drift"));
            driftTableRows.add(new String[]{r.name,
                    "`" + r.declared + "`", "`" + r.actual + "`", status});
        }
        report.table(List.of("Subproject", "YAML branch", "Repo branch", "Status"),
                driftTableRows);
        if (driftCount > 0) {
            report.paragraph("_Repair: `mvn "
                    + WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified()
                    + " -Dfrom=workspace-head`_");
        }
        return new WorkspaceReportSpec(WsGoal.CHECK_BRANCH, report.build());
    }

    /** One row of the workspace-scope drift table. */
    private record DriftRow(String name, String declared, String actual,
                            boolean yamlDrift, boolean repoDrift) {}

    /**
     * Find which workspace subproject the CWD belongs to.
     * Handles being in a subdirectory of a multi-module reactor.
     *
     * @param wsRoot workspace root directory
     * @param cwd    current working directory
     * @param graph  workspace dependency graph
     * @return the subproject name, or null if CWD is not inside a known subproject
     */
    static String findSubprojectName(File wsRoot, File cwd, WorkspaceGraph graph) {
        // Walk up from CWD toward wsRoot, checking each directory name
        Path wsPath = wsRoot.toPath().toAbsolutePath().normalize();
        Path cwdPath = cwd.toPath().toAbsolutePath().normalize();

        while (cwdPath != null && cwdPath.startsWith(wsPath) && !cwdPath.equals(wsPath)) {
            // The directory immediately under wsRoot is the subproject name
            Path relative = wsPath.relativize(cwdPath);
            String topDir = relative.getName(0).toString();
            if (graph.manifest().subprojects().containsKey(topDir)) {
                return topDir;
            }
            cwdPath = cwdPath.getParent();
        }
        return null;
    }

    /**
     * Check if a branch exists on the remote (origin).
     * Returns false if the check fails (no remote, offline, etc.).
     */
    private boolean branchExistsRemotely(File dir, String branch) {
        try {
            String result = ReleaseSupport.execCapture(dir,
                    "git", "ls-remote", "--heads", "origin", branch);
            return result != null && !result.isEmpty();
        } catch (Exception e) {
            return false; // Assume new if we can't check
        }
    }
}
