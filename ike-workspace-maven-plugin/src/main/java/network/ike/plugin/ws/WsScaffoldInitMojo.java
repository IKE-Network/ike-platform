package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.bootstrap.SubprojectInitializer;
import network.ike.plugin.ws.bootstrap.WorkspaceBootstrap;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bootstrap a workspace: create it from scratch when there is no
 * {@code workspace.yaml}, otherwise clone any declared-but-missing
 * subprojects.
 *
 * <p>This goal subsumes the retired {@code ws:create} and
 * {@code ws:init} goals (IKE-Network/ike-issues#393) into a single
 * idempotent entry point that "does the right thing" regardless of
 * the current state of the directory:
 *
 * <ul>
 *   <li><b>No {@code workspace.yaml} in CWD</b> — bootstrap mode. Generates
 *       {@code pom.xml}, {@code workspace.yaml}, {@code .gitignore},
 *       {@code .mvn/maven.config}, {@code .mvn/jvm.config},
 *       {@code README.adoc}, and installs the Maven wrapper. Optionally
 *       initializes git. Required: {@code -Dgroup=<groupId>}.</li>
 *   <li><b>{@code workspace.yaml} present</b> — init mode. Walks every
 *       declared subproject and ensures it is cloned (or initialized in
 *       place from a Syncthing-synced working tree, or fetched + rebased
 *       if already a healthy clone). Generates workspace-level docs
 *       ({@code GOALS.md}, {@code WS-REFERENCE.md},
 *       {@code CLAUDE.md}).</li>
 *   <li><b>Both states satisfied</b> — every declared subproject already
 *       a clean clone, every wrapper at the right version — the goal
 *       no-ops with a single "all good" line.</li>
 * </ul>
 *
 * <p>Unlike most {@code ws:*} goals this mojo {@code implements Mojo}
 * directly rather than extending {@link AbstractWorkspaceMojo}, because
 * the bootstrap branch must run <em>before</em> a manifest exists. The
 * init branch reads {@code workspace.yaml} inline.
 *
 * <pre>{@code
 * # Bootstrap a new workspace:
 * mkdir my-ws && cd my-ws
 * mvn ws:scaffold-init -Dname=my-ws -Dgroup=org.example
 *
 * # Inside an existing workspace, clone any missing subprojects:
 * mvn ws:scaffold-init
 * }</pre>
 *
 * @see WorkspaceBootstrap for the create-from-scratch helper
 * @see SubprojectInitializer for the clone-missing-subprojects helper
 */
@org.apache.maven.api.plugin.annotations.Mojo(
        name = "scaffold-init",
        projectRequired = false,
        aggregator = true)
public class WsScaffoldInitMojo implements Mojo {

    /** Maven logger, injected by the Maven 4 DI container. */
    @Inject
    private Log log;

    /** Maven session — consulted for interactive mode (#385). */
    @Inject
    private Session session;

    /** Interactive prompter, lazily built (IKE-Network/ike-issues#385). */
    private IkePrompter prompter;

    /**
     * Workspace name. Used as the directory name (bootstrap mode), Maven
     * artifactId, and in generated documentation. Prompted if omitted
     * in bootstrap mode. Ignored in init mode.
     */
    @Parameter(property = "name")
    String name;

    /**
     * Short description of the workspace purpose. Defaults to the
     * workspace name, which is also used as the Maven POM {@code <name>}
     * element (shown in Maven build output). Bootstrap mode only.
     */
    @Parameter(property = "description")
    String description;

    /**
     * GitHub organization or user for the remote URL. If set, the
     * remote is configured as
     * {@code https://github.com/<org>/<name>.git}. Bootstrap mode only.
     */
    @Parameter(property = "org")
    String org;

    /**
     * Maven groupId for the workspace root POM. Required in bootstrap
     * mode (no default — placeholder GAVs are not supported, see
     * ike-issues#183). Persisted to {@code workspace-root.groupId} in
     * workspace.yaml.
     *
     * <p>Convention: {@code network.ike.workspace} for IKE-managed
     * workspaces; downstream consumers pick a groupId that fits their
     * organization namespace.
     */
    @Parameter(property = "group")
    String group;

    /**
     * Initial workspace root version. Single-segment monotonic (per
     * {@code feedback_no_semver_assumption}); defaults to
     * {@code 1-SNAPSHOT}. The version increments after each release of
     * the workspace root, NOT per software-meaningful change. Bootstrap
     * mode only.
     */
    @Parameter(property = "version", defaultValue = "1-SNAPSHOT")
    String version;

    /**
     * Maven artifactId for the workspace root POM. Defaults to
     * {@link #name} when omitted (ike-issues#183). Bootstrap mode only.
     */
    @Parameter(property = "artifactId")
    String artifactId;

    /**
     * Default Maven version for subprojects. Written to
     * {@code defaults.maven-version} in workspace.yaml. Bootstrap mode
     * only.
     */
    @Parameter(property = "mavenVersion", defaultValue = "4.0.0-rc-5")
    String mavenVersion;

    /**
     * Default branch for subprojects. Written to
     * {@code defaults.branch} in workspace.yaml. Bootstrap mode only.
     */
    @Parameter(property = "branch", defaultValue = "main")
    String defaultBranch;

    /**
     * Skip git init and remote setup in bootstrap mode.
     */
    @Parameter(property = "skipGit", defaultValue = "false")
    boolean skipGit;

    /**
     * Path to workspace.yaml override (init mode only — bypasses the
     * upward-from-CWD search).
     */
    @Parameter(property = "workspace.manifest")
    File manifest;

    /**
     * Reach each subproject's pinned {@code sha} with
     * {@code git reset --hard} and fail the build if a pin cannot be
     * reached (init mode only). Off by default: the interactive path uses
     * a lenient {@code git checkout} that never clobbers uncommitted work
     * and only warns on failure. Turn this on in CI/force runs so a stale
     * clone can never build silently-stale code
     * (IKE-Network/ike-issues#685).
     */
    @Parameter(property = "ws.scaffold.resetToPin", defaultValue = "false")
    boolean resetToPin;

    /** Creates this goal instance. */
    public WsScaffoldInitMojo() {}

    @Override
    public void execute() throws MojoException {
        Path here = Path.of(System.getProperty("user.dir"));
        Path manifestPath = (manifest != null && manifest.exists())
                ? manifest.toPath()
                : here.resolve("workspace.yaml");

        if (!Files.exists(manifestPath)) {
            // No workspace.yaml here — bootstrap a new workspace.
            createWorkspace(here);
        } else {
            // workspace.yaml exists — clone declared-but-missing subprojects
            // (and refresh wrappers / docs / CLAUDE.md).
            initSubprojects(manifestPath);
        }
    }

    // ── Bootstrap branch (was ws:create) ────────────────────────

    private void createWorkspace(Path here) throws MojoException {
        if (name == null || name.isBlank()) {
            name = promptParam("name", "Workspace name");
        }

        // -Dgroup is required for real coordinates (ike-issues#183).
        // The placeholder local.aggregate:<name>:1.0.0-SNAPSHOT path
        // is gone — no known workspace still uses it.
        if (group == null || group.isBlank()) {
            throw new MojoException(
                    WsGoal.SCAFFOLD_INIT.qualified()
                            + " in bootstrap mode requires -Dgroup=<groupId> "
                            + "(e.g. network.ike.workspace). The workspace root "
                            + "needs real Maven coordinates so "
                            + WsGoal.RELEASE_PUBLISH.qualified() + ", "
                            + WsGoal.ALIGN_PUBLISH.qualified()
                            + ", and site deploy can address it. "
                            + "See ike-issues#183.");
        }

        if (artifactId == null || artifactId.isBlank()) {
            artifactId = name;
        }

        // Validate name + artifactId via SubprojectName and version
        // via MavenVersion (#295) — single typed boundary for the
        // string parameters that flow into the generated POM. The
        // mojo doesn't extend AbstractWorkspaceMojo, so the helpers
        // can't be reused; inline the IAE → MojoException conversion.
        try {
            SubprojectName.of(name);
            SubprojectName.of(artifactId);
            MavenVersion.of(version);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage());
        }

        if (description == null || description.isBlank()) {
            description = name;
        }

        Path wsDir = here.resolve(name);

        // Fail if workspace files already exist (prevent silent overwrite)
        if (Files.exists(wsDir.resolve("pom.xml"))
                || Files.exists(wsDir.resolve("workspace.yaml"))) {
            throw new MojoException(
                    "Workspace already exists at " + wsDir
                    + " (pom.xml or workspace.yaml found). "
                    + "Remove the directory first or choose a different name.");
        }

        log.info("");
        log.info(name + " — Scaffold-Init (bootstrap)");
        log.info("══════════════════════════════════════════════════════════════");
        log.info("  Name:      " + name);
        log.info("  GAV:       " + group + ":" + artifactId + ":" + version);
        log.info("  Directory: " + wsDir);
        if (org != null && !org.isBlank()) {
            log.info("  Remote:    https://github.com/" + org + "/" + name + ".git");
        }
        log.info("");

        WorkspaceBootstrap.Params params = new WorkspaceBootstrap.Params(
                name, description, org, group, artifactId, version,
                mavenVersion, defaultBranch, skipGit,
                loadBuildProperty("ike-platform.version"),
                loadBuildProperty("ike-workspace-extension.version"));
        WorkspaceBootstrap bootstrap = new WorkspaceBootstrap(params, log);
        bootstrap.createAt(wsDir);

        log.info("");
        log.info(Ansi.green("  ✓ ") + "Workspace created: " + wsDir);
        log.info("");
        log.info(Ansi.yellow("  ⚠  You must change into the workspace directory before running ws: goals:"));
        log.info("");
        log.info("    " + Ansi.cyan("cd " + name));
        log.info("    mvn " + WsGoal.ADD.qualified()
                + " -Drepo=<git-url>    # add components");
        log.info("    mvn " + WsGoal.SCAFFOLD_INIT.qualified()
                + "           # clone components");
        log.info("");

        WorkspaceReport.write(wsDir, WsGoal.SCAFFOLD_INIT.qualified(),
                "Created workspace **" + name + "**\n\nDirectory: `" + wsDir + "`\n",
                log);
    }

    // ── Init branch (was ws:init) ───────────────────────────────

    private void initSubprojects(Path manifestPath) throws MojoException {
        log.debug("Reading manifest: " + manifestPath);
        Manifest m;
        try {
            m = ManifestReader.read(manifestPath);
        } catch (ManifestException e) {
            throw new MojoException(
                    "Failed to read workspace manifest: " + e.getMessage(), e);
        }
        WorkspaceGraph graph = new WorkspaceGraph(m);
        File root = manifestPath.getParent().toFile();
        String wsName = resolveWorkspaceName(root);

        // Refresh the managed comment header in workspace.yaml so the
        // bootstrap instructions stay in lockstep with current goal
        // names (#458). Best-effort — a failure here doesn't abort the
        // init since subproject cloning is the primary effect.
        try {
            String org = resolveGitHubOrg(root);
            boolean refreshed = WorkspaceBootstrap.refreshManagedHeader(
                    manifestPath, wsName, wsName, org);
            if (refreshed) {
                log.info(Ansi.green("  ✓ ") + "Refreshed workspace.yaml header.");
            }
        } catch (IOException e) {
            log.warn("Could not refresh workspace.yaml header: " + e.getMessage());
        }

        // Refresh the managed ike-workspace-extension entry in
        // .mvn/extensions.xml so the literal version stays in lockstep
        // with the ike-parent property (#460). Best-effort — a failure
        // here doesn't abort the init.
        try {
            Path extensionsXml = root.toPath().resolve(".mvn/extensions.xml");
            if (Files.exists(extensionsXml)) {
                String extVersion = loadBuildProperty("ike-workspace-extension.version");
                boolean refreshed = WorkspaceBootstrap
                        .refreshExtensionsManagedBlock(extensionsXml, extVersion);
                if (refreshed) {
                    log.info(Ansi.green("  ✓ ") + "Refreshed .mvn/extensions.xml ("
                            + extVersion + ").");
                }
            }
        } catch (IOException e) {
            log.warn("Could not refresh .mvn/extensions.xml: " + e.getMessage());
        }

        SubprojectInitializer initializer =
                new SubprojectInitializer(graph, root, wsName, log, resetToPin);
        SubprojectInitializer.Result result = initializer.run();

        if (result.nothingChanged()) {
            log.info(Ansi.green("  ✓ ") + "All " + result.alreadyClean()
                    + " declared subproject(s) already initialized.");
        }
    }

    /**
     * Parse the GitHub org from {@code git config --get remote.origin.url}
     * in the workspace root. Returns {@code null} when the URL is absent
     * or not a recognized GitHub form — callers fall back to the
     * {@code <org>} placeholder in the managed header.
     *
     * @param root the workspace root directory
     * @return the GitHub org, or {@code null} when not derivable
     */
    private static String resolveGitHubOrg(File root) {
        try {
            String url = ReleaseSupport.execCapture(root,
                    "git", "config", "--get", "remote.origin.url");
            if (url == null) return null;
            url = url.trim();
            // https://github.com/<org>/<repo>(.git)?
            int idx = url.indexOf("github.com/");
            if (idx >= 0) {
                String tail = url.substring(idx + "github.com/".length());
                int slash = tail.indexOf('/');
                if (slash > 0) return tail.substring(0, slash);
            }
            // git@github.com:<org>/<repo>(.git)?
            idx = url.indexOf("github.com:");
            if (idx >= 0) {
                String tail = url.substring(idx + "github.com:".length());
                int slash = tail.indexOf('/');
                if (slash > 0) return tail.substring(0, slash);
            }
        } catch (RuntimeException e) {
            // git not available or no remote — fall through.
        }
        return null;
    }

    /**
     * Read the workspace name from the root POM's artifactId. Mirrors
     * {@code AbstractWorkspaceMojo#workspaceName()} but is duplicated
     * here so this mojo can remain independent of that base class.
     */
    private static String resolveWorkspaceName(File root) {
        try {
            File rootPom = new File(root, "pom.xml");
            if (rootPom.exists()) {
                return ReleaseSupport.readPomArtifactId(rootPom);
            }
        } catch (Exception e) {
            // Fall through
        }
        return "Workspace";
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Load a build-time property from {@code ws-plugin.properties}
     * (resolved by Maven resource filtering during the build). Used to
     * pin the ike-parent version stamped into the generated POM.
     */
    private String loadBuildProperty(String key) {
        try (var is = getClass().getResourceAsStream("ws-plugin.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                String value = props.getProperty(key);
                if (value != null && !value.isBlank() && !value.startsWith("${")) {
                    return value;
                }
            }
        } catch (IOException e) {
            // Fall through to fallback
        }
        // Fallback: use JAR manifest version
        String jarVersion = getClass().getPackage().getImplementationVersion();
        return jarVersion != null ? jarVersion : "66";
    }

    private String promptParam(String propertyName, String label)
            throws MojoException {
        if (prompter == null) {
            // No session (unit tests) or batch mode -> not interactive,
            // so the prompter declines rather than blocking on stdin.
            boolean interactive = session != null
                    && session.getSettings().isInteractiveMode();
            prompter = new ConsoleIkePrompter(log, interactive);
        }
        if (prompter.isInteractive()) {
            String input = prompter.prompt(label + ": ");
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        }
        throw new MojoException(
                propertyName + " is required. Specify -D" + propertyName
                        + "=<value> or run interactively.");
    }
}
