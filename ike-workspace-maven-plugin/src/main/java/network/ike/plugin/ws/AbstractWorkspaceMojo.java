package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.di.Inject;

import java.io.File;
import java.nio.file.Path;

/**
 * Base class for workspace goals that read {@code workspace.yaml}.
 *
 * <p>Resolves the manifest by searching upward from the invocation
 * directory for a file named {@code workspace.yaml}. All workspace
 * goals inherit this resolution logic.
 */
abstract class AbstractWorkspaceMojo implements Mojo {

    /**
     * Maven logger, injected by the Maven 4 DI container.
     */
    @Inject
    private Log log;

    /**
     * Path to workspace.yaml. If not set, searches upward from the
     * current directory. Package-private for test access.
     */
    @Parameter(property = "workspace.manifest")
    File manifest;

    /**
     * Access the Maven logger.
     *
     * @return the logger instance
     */
    protected Log getLog() {
        return log;
    }

    /**
     * Replace the logger (used by {@link #startReport()} to install
     * a capturing wrapper).
     *
     * @param log the replacement logger
     */
    protected void setLog(Log log) {
        this.log = log;
    }

    /**
     * Load the manifest and build the workspace graph.
     *
     * @return the workspace dependency graph
     * @throws MojoException if the manifest cannot be read
     */
    protected WorkspaceGraph loadGraph() {
        Path manifestPath = resolveManifest();
        getLog().debug("Reading manifest: " + manifestPath);
        try {
            Manifest m = ManifestReader.read(manifestPath);
            return new WorkspaceGraph(m);
        } catch (ManifestException e) {
            throw new MojoException(
                    "Failed to read workspace manifest: " + e.getMessage(), e);
        }
    }

    /**
     * Resolve the manifest path — explicit parameter, or search upward.
     *
     * @return path to the workspace manifest file
     * @throws MojoException if the manifest cannot be found
     */
    protected Path resolveManifest() {
        if (manifest != null && manifest.exists()) {
            return manifest.toPath();
        }

        // Search upward from current directory
        Path dir = Path.of(System.getProperty("user.dir"));
        while (dir != null) {
            Path candidate = dir.resolve("workspace.yaml");
            if (candidate.toFile().exists()) {
                return candidate;
            }
            dir = dir.getParent();
        }

        throw new MojoException(
                "Cannot find workspace.yaml. Specify -Dworkspace.manifest=<path> "
                        + "or run from within a workspace directory.");
    }

    /**
     * Resolve the workspace root directory (parent of workspace.yaml).
     *
     * @return the workspace root directory
     * @throws MojoException if the manifest cannot be found
     */
    protected File workspaceRoot() {
        return resolveManifest().getParent().toFile();
    }

    /**
     * Run {@code git status --porcelain} on a subproject directory and
     * return the output (empty string = clean).
     *
     * @param subprojectDir the subproject directory to check
     * @return git status output, empty if clean
     */
    protected String gitStatus(File subprojectDir) {
        try {
            return ReleaseSupport.execCapture(subprojectDir,
                    "git", "status", "--porcelain");
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Get the current branch of a subproject directory.
     *
     * @param subprojectDir the subproject directory to check
     * @return the current branch name
     */
    protected String gitBranch(File subprojectDir) {
        try {
            return ReleaseSupport.execCapture(subprojectDir,
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Get the short SHA of HEAD for a subproject directory.
     *
     * @param subprojectDir the subproject directory to check
     * @return the short SHA of HEAD
     */
    protected String gitShortSha(File subprojectDir) {
        try {
            return ReleaseSupport.execCapture(subprojectDir,
                    "git", "rev-parse", "--short", "HEAD");
        } catch (Exception e) {
            return "???????";
        }
    }

    /**
     * Check whether a workspace.yaml exists in the directory hierarchy.
     * Does not throw — returns false if no manifest is found.
     *
     * @return true if running inside a workspace, false for a bare repo
     */
    protected boolean isWorkspaceMode() {
        try {
            resolveManifest();
            return true;
        } catch (MojoException e) {
            return false;
        }
    }

    /**
     * Prompt the user interactively for a required parameter when it
     * was not supplied on the command line.
     *
     * <p>Uses {@link System#console()} so that IntelliJ run configs
     * and terminal sessions can provide values without requiring
     * placeholder {@code -D} properties. Falls back to a clear error
     * message when running non-interactively (e.g., piped input).
     *
     * @param currentValue the value from the {@code @Parameter} field (may be null)
     * @param propertyName the {@code -D} property name (for the error message)
     * @param promptLabel  human-readable label shown in the prompt
     * @return the resolved value — either the original or user-supplied
     * @throws MojoException if no value can be obtained
     */
    protected String requireParam(String currentValue, String propertyName,
                                  String promptLabel) {
        if (currentValue != null && !currentValue.isBlank()) {
            return currentValue.trim();
        }

        // Try System.console() first (real terminal)
        java.io.Console console = System.console();
        if (console != null) {
            String input = console.readLine(Ansi.YELLOW + "%s: " + Ansi.RESET, promptLabel);
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        } else {
            // IntelliJ's Maven runner spawns Maven as a child process
            // with stdin/stdout wired to the Run console panel. System.console()
            // is null (not a real terminal), but System.in is connected and
            // interactive — the same mechanism the Plexus Prompter uses.
            // Use getLog().info() for the prompt so IntelliJ renders it
            // through the Maven logger (no [stdout] prefix).
            getLog().info(Ansi.yellow(promptLabel + ": "));
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in));
                String input = reader.readLine();
                if (input != null && !input.isBlank()) {
                    return input.trim();
                }
            } catch (java.io.IOException e) {
                // Fall through to error
            }
        }

        throw new MojoException(
                propertyName + " is required. Specify -D" + propertyName
                        + "=<value> or run interactively.");
    }

    /**
     * Read the workspace name from the root POM's artifactId.
     * Falls back to "Workspace" if the POM cannot be read.
     *
     * @return the workspace name derived from the root POM artifactId
     */
    protected String workspaceName() {
        try {
            File rootPom = new File(workspaceRoot(), "pom.xml");
            if (rootPom.exists()) {
                return ReleaseSupport.readPomArtifactId(rootPom);
            }
        } catch (Exception e) {
            // Fall through
        }
        return "Workspace";
    }

    /**
     * Format a goal header line using the workspace name.
     * Example: "komet-ws — Status"
     *
     * @param goalName the goal name to display in the header
     * @return the formatted header string
     */
    protected String header(String goalName) {
        return workspaceName() + " — " + goalName;
    }

    /**
     * Write a goal's report to its per-goal file at the workspace root.
     *
     * <p>Overwrites any previous content for this goal. Filenames use
     * {@code ꞉} (U+A789) to cluster as {@code ws꞉goal-name.md} in IDE file
     * browsers. The nearest {@code .gitignore} is self-healed to include
     * {@code ws꞉*.md} so reports never land in git.
     *
     * @param goal    the goal whose output is being reported
     * @param content markdown content to write
     */
    protected void writeReport(WsGoal goal, String content) {
        try {
            WorkspaceReport.write(
                    workspaceRoot().toPath(), goal.qualified(), content, getLog());
        } catch (MojoException e) {
            getLog().debug("Could not resolve workspace root for report: "
                    + e.getMessage());
        }
    }

    /**
     * Start capturing info-level log output for the workspace report.
     * Replaces the Mojo's logger with a tee that captures info output.
     * Call {@link #finishReport(WsGoal, ReportLog)} at the end of
     * {@code execute()} to write the captured output to the report file.
     *
     * @return a ReportLog that wraps the original logger and captures info output
     */
    protected ReportLog startReport() {
        ReportLog report = new ReportLog(getLog());
        setLog(report);
        return report;
    }

    /**
     * Write captured log output to the workspace report file.
     *
     * @param goal      the goal whose output is being reported
     * @param reportLog the ReportLog from {@link #startReport()}
     */
    protected void finishReport(WsGoal goal, ReportLog reportLog) {
        String content = reportLog.captured();
        if (!content.isBlank()) {
            writeReport(goal, content);
        }
    }
}
