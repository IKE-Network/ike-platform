package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.Subproject;
import network.ike.workspace.DependencyConvergenceAnalysis;
import network.ike.workspace.DependencyConvergenceAnalysis.Divergence;
import network.ike.workspace.DependencyTreeParser;
import network.ike.workspace.DependencyTreeParser.ResolvedDependency;
import network.ike.workspace.VersionSupport;
import network.ike.workspace.WorkspaceGraph;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Check transitive dependency convergence across workspace subprojects.
 *
 * <p>This goal runs {@code mvn dependency:tree} for each subproject in
 * topological order, then compares resolved versions of shared
 * dependencies. Divergences (the same artifact resolving to different
 * versions in different components) are reported in the terminal and
 * written to a markdown report.
 *
 * <p>This is inherently read-only — no apply variant is needed.
 * Slower than other verification goals because it invokes Maven
 * per subproject.
 *
 * <pre>{@code
 * mvn ws:verify-convergence
 * mvn ws:verify-convergence -DconvergenceReport=build/convergence.md
 * }</pre>
 *
 * @see VerifyWorkspaceMojo for general workspace verification
 */
@Mojo(name = "verify-convergence", projectRequired = false, aggregator = true)
public class VerifyConvergenceMojo extends AbstractWorkspaceMojo {

    /**
     * Output file for the convergence markdown report. Defaults to
     * {@code target/convergence-report.md} in the workspace root.
     */
    @Parameter(property = "convergenceReport")
    String convergenceReport;

    /** Creates this goal instance. */
    public VerifyConvergenceMojo() {}

    @Override
    public void execute() throws MojoException {
        getLog().info("");
        getLog().info(header("Dependency Convergence"));
        getLog().info("══════════════════════════════════════════════════════════════");

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        boolean failed = false;

        // ── Fast pre-checks (no Maven invocations) ──────────────

        boolean parentSkew = checkParentVersionSkew(graph, root);
        boolean qualifierContamination = checkBranchQualifierContamination(graph, root);
        failed |= parentSkew;
        failed |= qualifierContamination;

        // ── Dependency tree convergence (slow) ──────────────────

        getLog().info("");
        getLog().info("  Resolving dependency trees (this may take a while)...");
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
                getLog().warn("    ⚠ " + name + ": dependency:tree failed — "
                        + e.getMessage());
            }
        }

        if (componentTrees.size() < 2) {
            getLog().info("    Fewer than 2 components resolved — skipping analysis");
            writeReport(WsGoal.VERIFY_CONVERGENCE, buildSummary(
                    workspaceName(), componentTrees.size(),
                    parentSkew, qualifierContamination,
                    java.util.List.of(), failed));
            return;
        }

        // Analyze
        List<Divergence> divergences =
                DependencyConvergenceAnalysis.analyze(componentTrees);

        // Terminal output
        if (divergences.isEmpty()) {
            getLog().info("");
            getLog().info("  Convergence: all shared dependencies converge across "
                    + componentTrees.size() + " components  ✓");
        } else {
            getLog().info("");
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

        // Markdown report
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

        if (!divergences.isEmpty()) {
            failed = true;
        }

        getLog().info("");
        writeReport(WsGoal.VERIFY_CONVERGENCE, buildSummary(
                wsName, componentTrees.size(),
                parentSkew, qualifierContamination,
                divergences, failed));

        if (failed) {
            throw new MojoException(
                    "Convergence verification failed — see output above.");
        }
    }

    /**
     * Build the session report summary. A higher-level view than the
     * configurable {@code convergenceReport} markdown: captures all three
     * finding buckets (parent skew, qualifier contamination, transitive
     * divergence) and an overall pass/fail status.
     *
     * @param wsName                 workspace name for the heading
     * @param componentsResolved     number of components whose dependency tree resolved
     * @param parentSkew             whether the parent-version check found mismatches
     * @param qualifierContamination whether branch-qualifier contamination was found
     * @param divergences            transitive dependency divergences (may be empty)
     * @param failed                 overall pass/fail flag
     * @return structured markdown for the session report
     */
    static String buildSummary(String wsName,
                                int componentsResolved,
                                boolean parentSkew,
                                boolean qualifierContamination,
                                List<Divergence> divergences,
                                boolean failed) {
        var sb = new StringBuilder();
        sb.append("# Dependency Convergence — ").append(wsName).append("\n\n");
        sb.append("**Components resolved:** ").append(componentsResolved).append("\n\n");
        sb.append("| Check | Result |\n|-------|--------|\n");
        sb.append("| Parent version skew | ")
                .append(parentSkew ? "❌ mismatches" : "✓ clean")
                .append(" |\n");
        sb.append("| Branch qualifier contamination | ")
                .append(qualifierContamination ? "❌ found" : "✓ clean")
                .append(" |\n");
        sb.append("| Dependency convergence | ")
                .append(divergences.isEmpty() ? "✓ clean"
                        : "❌ " + divergences.size() + " divergence(s)")
                .append(" |\n");
        sb.append("\n**Overall:** ").append(failed ? "FAIL" : "PASS").append("\n");

        if (!divergences.isEmpty()) {
            sb.append("\n## Divergences\n\n");
            for (Divergence d : divergences) {
                sb.append("- `").append(d.coordinate()).append("`\n");
                for (var vEntry : d.versionToSubprojects().entrySet()) {
                    sb.append("  - `").append(vEntry.getKey()).append("` ← ")
                      .append(String.join(", ", vEntry.getValue())).append("\n");
                }
            }
        }

        return sb.toString();
    }

    // ── Parent version skew check ───────────────────────────────

    /**
     * Check that all subproject POMs reference the same ike-parent
     * version as the root POM. Logs a warning for each mismatch.
     *
     * @param graph workspace graph
     * @param root  workspace root directory
     * @return {@code true} if any skew was detected
     */
    private boolean checkParentVersionSkew(WorkspaceGraph graph, File root) {
        Path rootPom = root.toPath().resolve("pom.xml");
        if (!Files.exists(rootPom)) return false;

        PomParentSupport.ParentInfo rootParent;
        try {
            rootParent = PomParentSupport.readParent(rootPom);
        } catch (IOException e) {
            return false;
        }
        if (rootParent == null) return false;

        String rootVersion = rootParent.version();
        String parentAid = rootParent.artifactId();
        List<String> skewed = new ArrayList<>();

        getLog().info("");
        getLog().info("  Parent version check (" + parentAid + ":" + rootVersion + ")");

        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            File subDir = new File(root, name);
            Path compPom = subDir.toPath().resolve("pom.xml");
            if (!Files.exists(compPom)) continue;

            try {
                PomParentSupport.ParentInfo compParent =
                        PomParentSupport.readParent(compPom);
                if (compParent == null) continue;
                if (!parentAid.equals(compParent.artifactId())) continue;

                if (!rootVersion.equals(compParent.version())) {
                    skewed.add(name + " (" + compParent.version() + ")");
                }
            } catch (IOException e) {
                getLog().debug("    Could not read parent for " + name);
            }
        }

        if (skewed.isEmpty()) {
            getLog().info("    " + Ansi.GREEN + "✓ " + Ansi.RESET
                    + "All components match root parent version");
            return false;
        }

        getLog().warn("");
        getLog().warn("  Parent version skew detected:");
        getLog().warn("    Root POM: " + parentAid + ":" + rootVersion);
        for (String s : skewed) {
            getLog().warn("    " + s + "  ← mismatch");
        }
        getLog().warn("  Use ws:set-parent-publish -Dparent.version="
                + rootVersion + " to fix.");
        return true;
    }

    // ── Branch qualifier contamination check ────────────────────

    /**
     * Check for branch-qualifier version strings on non-feature branches.
     * Scans all subproject POM files for version strings containing
     * known feature branch qualifiers.
     *
     * @param graph workspace graph
     * @param root  workspace root directory
     * @return {@code true} if any contamination was detected
     */
    private boolean checkBranchQualifierContamination(
            WorkspaceGraph graph, File root) {
        // Only check on non-feature branches
        String wsBranch = "unknown";
        if (new File(root, ".git").exists()) {
            wsBranch = gitBranch(root);
        }
        if (wsBranch.startsWith("feature/")) {
            getLog().info("");
            getLog().info("  Branch qualifier check: skipped (on feature branch)");
            return false;
        }

        // Collect known feature branch qualifiers from subproject branches
        Set<String> qualifiers = new TreeSet<>();
        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            File subDir = new File(root, entry.getKey());
            if (!new File(subDir, ".git").exists()) continue;

            try {
                String output = ReleaseSupport.execCapture(subDir,
                        "git", "branch", "--list", "feature/*");
                for (String line : output.split("\n")) {
                    String branch = line.trim().replaceFirst("^\\* ", "");
                    if (branch.startsWith("feature/")) {
                        qualifiers.add(
                                VersionSupport.safeBranchName(branch));
                    }
                }
            } catch (MojoException ignored) {
            }
        }

        if (qualifiers.isEmpty()) {
            getLog().info("");
            getLog().info("  Branch qualifier check: no feature branches found");
            return false;
        }

        getLog().info("");
        getLog().info("  Branch qualifier check (on " + wsBranch
                + ", scanning for " + qualifiers.size() + " qualifier(s))");

        List<String> contaminated = new ArrayList<>();

        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            File subDir = new File(root, name);
            if (!new File(subDir, "pom.xml").exists()) continue;

            for (String qualifier : qualifiers) {
                List<String> hits = FeatureFinishSupport
                        .findQualifierContamination(subDir, qualifier);
                for (String hit : hits) {
                    contaminated.add(name + "/" + hit
                            + " (qualifier: " + qualifier + ")");
                }
            }
        }

        if (contaminated.isEmpty()) {
            getLog().info("    " + Ansi.GREEN + "✓ " + Ansi.RESET
                    + "No branch qualifiers found on " + wsBranch);
            return false;
        }

        getLog().warn("");
        getLog().warn("  Branch qualifier contamination on " + wsBranch + ":");
        for (String c : contaminated) {
            getLog().warn("    " + c);
        }
        return true;
    }

    private Path resolveConvergenceReportPath(File root) {
        if (convergenceReport != null && !convergenceReport.isBlank()) {
            return Path.of(convergenceReport);
        }
        return root.toPath().resolve("target").resolve("convergence-report.md");
    }
}
