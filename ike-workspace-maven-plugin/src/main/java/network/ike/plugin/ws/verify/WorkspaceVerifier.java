package network.ike.plugin.ws.verify;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.Ansi;
import network.ike.plugin.ws.PomParentSupport;
import network.ike.plugin.ws.WsGoal;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import network.ike.workspace.BomAnalysis;
import network.ike.workspace.DependencyConvergenceAnalysis;
import network.ike.workspace.DependencyConvergenceAnalysis.Divergence;
import network.ike.workspace.DependencyTreeParser;
import network.ike.workspace.DependencyTreeParser.ResolvedDependency;
import network.ike.workspace.PublishedArtifactSet;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Workspace-wide verification — the read-only logic formerly in
 * the retired {@code ws:verify} goal (IKE-Network/ike-issues#393).
 * Now invoked by {@code ws:scaffold-draft} as part of its drift
 * report.
 *
 * <p>Each {@code verifyXxx} method translates one check from the
 * original mojo. The {@code verifyParentAlignment()} check was
 * intentionally dropped during the extraction: parent drift is now
 * detected by {@code ParentVersionReconciler.detect()} (one of the
 * workspace-level reconcilers run by {@code ws:scaffold-draft})
 * and would otherwise duplicate that warning.
 */
public final class WorkspaceVerifier {

    private final Log log;
    private final WorkspaceGraph graph;
    private final File root;
    private final Path manifestPath;
    private final boolean checkConvergence;
    private final boolean workspaceMode;
    private final List<String[]> rows = new ArrayList<>();

    /**
     * Construct a verifier bound to a single workspace.
     *
     * @param log              Maven logger for streaming check output
     * @param graph            the workspace graph (already loaded by caller)
     * @param root             workspace root directory
     * @param manifestPath     path to {@code workspace.yaml}
     * @param checkConvergence run the slow transitive-convergence check
     * @param workspaceMode    {@code true} when running inside a
     *                         workspace; {@code false} for a bare repo
     */
    public WorkspaceVerifier(Log log, WorkspaceGraph graph, File root,
                             Path manifestPath, boolean checkConvergence,
                             boolean workspaceMode) {
        this.log = log;
        this.graph = graph;
        this.root = root;
        this.manifestPath = manifestPath;
        this.checkConvergence = checkConvergence;
        this.workspaceMode = workspaceMode;
    }

    /**
     * Run every verification check; returns the row data the caller
     * uses to render its markdown report. Also logs check progress
     * and outcomes to the {@link Log} passed in the constructor.
     *
     * @return per-check {@code {label, status}} rows
     * @throws MojoException if a verification step fails irrecoverably
     */
    public List<String[]> runAllChecks() throws MojoException {
        log.info("");
        log.info(header("Verification"));
        log.info("══════════════════════════════════════════════════════════════");

        if (workspaceMode) {
            verifyWorkspaceManifest();
            // Parent alignment intentionally omitted — superseded by
            // ParentVersionReconciler.detect() (#393).
            verifyParentCoherence();
            verifyBomCascade();
            if (checkConvergence) {
                verifyDependencyConvergence();
            }
            verifyWorkspaceVcs();
        } else {
            verifyBareVcs();
        }

        verifyEnvironment();
        log.info("");
        return rows;
    }

    // ── Workspace manifest verification ───────────────────────────

    private void verifyWorkspaceManifest() throws MojoException {
        List<String> errors = graph.verify();

        int subprojectCount = graph.manifest().subprojects().size();

        log.info("  Components:      " + subprojectCount);
        log.info("");

        if (errors.isEmpty()) {
            log.info(Ansi.green("  Manifest:    consistent  ✓"));
            rows.add(new String[]{"Manifest", "consistent ✓"});
        } else {
            log.error("  Manifest:    " + errors.size() + " error(s)");
            for (String error : errors) {
                log.error("    ✗ " + error);
            }
            rows.add(new String[]{"Manifest",
                    errors.size() + " error(s)"});
        }
    }

    // ── Parent coherence — same GA as workspace's parent ─────────
    //
    // ike-issues#324: when a subproject's <parent> declares the SAME
    // groupId+artifactId as the workspace aggregator's own <parent>
    // (typically network.ike.platform:ike-parent for IKE-Network
    // workspaces), two policy rules apply:
    //
    //   1. Cycle prevention. The subproject must include an empty
    //      <relativePath/> in its <parent> block. Without it,
    //      Maven 4's model builder fails with "The parents form a
    //      cycle" — the subproject's parent lookup tries the
    //      filesystem first, finds an ike-parent at a different
    //      version, can't reconcile, and bails. Established
    //      precedent in komet workspaces; documented in MAVEN.md
    //      and IKE-WORKSPACE.md.
    //
    //   2. Version coherence. The subproject's <parent><version>
    //      must equal the workspace aggregator's. Version drift
    //      means subprojects inherit different pluginManagement +
    //      dependencyManagement matrices — a silent loss of
    //      consistency across the workspace.

    private void verifyParentCoherence() throws MojoException {
        Path workspacePom = root.toPath().resolve("pom.xml");
        if (!Files.exists(workspacePom)) {
            // Bare-VCS or unusual layout — nothing to enforce.
            return;
        }

        PomParentSupport.ParentInfo workspaceParent;
        try {
            workspaceParent = PomParentSupport.readParent(workspacePom);
        } catch (IOException e) {
            log.debug("  Could not read workspace parent: " + e.getMessage());
            return;
        }
        if (workspaceParent == null
                || workspaceParent.groupId() == null
                || workspaceParent.artifactId() == null) {
            // Workspace doesn't inherit a parent — no coherence to
            // enforce on subprojects.
            return;
        }

        int cycleRisk = 0;
        int versionDrift = 0;
        int coherent = 0;

        log.info("");

        for (Map.Entry<String, Subproject> entry :
                graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Path pomFile = root.toPath().resolve(name).resolve("pom.xml");
            if (!Files.exists(pomFile)) continue;

            PomParentSupport.ParentInfo subParent;
            try {
                subParent = PomParentSupport.readParent(pomFile);
            } catch (IOException e) {
                log.debug("  Could not read " + name + " parent: "
                        + e.getMessage());
                continue;
            }
            if (subParent == null) continue;

            // Decision matrix gate: subproject's parent GA must match
            // the workspace aggregator's parent GA. Otherwise this
            // subproject inherits a different parent and is out of
            // scope for these rules.
            if (!Objects.equals(workspaceParent.groupId(),
                            subParent.groupId())
                    || !Objects.equals(workspaceParent.artifactId(),
                            subParent.artifactId())) {
                continue;
            }

            String coordinates = subParent.groupId() + ":"
                    + subParent.artifactId();

            // Rule 2: version coherence
            if (!Objects.equals(workspaceParent.version(),
                    subParent.version())) {
                log.warn("  WARN: " + name + " parent "
                        + coordinates + ":" + subParent.version()
                        + " != workspace " + coordinates + ":"
                        + workspaceParent.version()
                        + " (#324 coherence violation)");
                versionDrift++;
                continue;
            }

            // Rule 1: cycle prevention — empty <relativePath/> required
            boolean hasEmptyRelativePath;
            try {
                hasEmptyRelativePath =
                        PomParentSupport.hasEmptyRelativePath(pomFile);
            } catch (IOException e) {
                log.debug("  Could not check relativePath for "
                        + name + ": " + e.getMessage());
                continue;
            }

            if (!hasEmptyRelativePath) {
                log.warn("  WARN: " + name + " parent "
                        + coordinates + ":" + subParent.version()
                        + " matches workspace parent but is missing "
                        + "empty <relativePath/> (#324 cycle prevention; "
                        + "add <relativePath/> inside the <parent> block)");
                cycleRisk++;
                continue;
            }

            coherent++;
        }

        int problems = cycleRisk + versionDrift;
        int total = problems + coherent;
        if (total == 0) {
            log.info("  Parent coherence: no subprojects share the "
                    + "workspace's parent GA");
            rows.add(new String[]{"Parent coherence", "n/a"});
        } else if (problems == 0) {
            log.info(Ansi.green("  Parent coherence: " + coherent
                    + " subproject(s) coherent  ✓"));
            rows.add(new String[]{"Parent coherence",
                    coherent + " coherent ✓"});
        } else {
            String summary = versionDrift + " version drift, "
                    + cycleRisk + " missing <relativePath/>";
            log.warn("  Parent coherence: " + summary);
            rows.add(new String[]{"Parent coherence", summary});
        }
    }

    // ── BOM cascade verification ──────────────────────────────────

    private void verifyBomCascade() throws MojoException {
        // Build published artifact sets for all subprojects
        Map<String, Set<PublishedArtifactSet.Artifact>> workspaceArtifacts =
                new LinkedHashMap<>();
        for (String name : graph.manifest().subprojects().keySet()) {
            Path subDir = root.toPath().resolve(name);
            if (Files.exists(subDir.resolve("pom.xml"))) {
                try {
                    workspaceArtifacts.put(name,
                            PublishedArtifactSet.scan(subDir));
                } catch (IOException e) {
                    log.debug("Could not scan " + name + ": " + e.getMessage());
                }
            }
        }

        try {
            List<BomAnalysis.CascadeIssue> issues = BomAnalysis.analyzeCascadeIssues(
                    root.toPath(), graph.manifest(), workspaceArtifacts);

            if (issues.isEmpty()) {
                log.info("");
                log.info("  BOM cascade: all dependency edges can cascade  ✓");
                rows.add(new String[]{"BOM cascade", "all edges cascade ✓"});
            } else {
                log.info("");
                log.warn("  BOM cascade: " + issues.size() + " gap(s) detected");
                rows.add(new String[]{"BOM cascade",
                        issues.size() + " gap(s)"});
                for (BomAnalysis.CascadeIssue issue : issues) {
                    log.warn("    " + issue.subprojectName() + " → "
                            + issue.dependsOn()
                            + ": no version-property or workspace BOM import");
                    if (!issue.externalBomPins().isEmpty()) {
                        for (BomAnalysis.BomImport bom : issue.externalBomPins()) {
                            log.warn("      external BOM: "
                                    + bom.groupId() + ":" + bom.artifactId()
                                    + ":" + bom.version()
                                    + " (may pin workspace artifact versions)");
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("  BOM cascade check failed: " + e.getMessage());
        }
    }

    // ── Dependency convergence check ───────────────────────────────

    private void verifyDependencyConvergence() throws MojoException {
        log.info("");
        log.info("  Dependency convergence (this may take a while)...");
        log.info("");

        File mvnExecutable = ReleaseSupport.resolveMavenWrapper(root, log);

        // Collect dependency trees per subproject in topological order
        List<String> order = graph.topologicalSort();
        Map<String, List<ResolvedDependency>> componentTrees =
                new LinkedHashMap<>();

        for (String name : order) {
            File subDir = new File(root, name);
            File pomFile = new File(subDir, "pom.xml");
            if (!pomFile.exists()) continue;

            log.info("    Resolving " + name + "...");
            try {
                String treeOutput = ReleaseSupport.execCapture(subDir,
                        mvnExecutable.getAbsolutePath(),
                        "dependency:tree", "-DoutputType=text",
                        "-B", "-q");
                List<ResolvedDependency> deps =
                        DependencyTreeParser.parse(treeOutput);
                if (!deps.isEmpty()) {
                    componentTrees.put(name, deps);
                }
            } catch (MojoException e) {
                log.warn(Ansi.yellow("    ⚠ ") + name + ": dependency:tree failed — "
                        + e.getMessage());
            }
        }

        if (componentTrees.size() < 2) {
            log.info("    Fewer than 2 components resolved — skipping analysis");
            return;
        }

        // Analyze
        List<Divergence> divergences =
                DependencyConvergenceAnalysis.analyze(componentTrees);

        if (divergences.isEmpty()) {
            log.info("");
            log.info("  Convergence: all shared dependencies converge across "
                    + componentTrees.size() + " components  ✓");
            rows.add(new String[]{"Convergence",
                    "all converge ✓ (" + componentTrees.size() + " components)"});
        } else {
            log.info("");
            rows.add(new String[]{"Convergence",
                    divergences.size() + " divergence(s)"});
            log.info("  Convergence: " + divergences.size()
                    + " artifact(s) diverge across "
                    + componentTrees.size() + " components");
            log.info("");

            for (Divergence d : divergences) {
                log.info("    " + d.coordinate());
                for (Map.Entry<String, List<String>> vEntry : d.versionToSubprojects().entrySet()) {
                    log.info("      " + vEntry.getKey() + " ← "
                            + String.join(", ", vEntry.getValue()));
                }
            }
        }
    }

    // ── Subproject git state (workspace mode) ─────────────────────

    private void verifyWorkspaceVcs() throws MojoException {
        log.info("");

        // Workspace repo itself
        if (VcsState.isIkeManaged(root.toPath())) {
            log.info("  Workspace");
            reportVcsState(root, "    ");
        }

        // Each subproject
        for (Map.Entry<String, Subproject> entry : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            File dir = new File(root, name);

            if (!new File(dir, ".git").exists()) {
                continue;
            }

            log.info("  " + name);

            if (!VcsState.isIkeManaged(dir.toPath())) {
                log.info("    Git state: freshly added (no workspace operations yet)");
                continue;
            }

            reportVcsState(dir, "    ");
        }
    }

    // ── Subproject git state (bare mode) ──────────────────────────

    private void verifyBareVcs() throws MojoException {
        File dir = new File(System.getProperty("user.dir"));
        String dirName = dir.getName();

        log.info("  Machine:     " + hostname());

        if (!VcsState.isIkeManaged(dir.toPath())) {
            log.info("  Git state:   freshly added (no workspace operations yet)");
            return;
        }

        log.info("");
        log.info("  " + dirName);
        reportVcsState(dir, "    ");
    }

    // ── Shared VCS state reporting ───────────────────────────────

    private void reportVcsState(File dir, String indent)
            throws MojoException {
        String localBranch = gitBranch(dir);
        String localSha = gitShortSha(dir);

        log.info(indent + "Branch:        " + localBranch);
        log.info(indent + "Local HEAD:    " + localSha);

        Optional<VcsState> stateOpt = VcsState.readFrom(dir.toPath());

        if (stateOpt.isEmpty()) {
            log.info(indent + "State file:    absent (first commit, or Syncthing not delivered)");
            log.info(indent + "Status:        no state file  ─");
            return;
        }

        VcsState state = stateOpt.get();
        log.info(indent + "State file:    " + state.action().label()
                + " by " + state.machine() + " at " + state.timestamp());
        log.info(indent + "State SHA:     " + state.sha());
        log.info(indent + "State branch:  " + state.branch());

        // In sync?
        boolean shaMatch = state.sha().equals(localSha);
        boolean branchMatch = state.branch().equals(localBranch);

        if (shaMatch && branchMatch) {
            log.info(indent + "Status:        in sync  ✓");
            return;
        }

        // Not in sync — diagnose based on action
        if (!branchMatch) {
            diagnoseBranchMismatch(dir, indent, state, localBranch);
        } else {
            diagnoseShaMismatch(dir, indent, state, localSha);
        }
    }

    private void diagnoseBranchMismatch(File dir, String indent,
                                         VcsState state, String localBranch) {
        switch (state.action()) {
            case FEATURE_START -> {
                log.warn(indent + "Status:        feature branch '"
                        + state.branch() + "' started on " + state.machine()
                        + " at " + state.timestamp());
                log.warn(indent + "               You are on '"
                        + localBranch + "'.");
                log.warn(indent + "Action:        run 'mvnw ws:switch -Dbranch="
                        + state.branch() + "' to follow the feature branch");
            }
            case FEATURE_FINISH -> {
                log.warn(indent + "Status:        feature finished on "
                        + state.machine() + " at " + state.timestamp()
                        + ", merged to '" + state.branch() + "'");
                log.warn(indent + "               You are on '"
                        + localBranch + "'.");
                log.warn(indent + "Action:        run 'mvnw ws:switch -Dbranch="
                        + state.branch() + "' to return to '"
                        + state.branch() + "'");
            }
            case SWITCH -> {
                log.warn(indent + "Status:        switched to '"
                        + state.branch() + "' on " + state.machine()
                        + " at " + state.timestamp());
                log.warn(indent + "               You are on '"
                        + localBranch + "'.");
                log.warn(indent + "Action:        run 'mvnw ws:switch -Dbranch="
                        + state.branch() + "' or 'mvnw "
                        + WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified()
                        + " -Dfrom=manifest'");
            }
            case COMMIT, PUSH, RELEASE, CHECKPOINT -> {
                log.warn(indent + "Status:        branch mismatch — local '"
                        + localBranch + "', state file '" + state.branch() + "'");
                log.warn(indent + "Action:        run 'mvnw ws:switch -Dbranch="
                        + state.branch() + "' to reconcile");
            }
        }
    }

    private void diagnoseShaMismatch(File dir, String indent,
                                      VcsState state, String localSha) {
        // Check if the state SHA exists on the remote
        Optional<String> remoteSha;
        try {
            remoteSha = VcsOperations.remoteSha(dir, "origin", state.branch());
        } catch (MojoException e) {
            remoteSha = Optional.empty();
        }

        boolean shaOnRemote = remoteSha.isPresent();

        switch (state.action()) {
            case COMMIT -> {
                if (shaOnRemote) {
                    log.warn(indent + "Status:        commit on "
                            + state.machine() + " at " + state.timestamp());
                    log.warn(indent + "Action:        run 'mvnw " + WsGoal.PULL.qualified() + "'");
                } else {
                    log.warn(indent + "Status:        commit on "
                            + state.machine() + " at " + state.timestamp()
                            + ", but push did not complete");
                    log.warn(indent + "Action:        push from "
                            + state.machine() + " first, then 'mvnw "
                            + WsGoal.PULL.qualified() + "' here");
                    log.warn(indent + "               Or: IKE_VCS_OVERRIDE=1 to proceed independently");
                }
            }
            case PUSH -> {
                log.warn(indent + "Status:        push from "
                        + state.machine() + " at " + state.timestamp());
                log.warn(indent + "               Local HEAD behind remote.");
                log.warn(indent + "Action:        run 'mvnw " + WsGoal.PULL.qualified() + "'");
            }
            case RELEASE -> {
                log.warn(indent + "Status:        release performed on "
                        + state.machine() + " at " + state.timestamp());
                log.warn(indent + "Action:        run 'mvnw " + WsGoal.PULL.qualified() + "'");
            }
            case CHECKPOINT -> {
                log.warn(indent + "Status:        checkpoint created on "
                        + state.machine() + " at " + state.timestamp());
                log.warn(indent + "Action:        run 'mvnw " + WsGoal.PULL.qualified() + "'");
            }
            case SWITCH -> {
                log.warn(indent + "Status:        switched on "
                        + state.machine() + " at " + state.timestamp());
                log.warn(indent + "Action:        run 'mvnw "
                        + WsGoal.RECONCILE_BRANCHES_PUBLISH.qualified()
                        + " -Dfrom=manifest'");
            }
            case FEATURE_START, FEATURE_FINISH -> {
                log.warn(indent + "Status:        behind ("
                        + state.action().label() + " on " + state.machine() + ")");
                log.warn(indent + "Action:        run 'mvnw " + WsGoal.PULL.qualified() + "'");
            }
        }
    }

    // ── Environment checks ──────────────────────────────────────

    private void verifyEnvironment() {
        File dir = new File(System.getProperty("user.dir"));

        log.info("");

        // Standards
        File standards = new File(dir, ".claude/standards");
        if (standards.isDirectory()) {
            log.info("  Standards:   .claude/standards/ present  ✓");
        } else {
            log.info("  Standards:   .claude/standards/ absent");
        }

        // CLAUDE.md
        File claudeMd = new File(dir, "CLAUDE.md");
        if (claudeMd.exists()) {
            log.info("  CLAUDE.md:   present  ✓");
        } else {
            log.info("  CLAUDE.md:   absent");
        }

        // Syncthing
        checkSyncthingHealth();
    }

    private void checkSyncthingHealth() {
        int port = 8384;

        // Check for custom port in .ike/config
        File dir = new File(System.getProperty("user.dir"));
        Path config = dir.toPath().resolve(".ike/config");
        if (Files.exists(config)) {
            try {
                Properties props = new Properties();
                props.load(new StringReader(
                        Files.readString(config, StandardCharsets.UTF_8)));
                String portStr = props.getProperty("syncthing.port");
                if (portStr != null) {
                    port = Integer.parseInt(portStr.trim());
                }
            } catch (Exception e) {
                log.debug("Could not read .ike/config: " + e.getMessage());
            }
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/rest/noauth/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("  Syncthing:   connected (port " + port + ")  ✓");
            } else {
                log.info("  Syncthing:   responded with status "
                        + response.statusCode());
            }
        } catch (Exception e) {
            log.info("  Syncthing:   not running (port " + port + ")");
        }
    }

    // ── Inlined helpers from AbstractWorkspaceMojo ────────────────

    /**
     * Format a goal header line using the workspace name.
     * Inlined equivalent of {@code AbstractWorkspaceMojo#header}.
     */
    private String header(String goalName) {
        return workspaceName() + " — " + goalName;
    }

    /**
     * Read the workspace name from the root POM's artifactId.
     * Falls back to {@code "Workspace"} if the POM cannot be read.
     * Inlined equivalent of {@code AbstractWorkspaceMojo#workspaceName}.
     */
    private String workspaceName() {
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

    /**
     * Inlined equivalent of {@code AbstractWorkspaceMojo#gitBranch}.
     */
    private static String gitBranch(File dir) {
        try {
            return ReleaseSupport.execCapture(dir,
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Inlined equivalent of {@code AbstractWorkspaceMojo#gitShortSha}.
     */
    private static String gitShortSha(File dir) {
        try {
            return ReleaseSupport.execCapture(dir,
                    "git", "rev-parse", "--short", "HEAD");
        } catch (Exception e) {
            return "???????";
        }
    }

    private static String hostname() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }
}
