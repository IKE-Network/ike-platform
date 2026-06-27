package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.Cohort;
import network.ike.workspace.CohortResolver;
import network.ike.workspace.FeatureName;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.MavenVersion;
import network.ike.workspace.SubprojectName;
import network.ike.workspace.WorkingSet;
import network.ike.workspace.WorkingSetResolver;
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
     * Escape hatch for the {@link TreePreflight#REQUIRE_UNMODIFIED} preflight
     * (#780): when {@code true}, a goal that would otherwise refuse to run on a
     * working tree with uncommitted changes proceeds anyway. For the documented
     * roll-forward (interrupted release) and fold-into-feature cases.
     *
     * <p>Inert until the per-goal preflight wiring lands (the #780 migration
     * slice); declared here so every goal inherits the same flag.
     */
    @Parameter(property = "allow-uncommitted", defaultValue = "false")
    boolean allowUncommitted;

    /**
     * Access the Maven logger.
     *
     * @return the logger instance
     */
    protected Log getLog() {
        return log;
    }

    /**
     * Whether the {@code -Dallow-uncommitted} escape hatch is set — bypasses a
     * {@link TreePreflight#REQUIRE_UNMODIFIED} preflight (#780).
     *
     * @return {@code true} to proceed despite an uncommitted working tree
     */
    protected boolean allowUncommitted() {
        return allowUncommitted;
    }

    /**
     * Access the Maven session injected by Maven 4's plugin DI.
     *
     * @return the injected session (may be {@code null} in unit tests)
     */
    protected Session getSession() {
        return session;
    }

    /**
     * Replace the logger. Used when a mojo is constructed directly
     * (not via Maven's DI container) and so never had a logger
     * injected — {@link WsSyncMojo} drives {@link PullWorkspaceMojo}
     * and {@link PushMojo} instances it created itself.
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
            // No session (unit tests) or batch mode -> not interactive,
            // so the prompter declines rather than blocking on stdin.
            boolean interactive = session != null
                    && session.getSettings().isInteractiveMode();
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
     * Resolve the {@link WorkingSet} this goal acts on, honoring the
     * {@code -Dworkspace.manifest} override and the
     * workspace-vs-single-repo decision in one place
     * (IKE-Network/ike-issues#611). When a {@code workspace.yaml} is
     * configured or found by searching upward, the working set is that
     * workspace's subprojects plus the root; otherwise it is the current
     * repository — a working set of one. This is the single home for the
     * {@code isWorkspaceMode()} + bare-mode branch the working-tree goals
     * otherwise each carry, delegating to the shared
     * {@link WorkingSetResolver}.
     *
     * @return the resolved working set (never {@code null})
     */
    protected WorkingSet resolveWorkingSet() {
        File configured = this.manifest;
        Path startDir = (configured != null && configured.exists())
                ? configured.getAbsoluteFile().toPath().getParent()
                : Path.of(System.getProperty("user.dir"));
        return WorkingSetResolver.resolve(startDir);
    }

    /**
     * Resolve the {@link Cohort} an artifact / release-style goal acts on,
     * honoring {@code -Dworkspace.manifest} (IKE-Network/ike-issues#612).
     * When a {@code workspace.yaml} is configured or found by searching
     * upward, the cohort is its subprojects in topological order (the
     * aggregator root excluded); otherwise it is the current repository — a
     * cohort of one. The dependency-ordered counterpart to
     * {@link #resolveWorkingSet()}, delegating to the shared
     * {@link CohortResolver}.
     *
     * @return the resolved cohort (never {@code null})
     */
    protected Cohort resolveCohort() {
        File configured = this.manifest;
        Path startDir = (configured != null && configured.exists())
                ? configured.getAbsoluteFile().toPath().getParent()
                : Path.of(System.getProperty("user.dir"));
        return CohortResolver.resolve(startDir);
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
     * Run the goal and write its report.
     *
     * <p>This method is {@code final}: every {@code ws:*} goal follows
     * the same template — do the work, then write exactly one report.
     * Subclasses supply the work and the report content by implementing
     * {@link #runGoal()}; they cannot override {@code execute()} to skip
     * the report. That is what makes report-writing structural — a goal
     * that compiles necessarily writes a report, so the #407 bug class
     * (a goal runs fine but silently writes none) becomes
     * compiler-impossible (IKE-Network/ike-issues#413).
     *
     * <p>The report lands in its per-goal file at the workspace root
     * ({@code ws꞉goal-name.md}); {@link WorkspaceReport} self-heals the
     * nearest {@code .gitignore} so reports never land in git. A
     * {@code -publish} run does not delete the matching {@code -draft}
     * report — both are timestamped history (ike-issues#413).
     *
     * @throws MojoException if the goal fails
     */
    @Override
    public final void execute() throws MojoException {
        WorkspaceReportSpec report = runGoal();
        try {
            WorkspaceReport.write(workspaceRoot().toPath(),
                    report.goal().qualified(), report.content(), getLog());
        } catch (MojoException e) {
            getLog().debug("Could not resolve workspace root for report: "
                    + e.getMessage());
        }
    }

    /**
     * Run this goal's work and return the report it produced.
     *
     * <p>Implementations do the goal's actual work here and return a
     * {@link WorkspaceReportSpec} — the goal identity and the Markdown
     * body. The base class resolves the workspace root and writes the
     * file. A goal cannot be implemented without producing a report,
     * which is the structural fix for the missing-report bug class
     * (IKE-Network/ike-issues#413 / #407).
     *
     * <p>On failure, throw {@link MojoException} as usual — a failed
     * goal produces no report, and Maven surfaces the exception.
     *
     * @return the report this goal produced (never {@code null})
     * @throws MojoException if the goal fails
     */
    protected abstract WorkspaceReportSpec runGoal() throws MojoException;
}
