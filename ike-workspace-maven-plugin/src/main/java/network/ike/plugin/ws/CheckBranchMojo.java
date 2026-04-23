package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.nio.file.Path;

/**
 * Defensive git hook — warns when a branch is created or switched
 * outside the workspace tooling.
 *
 * <p>Intended to be called from a {@code post-checkout} git hook:
 * <pre>{@code
 * #!/bin/sh
 * mvn -q ike:check-branch -- "$@"
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
@Mojo(name = "check-branch", projectRequired = false)
public class CheckBranchMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public CheckBranchMojo() {}

    @Override
    public void execute() throws MojoException {
        if (!isWorkspaceMode()) {
            writeReport(WsGoal.CHECK_BRANCH,
                    "_Bare mode — not inside a workspace._\n");
            return;
        }

        WorkspaceGraph graph = loadGraph();
        File wsRoot = workspaceRoot();
        File cwd = new File(System.getProperty("user.dir"));

        // Determine which subproject we're in by matching CWD to workspace root + subproject name
        String subprojectName = findSubprojectName(wsRoot, cwd, graph);
        if (subprojectName == null) {
            writeReport(WsGoal.CHECK_BRANCH,
                    "_CWD is not inside a known subproject directory._\n");
            return;
        }

        Subproject subproject = graph.manifest().subprojects().get(subprojectName);
        if (subproject == null || subproject.branch() == null) {
            writeReport(WsGoal.CHECK_BRANCH,
                    "**Subproject:** `" + subprojectName + "`\n\n"
                            + "_No expected branch declared in workspace.yaml._\n");
            return;
        }

        String expectedBranch = subproject.branch();
        String actualBranch = gitBranch(cwd);

        if (actualBranch.equals(expectedBranch)) {
            writeReport(WsGoal.CHECK_BRANCH,
                    "**Subproject:** `" + subprojectName + "`\n"
                            + "**Branch:** `" + actualBranch
                            + "` (matches expected)  ✓\n");
            return;
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
            getLog().warn("    mvn ike:feature-start -Dfeature=" + featureName);
            getLog().warn("");
            getLog().warn("  ike:feature-start creates aligned branches across all workspace");
            getLog().warn("  subprojects and sets version-qualified SNAPSHOTs.");
            getLog().warn("");
            scenario = "new feature branch created directly (should use ws:feature-start)";
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
            getLog().warn("    mvn ike:ws-sync");
            getLog().warn("");
            getLog().warn("  If not:");
            getLog().warn("    git checkout " + expectedBranch);
            getLog().warn("");
            scenario = "switched to an existing branch";
        }

        writeReport(WsGoal.CHECK_BRANCH,
                "**Subproject:** `" + subprojectName + "`\n"
                        + "**Expected:** `" + expectedBranch + "`\n"
                        + "**Actual:** `" + actualBranch + "`\n"
                        + "**Scenario:** " + scenario + "\n");
    }

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
