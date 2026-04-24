package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.BomAnalysis;
import network.ike.workspace.Subproject;
import network.ike.workspace.DependencyConvergenceAnalysis;
import network.ike.workspace.DependencyConvergenceAnalysis.Divergence;
import network.ike.workspace.DependencyTreeParser;
import network.ike.workspace.DependencyTreeParser.ResolvedDependency;
import network.ike.workspace.PublishedArtifactSet;
import network.ike.workspace.WorkspaceGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Verify workspace manifest consistency and subproject git state.
 *
 * <p>Checks that all dependency references resolve, no cycles exist,
 * all group members are valid, and all subproject types are defined.
 * Also reports subproject git state, Syncthing health, and environment
 * presence.
 *
 * <pre>{@code mvn ike:verify}</pre>
 */
@Mojo(name = "verify", projectRequired = false, aggregator = true)
public class VerifyWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * Run transitive dependency convergence analysis across all
     * workspace subprojects. Slow — requires {@code mvn dependency:tree}
     * per subproject.
     */
    @Parameter(property = "checkConvergence", defaultValue = "false")
    boolean checkConvergence;

    /**
     * Output file for the convergence markdown report. Defaults to
     * {@code target/convergence-report.md} in the workspace root.
     */
    @Parameter(property = "convergenceReport")
    String convergenceReport;

    /** Creates this goal instance. */
    public VerifyWorkspaceMojo() {}

    /** Structured verification results for markdown report. */
    private final List<String[]> verifyRows = new ArrayList<>();

    @Override
    public void execute() throws MojoException {
        // Console output goes to Maven log; markdown report via writeReport
        getLog().info("");
        getLog().info(header("Verification"));
        getLog().info("══════════════════════════════════════════════════════════════");

        if (isWorkspaceMode()) {
            verifyWorkspaceManifest();
            verifyParentAlignment();
            verifyBomCascade();
            if (checkConvergence) {
                verifyDependencyConvergence();
            }
            verifyWorkspaceVcs();
        } else {
            verifyBareVcs();
        }

        verifyEnvironment();
        getLog().info("");
        // Structured markdown summary
        if (!verifyRows.isEmpty()) {
            writeReport(WsGoal.VERIFY, buildVerifyMarkdownReport());
        }
    }

    private String buildVerifyMarkdownReport() {
        var sb = new StringBuilder();
        sb.append("| Check | Status |\n");
        sb.append("|-------|--------|\n");
        for (String[] row : verifyRows) {
            sb.append("| ").append(row[0])
                    .append(" | ").append(row[1])
                    .append(" |\n");
        }
        return sb.toString();
    }

    // ── Workspace manifest verification (existing logic) ──────────

    private void verifyWorkspaceManifest() throws MojoException {
        WorkspaceGraph graph = loadGraph();

        List<String> errors = graph.verify();

        int subprojectCount = graph.manifest().subprojects().size();

        getLog().info("  Components:      " + subprojectCount);
        getLog().info("");

        if (errors.isEmpty()) {
            getLog().info(Ansi.green("  Manifest:    consistent  ✓"));
            verifyRows.add(new String[]{"Manifest", "consistent ✓"});
        } else {
            getLog().error("  Manifest:    " + errors.size() + " error(s)");
            for (String error : errors) {
                getLog().error("    ✗ " + error);
            }
            verifyRows.add(new String[]{"Manifest",
                    errors.size() + " error(s)"});
        }
    }

    // ── Parent version alignment ─────────────────────────────────

    private void verifyParentAlignment() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        int misaligned = 0;
        int checked = 0;

        getLog().info("");

        for (Map.Entry<String, Subproject> entry :
                graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            java.nio.file.Path pomFile = root.toPath().resolve(name).resolve("pom.xml");

            if (!java.nio.file.Files.exists(pomFile)) continue;

            try {
                PomParentSupport.ParentInfo parent =
                        PomParentSupport.readParent(pomFile);
                if (parent == null) continue;

                // Check if parent matches a workspace subproject
                String parentSubprojectName = subproject.parent();
                if (parentSubprojectName == null) {
                    // Try to detect: does the parent artifactId match a workspace subproject?
                    for (Map.Entry<String, Subproject> candidate :
                            graph.manifest().subprojects().entrySet()) {
                        if (candidate.getValue().groupId() != null
                                && candidate.getValue().groupId().equals(parent.groupId())) {
                            getLog().info("  INFO: " + name + " has parent "
                                    + parent.groupId() + ":" + parent.artifactId()
                                    + ":" + parent.version()
                                    + " — consider adding 'parent: "
                                    + candidate.getKey() + "' to workspace.yaml");
                            break;
                        }
                    }
                    continue;
                }

                // Parent is declared — check version alignment
                checked++;
                Subproject parentSubproject =
                        graph.manifest().subprojects().get(parentSubprojectName);
                if (parentSubproject == null) {
                    getLog().warn("  WARN: " + name + " declares parent '"
                            + parentSubprojectName + "' but it is not a workspace subproject");
                    misaligned++;
                    continue;
                }

                String expectedVersion = parentSubproject.version();
                if (expectedVersion != null
                        && !expectedVersion.equals(parent.version())) {
                    getLog().warn("  WARN: " + name + " parent version "
                            + parent.version() + " != " + parentSubprojectName
                            + " workspace version " + expectedVersion);
                    misaligned++;
                } else {
                    getLog().info(Ansi.green("  " + name + ": parent " + parentSubprojectName
                            + ":" + parent.version() + "  ✓"));
                }
            } catch (java.io.IOException e) {
                getLog().debug("  Could not read parent from " + name + ": "
                        + e.getMessage());
            }
        }

        if (checked == 0) {
            getLog().info("  Parent alignment: no components declare workspace parents");
            verifyRows.add(new String[]{"Parent alignment", "n/a"});
        } else if (misaligned == 0) {
            getLog().info("  Parent alignment: " + checked
                    + " subproject(s) aligned  ✓");
            verifyRows.add(new String[]{"Parent alignment",
                    checked + " aligned ✓"});
        } else {
            getLog().warn("  Parent alignment: " + misaligned + "/" + checked
                    + " subproject(s) misaligned");
            verifyRows.add(new String[]{"Parent alignment",
                    misaligned + "/" + checked + " misaligned"});
        }
    }

    // ── BOM cascade verification ──────────────────────────────────

    private void verifyBomCascade() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // Build published artifact sets for all subprojects
        Map<String, Set<PublishedArtifactSet.Artifact>> workspaceArtifacts =
                new LinkedHashMap<>();
        for (String name : graph.manifest().subprojects().keySet()) {
            java.nio.file.Path subDir = root.toPath().resolve(name);
            if (java.nio.file.Files.exists(subDir.resolve("pom.xml"))) {
                try {
                    workspaceArtifacts.put(name,
                            PublishedArtifactSet.scan(subDir));
                } catch (java.io.IOException e) {
                    getLog().debug("Could not scan " + name + ": " + e.getMessage());
                }
            }
        }

        try {
            var issues = BomAnalysis.analyzeCascadeIssues(
                    root.toPath(), graph.manifest(), workspaceArtifacts);

            if (issues.isEmpty()) {
                getLog().info("");
                getLog().info("  BOM cascade: all dependency edges can cascade  ✓");
                verifyRows.add(new String[]{"BOM cascade", "all edges cascade ✓"});
            } else {
                getLog().info("");
                getLog().warn("  BOM cascade: " + issues.size() + " gap(s) detected");
                verifyRows.add(new String[]{"BOM cascade",
                        issues.size() + " gap(s)"});
                for (var issue : issues) {
                    getLog().warn("    " + issue.subprojectName() + " → "
                            + issue.dependsOn()
                            + ": no version-property or workspace BOM import");
                    if (!issue.externalBomPins().isEmpty()) {
                        for (var bom : issue.externalBomPins()) {
                            getLog().warn("      external BOM: "
                                    + bom.groupId() + ":" + bom.artifactId()
                                    + ":" + bom.version()
                                    + " (may pin workspace artifact versions)");
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            getLog().warn("  BOM cascade check failed: " + e.getMessage());
        }
    }

    // ── Dependency convergence check ───────────────────────────────

    private void verifyDependencyConvergence() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        getLog().info("");
        getLog().info("  Dependency convergence (this may take a while)...");
        getLog().info("");

        File mvnExecutable = ReleaseSupport.resolveMavenWrapper(root, getLog());

        // Collect dependency trees per subproject in topological order
        List<String> order = graph.topologicalSort();
        Map<String, List<ResolvedDependency>> componentTrees =
                new LinkedHashMap<>();

        for (String name : order) {
            File subDir = new File(root, name);
            File pomFile = new File(subDir, "pom.xml");
            if (!pomFile.exists()) continue;

            getLog().info("    Resolving " + name + "...");
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
                getLog().warn(Ansi.yellow("    ⚠ ") + name + ": dependency:tree failed — "
                        + e.getMessage());
            }
        }

        if (componentTrees.size() < 2) {
            getLog().info("    Fewer than 2 components resolved — skipping analysis");
            return;
        }

        // Analyze
        List<Divergence> divergences =
                DependencyConvergenceAnalysis.analyze(componentTrees);

        // Terminal output — use info so ReportLog captures it
        if (divergences.isEmpty()) {
            getLog().info("");
            getLog().info("  Convergence: all shared dependencies converge across "
                    + componentTrees.size() + " components  ✓");
            verifyRows.add(new String[]{"Convergence",
                    "all converge ✓ (" + componentTrees.size() + " components)"});
        } else {
            getLog().info("");
            verifyRows.add(new String[]{"Convergence",
                    divergences.size() + " divergence(s)"});
            getLog().info("  Convergence: " + divergences.size()
                    + " artifact(s) diverge across "
                    + componentTrees.size() + " components");
            getLog().info("");

            for (Divergence d : divergences) {
                getLog().info("    " + d.coordinate());
                for (var vEntry : d.versionToSubprojects().entrySet()) {
                    getLog().info("      " + vEntry.getKey() + " ← "
                            + String.join(", ", vEntry.getValue()));
                }
            }
        }

        // Supplementary markdown report with full details
        String wsName = workspaceName();
        String markdown = divergences.isEmpty()
                ? "# Dependency Convergence — " + wsName + "\n\n"
                + "All shared dependencies converge across "
                + componentTrees.size() + " components.\n"
                : DependencyConvergenceAnalysis.formatMarkdownReport(
                divergences, wsName);

        Path reportPath = resolveConvergenceReportPath(root);
        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, markdown, StandardCharsets.UTF_8);
            getLog().info("");
            getLog().info("  Report: " + reportPath);
        } catch (IOException e) {
            getLog().warn("  Could not write convergence report: "
                    + e.getMessage());
        }
    }

    private Path resolveConvergenceReportPath(File root) {
        if (convergenceReport != null && !convergenceReport.isBlank()) {
            return Path.of(convergenceReport);
        }
        return root.toPath().resolve("target").resolve("convergence-report.md");
    }

    // ── Subproject git state (workspace mode) ─────────────────────

    private void verifyWorkspaceVcs() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        getLog().info("");

        // Workspace repo itself
        if (VcsState.isIkeManaged(root.toPath())) {
            getLog().info("  Workspace");
            reportVcsState(root, "    ");
        }

        // Each subproject
        for (var entry : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            File dir = new File(root, name);

            if (!new File(dir, ".git").exists()) {
                continue;
            }

            getLog().info("  " + name);

            if (!VcsState.isIkeManaged(dir.toPath())) {
                getLog().info("    Git state: freshly added (no workspace operations yet)");
                continue;
            }

            reportVcsState(dir, "    ");
        }
    }

    // ── Subproject git state (bare mode) ──────────────────────────

    private void verifyBareVcs() throws MojoException {
        File dir = new File(System.getProperty("user.dir"));
        String dirName = dir.getName();

        getLog().info("  Machine:     " + hostname());

        if (!VcsState.isIkeManaged(dir.toPath())) {
            getLog().info("  Git state:   freshly added (no workspace operations yet)");
            return;
        }

        getLog().info("");
        getLog().info("  " + dirName);
        reportVcsState(dir, "    ");
    }

    // ── Shared VCS state reporting ───────────────────────────────

    private void reportVcsState(File dir, String indent)
            throws MojoException {
        String localBranch = gitBranch(dir);
        String localSha = gitShortSha(dir);

        getLog().info(indent + "Branch:        " + localBranch);
        getLog().info(indent + "Local HEAD:    " + localSha);

        Optional<VcsState> stateOpt = VcsState.readFrom(dir.toPath());

        if (stateOpt.isEmpty()) {
            getLog().info(indent + "State file:    absent (first commit, or Syncthing not delivered)");
            getLog().info(indent + "Status:        no state file  ─");
            return;
        }

        VcsState state = stateOpt.get();
        getLog().info(indent + "State file:    " + state.action().label()
                + " by " + state.machine() + " at " + state.timestamp());
        getLog().info(indent + "State SHA:     " + state.sha());
        getLog().info(indent + "State branch:  " + state.branch());

        // In sync?
        boolean shaMatch = state.sha().equals(localSha);
        boolean branchMatch = state.branch().equals(localBranch);

        if (shaMatch && branchMatch) {
            getLog().info(indent + "Status:        in sync  ✓");
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
                getLog().warn(indent + "Status:        feature branch '"
                        + state.branch() + "' started on " + state.machine()
                        + " at " + state.timestamp());
                getLog().warn(indent + "               You are on '"
                        + localBranch + "'.");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync' to switch to the feature branch");
            }
            case FEATURE_FINISH -> {
                getLog().warn(indent + "Status:        feature finished on "
                        + state.machine() + " at " + state.timestamp()
                        + ", merged to '" + state.branch() + "'");
                getLog().warn(indent + "               You are on '"
                        + localBranch + "'.");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync' to return to '"
                        + state.branch() + "'");
            }
            case SWITCH -> {
                getLog().warn(indent + "Status:        switched to '"
                        + state.branch() + "' on " + state.machine()
                        + " at " + state.timestamp());
                getLog().warn(indent + "               You are on '"
                        + localBranch + "'.");
                getLog().warn(indent + "Action:        run 'mvnw ws:switch -Dbranch="
                        + state.branch() + "' or 'mvnw ws:sync'");
            }
            case COMMIT, PUSH, RELEASE, CHECKPOINT -> {
                getLog().warn(indent + "Status:        branch mismatch — local '"
                        + localBranch + "', state file '" + state.branch() + "'");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync' to reconcile");
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
                    getLog().warn(indent + "Status:        commit on "
                            + state.machine() + " at " + state.timestamp());
                    getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                } else {
                    getLog().warn(indent + "Status:        commit on "
                            + state.machine() + " at " + state.timestamp()
                            + ", but push did not complete");
                    getLog().warn(indent + "Action:        push from "
                            + state.machine() + " first, then 'mvnw ike:sync' here");
                    getLog().warn(indent + "               Or: IKE_VCS_OVERRIDE=1 to proceed independently");
                }
            }
            case PUSH -> {
                getLog().warn(indent + "Status:        push from "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "               Local HEAD behind remote.");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
            }
            case RELEASE -> {
                getLog().warn(indent + "Status:        release performed on "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
            }
            case CHECKPOINT -> {
                getLog().warn(indent + "Status:        checkpoint created on "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
            }
            case SWITCH -> {
                getLog().warn(indent + "Status:        switched on "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "Action:        run 'mvnw ws:sync'");
            }
            case FEATURE_START, FEATURE_FINISH -> {
                getLog().warn(indent + "Status:        behind ("
                        + state.action().label() + " on " + state.machine() + ")");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
            }
        }
    }

    // ── Environment checks ──────────────────────────────────────

    private void verifyEnvironment() {
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");

        // Standards
        File standards = new File(dir, ".claude/standards");
        if (standards.isDirectory()) {
            getLog().info("  Standards:   .claude/standards/ present  ✓");
        } else {
            getLog().info("  Standards:   .claude/standards/ absent");
        }

        // CLAUDE.md
        File claudeMd = new File(dir, "CLAUDE.md");
        if (claudeMd.exists()) {
            getLog().info("  CLAUDE.md:   present  ✓");
        } else {
            getLog().info("  CLAUDE.md:   absent");
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
                props.load(new java.io.StringReader(
                        Files.readString(config, StandardCharsets.UTF_8)));
                String portStr = props.getProperty("syncthing.port");
                if (portStr != null) {
                    port = Integer.parseInt(portStr.trim());
                }
            } catch (Exception e) {
                getLog().debug("Could not read .ike/config: " + e.getMessage());
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
                getLog().info("  Syncthing:   connected (port " + port + ")  ✓");
            } else {
                getLog().info("  Syncthing:   responded with status "
                        + response.statusCode());
            }
        } catch (Exception e) {
            getLog().info("  Syncthing:   not running (port " + port + ")");
        }
    }

    private String hostname() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }
}
