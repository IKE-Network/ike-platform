package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.FeatureName;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.MavenVersion;
import network.ike.workspace.SubprojectName;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.support.ConsoleIkePrompter;
import network.ike.plugin.support.IkePrompter;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

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
     * Maven session, injected by the DI container — consulted for
     * {@code interactiveMode} when building the {@link IkePrompter}.
     */
    @Inject
    private Session session;

    /**
     * Interactive-prompt abstraction (IKE-Network/ike-issues#385).
     * Lazily built by {@link #getPrompter()} from the session's
     * interactive flag; package-private setter injects a
     * {@link network.ike.plugin.support.ScriptedIkePrompter} in tests.
     */
    private IkePrompter prompter;

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
     * Inject an {@link IkePrompter} (typically a
     * {@link network.ike.plugin.support.ScriptedIkePrompter}) for
     * tests. Production code lets {@link #getPrompter()} build one.
     *
     * @param prompter the prompter implementation to use
     */
    void setPrompter(IkePrompter prompter) {
        this.prompter = prompter;
    }

    /**
     * The {@link IkePrompter} for this goal — built lazily from the
     * session's interactive flag, or the test-injected instance.
     * Callers that pass it to a static helper (e.g.
     * {@link FeatureFinishSupport#promptStaleBranchCleanup}) use this.
     *
     * @return the prompter (never {@code null})
     */
    protected IkePrompter getPrompter() {
        if (prompter == null) {
            boolean interactive = session == null
                    || session.getSettings().isInteractiveMode();
            prompter = new ConsoleIkePrompter(getLog(), interactive);
        }
        return prompter;
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
     * <p>Delegates to the {@link IkePrompter} (IKE-Network/ike-issues#385):
     * an inline prompt on a real terminal, an own-line prompt in a
     * piped IDE runner. In batch mode it throws a clear error
     * directing the user to pass the property explicitly.
     *
     * @param currentValue the value from the {@code @Parameter} field (may be null)
     * @param propertyName the {@code -D} property name (for the error message)
     * @param promptLabel  human-readable label shown in the prompt;
     *                     callers pass it without trailing punctuation
     *                     (a {@code ": "} separator is appended here)
     * @return the resolved value — either the original or user-supplied
     * @throws MojoException if no value can be obtained
     */
    protected String requireParam(String currentValue, String propertyName,
                                  String promptLabel) {
        if (currentValue != null && !currentValue.isBlank()) {
            return currentValue.trim();
        }
        IkePrompter p = getPrompter();
        if (p.isInteractive()) {
            String input = p.prompt(promptLabel + ": ");
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        }
        throw new MojoException(
                propertyName + " is required. Specify -D" + propertyName
                        + "=<value> or run interactively.");
    }

    /**
     * Validate a feature-name string with {@link FeatureName#of} and
     * surface any rule violation as a clean {@link MojoException} (the
     * raw {@code IllegalArgumentException} would otherwise bubble up
     * as a Maven internal error). Use this anywhere a feature name
     * leaves the {@code -Dfeature=} command-line boundary
     * (ike-issues#205).
     *
     * @param feature the candidate feature name (must already be
     *                resolved — i.e. non-null after
     *                {@link #requireParam} or auto-detection)
     * @return the validated {@link FeatureName} value
     * @throws MojoException if {@code feature} fails the
     *                       {@code FeatureName} syntax rules
     */
    protected static FeatureName validateFeatureName(String feature) {
        try {
            return FeatureName.of(feature);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }
    }

    /**
     * Validate a subproject-name string with {@link SubprojectName#of}
     * and surface any rule violation as a {@link MojoException}
     * (ike-issues#295).
     *
     * @param subproject the candidate subproject name (already
     *                   resolved — non-null after
     *                   {@link #requireParam} or POM derivation)
     * @return the validated {@link SubprojectName} value
     * @throws MojoException if {@code subproject} fails the
     *                       {@code SubprojectName} syntax rules
     */
    protected static SubprojectName validateSubprojectName(String subproject) {
        try {
            return SubprojectName.of(subproject);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }
    }

    /**
     * Validate a Maven version string with {@link MavenVersion#of}
     * and surface any rule violation as a {@link MojoException}
     * (ike-issues#295). Per
     * {@code feedback_no_semver_assumption} the validator accepts
     * single-segment monotonic, semver-like, calendar-based, and
     * branch-qualified versions; it does not enforce semver.
     *
     * @param version the candidate version string
     * @return the validated {@link MavenVersion} value
     * @throws MojoException if {@code version} fails the
     *                       {@code MavenVersion} syntax rules
     */
    protected static MavenVersion validateMavenVersion(String version) {
        try {
            return MavenVersion.of(version);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }
    }

    /**
     * Prompt the user with a yes/no question, accepting "y"/"yes"/"n"/"no"
     * (case-insensitive). When invoked in a non-interactive context, the
     * default is used.
     *
     * @param label      the question to display (without trailing punctuation)
     * @param defaultYes whether {@code true} (yes) is the default
     * @return {@code true} for yes, {@code false} for no
     * @throws MojoException if no answer can be obtained
     */
    protected boolean confirm(String label, boolean defaultYes) {
        return getPrompter().confirm(label, defaultYes);
    }

    /**
     * Prompt the user to pick from a numbered list. Returns the chosen
     * option, or {@code null} when the list is empty.
     *
     * @param label   prompt header (printed via the Prompter as a message)
     * @param options ordered list of choices
     * @return the chosen option, or {@code null} if {@code options} is empty
     * @throws MojoException if no valid choice can be obtained
     */
    protected String selectFromList(String label, List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        String chosen = getPrompter().select(label, options);
        if (chosen == null) {
            throw new MojoException(
                    "Could not read a valid selection for: " + label);
        }
        return chosen;
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
            java.nio.file.Path root = workspaceRoot().toPath();
            WorkspaceReport.write(root, goal.qualified(), content, getLog());
            // After a successful -publish, the matching -draft report is
            // now stale — it described what WOULD happen, but it has
            // happened. Leaving both files alongside each other misleads
            // readers (ike-issues#300). Best-effort delete; debug-log on
            // failure.
            if (goal.isPublish()) {
                goal.pair().ifPresent(draft ->
                        WorkspaceReport.deleteReport(
                                root, draft.qualified(), getLog()));
            }
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
