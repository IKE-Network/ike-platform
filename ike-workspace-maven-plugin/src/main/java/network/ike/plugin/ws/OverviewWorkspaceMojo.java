package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.Subproject;
import network.ike.workspace.Dependency;
import network.ike.workspace.WorkingSet;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consolidated workspace overview — manifest, graph, status, cascade.
 *
 * <p>Replaces the former separate {@code ws:dashboard}, {@code ws:status},
 * and {@code ws:graph} goals with a single command. Loads the manifest
 * once and presents four sections:
 *
 * <ol>
 *   <li><b>Manifest</b> — subproject count, consistency check</li>
 *   <li><b>Graph</b> — dependency order with direct dependencies</li>
 *   <li><b>Status</b> — branch, SHA, clean/uncommitted per subproject</li>
 *   <li><b>Cascade</b> — downstream rebuild impact of components with
 *       uncommitted changes</li>
 * </ol>
 *
 * <p>Use {@code -Dformat=dot} to output Graphviz DOT format instead
 * of the overview (delegates to graph rendering).
 *
 * <pre>{@code
 * mvn ws:overview
 * mvn ws:overview -Dformat=dot
 * }</pre>
 */
@Mojo(name = "overview", projectRequired = false, aggregator = true)
public class OverviewWorkspaceMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public OverviewWorkspaceMojo() {}

    /** Output format: "overview" (default) or "dot" (Graphviz DOT). */
    @Parameter(property = "format", defaultValue = "overview")
    String format;

    /**
     * Kroki server base URL used to render the dependency graph in
     * the markdown report (#533). Defaults to {@code https://kroki.io}.
     * Pass an empty string to disable Kroki rendering and emit only
     * the raw DOT block (useful for fully air-gapped runs).
     * Self-hosted Kroki users override with their endpoint.
     */
    @Parameter(property = "ws.overview.krokiUrl",
            defaultValue = "https://kroki.io")
    String krokiUrl;

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        WorkspaceGraph graph = loadGraph();

        // DOT mode — delegate to graph-only output
        if ("dot".equalsIgnoreCase(format)) {
            printDot(graph);
            return new WorkspaceReportSpec(WsGoal.OVERVIEW,
                    "DOT format emitted to console — no report generated.\n");
        }

        File root = workspaceRoot();

        getLog().info("");
        getLog().info(header("Overview"));
        getLog().info("══════════════════════════════════════════════════════════════");

        // ── Section 1: Manifest ─────────────────────────────────────
        List<String> errors = graph.verify();
        getLog().info("");
        if (errors.isEmpty()) {
            getLog().info(Ansi.green("  ✓ ") + "Manifest: "
                    + graph.manifest().subprojects().size()
                    + " components — consistent");
        } else {
            getLog().warn(Ansi.red("  ✗ ") + "Manifest: "
                    + errors.size() + " error(s)");
            for (String error : errors) {
                getLog().warn("    " + error);
            }
        }

        // ── Section 2: Dependency Graph ─────────────────────────────
        List<String> sorted = graph.topologicalSort();
        getLog().info("");
        getLog().info("  Graph (dependency order)");
        getLog().info("  ──────────────────────────────────────────────────────");

        List<String[]> graphRows = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            String name = sorted.get(i);
            Subproject sub = graph.manifest().subprojects().get(name);
            String deps = sub.dependsOn().isEmpty() ? "—"
                    : sub.dependsOn().stream()
                        .map(Dependency::subproject)
                        .collect(Collectors.joining(", "));

            getLog().info(String.format("  %2d. %-24s → %s",
                    i + 1, name, deps));
            graphRows.add(new String[]{String.valueOf(i + 1), name, deps});
        }

        // ── Section 3: Working-Set Status ────────────────────────────
        // #763/#767: iterate the resolved working set — the declared
        // subprojects PLUS the workspace root (aggregator) — so the
        // aggregator's own pom version, branch, and tree state surface
        // in the report. A subproject-only loop hid the root left on a
        // stale {@code 1-<feature>-SNAPSHOT}; the aggregator is now a
        // first-class member row.
        getLog().info("");
        getLog().info("  Status");
        getLog().info("  ──────────────────────────────────────────────────────");
        getLog().info(String.format("  %-24s %-16s %-24s %-8s %s",
                "COMPONENT", "VERSION", "BRANCH", "SHA", ""));

        List<String> modifiedComponents = new ArrayList<>();
        List<WorkingSetReportTable.Row> statusRows = new ArrayList<>();
        int cloned = 0;
        int notCloned = 0;

        for (WorkingSet.Member member : resolveWorkingSet().members()) {
            String name = member.name();
            File dir = member.directory().toFile();
            // A subproject may not be cloned yet; the aggregator's root
            // directory always exists. The manifest entry is present for
            // subprojects only — the aggregator has none.
            Subproject sub = graph.manifest().subprojects().get(name);

            if (!dir.exists()) {
                notCloned++;
                getLog().info(String.format("  %-24s %-16s %-24s %-8s %s",
                        name, "—", "—", "—", "not cloned"));
                statusRows.add(new WorkingSetReportTable.Row(
                        member, "—", "—", "—", "not cloned"));
                continue;
            }

            cloned++;
            String branch = gitBranch(dir);
            String sha = gitShortSha(dir);
            String status = gitStatus(dir);
            // #533: POM version surfaces stale -SNAPSHOTs, missed
            // version-reset on a feature branch, etc. For the aggregator
            // this is the #763 fix — the root pom version is surfaced.
            String version = readPomVersion(dir);

            // #533: also surface ahead/behind against the upstream
            // tracking branch ({@code @{u}}), so unpushed commits and
            // unfetched remote work both show up in the Status column
            // rather than hiding behind a "clean" tree.
            Optional<int[]> aheadBehind = VcsOperations.aheadBehindUpstream(dir);

            String marker;
            String statusText;
            boolean clean = status.isEmpty();
            if (clean) {
                marker = Ansi.green("✓");
                statusText = "clean";
            } else {
                long count = status.lines().count();
                marker = Ansi.red("✗") + " " + count + " changed";
                statusText = "uncommitted (" + count + " files)";
                // Cascade impact is graph-driven; only declared
                // subprojects have downstream rebuild edges. The
                // aggregator is not a graph node, so it never seeds
                // the cascade even when its tree is dirty.
                if (!member.isAggregator()) {
                    modifiedComponents.add(name);
                }
            }
            if (aheadBehind.isPresent()) {
                int ahead = aheadBehind.get()[0];
                int behind = aheadBehind.get()[1];
                if (ahead > 0 || behind > 0) {
                    StringBuilder delta = new StringBuilder();
                    if (ahead > 0) delta.append(ahead).append(" ahead");
                    if (behind > 0) {
                        if (delta.length() > 0) delta.append(", ");
                        delta.append(behind).append(" behind");
                    }
                    statusText += "; " + delta + " origin";
                    // When the tree itself is clean, promote the
                    // ahead/behind delta to the CLI marker so the
                    // operator notices unpushed or unfetched work
                    // without scanning the status column.
                    if (clean) {
                        if (ahead > 0 && behind == 0) {
                            marker = Ansi.yellow("↑") + " " + ahead + " unpushed";
                        } else if (behind > 0 && ahead == 0) {
                            marker = Ansi.yellow("↓") + " " + behind + " unfetched";
                        } else {
                            marker = Ansi.yellow("⇅") + " " + ahead + "↑/" + behind + "↓";
                        }
                    }
                }
            }

            String branchCol = branch;
            if (sub != null && sub.branch() != null
                    && !branch.equals(sub.branch())) {
                branchCol = branch + Ansi.yellow(" ⚠");
            }

            getLog().info(String.format("  %-24s %-16s %-24s %-8s %s",
                    name, version, branchCol, sha, marker));
            statusRows.add(new WorkingSetReportTable.Row(
                    member, version, branch, sha, statusText));
        }

        getLog().info("");
        getLog().info("  " + cloned + " cloned, " + notCloned + " not cloned, "
                + modifiedComponents.size() + " with uncommitted changes");

        // ── Section 4: Feature Branch Divergence ────────────────────
        // If any subproject is on a feature branch, show how far it has
        // diverged from main — helps developers keep long-lived branches
        // up to date. This stays subproject-scoped: divergence-from-main
        // is a per-subproject concern, distinct from the working-set
        // Status table above.
        Set<String> targets = graph.manifest().subprojects().keySet();
        List<String[]> divergenceRows = new ArrayList<>();
        boolean anyOnFeature = false;
        int warnThreshold = 20;

        for (String name : targets) {
            File dir = new File(root, name);
            if (!dir.exists() || !new File(dir, ".git").exists()) continue;

            String branch = gitBranch(dir);
            if (!branch.startsWith("feature/")) continue;

            anyOnFeature = true;
            try {
                List<String> behind = VcsOperations.commitLog(dir, branch, "main");
                List<String> ahead = VcsOperations.commitLog(dir, "main", branch);

                String divergence;
                String marker;
                if (behind.isEmpty()) {
                    divergence = "up to date";
                    marker = Ansi.green("✓");
                } else if (behind.size() >= warnThreshold) {
                    divergence = behind.size() + " behind, "
                            + ahead.size() + " ahead";
                    marker = Ansi.red("⚠") + " consider ws:update-feature";
                } else {
                    divergence = behind.size() + " behind, "
                            + ahead.size() + " ahead";
                    marker = Ansi.yellow("·");
                }

                divergenceRows.add(new String[]{name, branch,
                        String.valueOf(behind.size()),
                        String.valueOf(ahead.size()), divergence});

                if (!anyOnFeature) continue; // skip printing header until first
            } catch (MojoException e) {
                // Can't determine divergence (no main branch, etc.) — skip
                divergenceRows.add(new String[]{name, branch,
                        "?", "?", "unknown"});
            }
        }

        if (anyOnFeature) {
            getLog().info("");
            getLog().info("  Feature Branch Divergence (from main)");
            getLog().info("  ──────────────────────────────────────────────────────");

            for (String[] row : divergenceRows) {
                String name = row[0];
                int behind = "?".equals(row[2]) ? -1 : Integer.parseInt(row[2]);
                int ahead = "?".equals(row[3]) ? -1 : Integer.parseInt(row[3]);

                String marker;
                String detail;
                if (behind == 0) {
                    marker = Ansi.green("✓");
                    detail = "up to date";
                } else if (behind < 0) {
                    marker = "?";
                    detail = "unknown";
                } else if (behind >= warnThreshold) {
                    marker = Ansi.red("⚠");
                    detail = behind + " commit(s) behind main, "
                            + ahead + " ahead — consider ws:update-feature";
                } else {
                    marker = Ansi.yellow("·");
                    detail = behind + " commit(s) behind main, " + ahead + " ahead";
                }

                getLog().info(String.format("  %-24s %s %s",
                        name, marker, detail));
            }
        }

        // ── Section 5: Cascade ──────────────────────────────────────
        List<String[]> cascadeRows = new ArrayList<>();
        if (!modifiedComponents.isEmpty()) {
            Set<String> allAffected = new LinkedHashSet<>();
            for (String sub : modifiedComponents) {
                allAffected.addAll(graph.cascade(sub));
            }
            allAffected.removeAll(modifiedComponents);

            if (!allAffected.isEmpty()) {
                getLog().info("");
                getLog().info("  Cascade — components needing rebuild:");
                getLog().info("  ──────────────────────────────────────────────────────");

                List<String> buildOrder = graph.topologicalSort(
                        new LinkedHashSet<>(allAffected));
                for (String name : buildOrder) {
                    List<String> triggeredBy = new ArrayList<>();
                    Subproject sub = graph.manifest().subprojects().get(name);
                    if (sub != null) {
                        for (Dependency dep : sub.dependsOn()) {
                            if (modifiedComponents.contains(dep.subproject())
                                    || allAffected.contains(dep.subproject())) {
                                triggeredBy.add(dep.subproject());
                            }
                        }
                    }
                    String triggers = String.join(", ", triggeredBy);
                    getLog().info("    " + name + " ← " + triggers);
                    cascadeRows.add(new String[]{name, triggers});
                }
            }
        }

        getLog().info("");

        // Structured markdown report
        return new WorkspaceReportSpec(WsGoal.OVERVIEW, buildMarkdownReport(
                errors, graphRows, statusRows, divergenceRows, cascadeRows,
                cloned, notCloned, modifiedComponents.size(), graph));
    }

    // ── Markdown report ─────────────────────────────────────────────

    private String buildMarkdownReport(List<String> manifestErrors,
                                        List<String[]> graphRows,
                                        List<WorkingSetReportTable.Row> statusRows,
                                        List<String[]> divergenceRows,
                                        List<String[]> cascadeRows,
                                        int cloned, int notCloned,
                                        int modified,
                                        WorkspaceGraph graph) {
        GoalReportBuilder report = new GoalReportBuilder();

        report.paragraph(manifestErrors.isEmpty()
                ? "**Manifest:** consistent."
                : "**Manifest:** " + manifestErrors.size() + " error(s).");

        // GraphViz dependency graph — IKE-DIAGRAMS.md mandates
        // GraphViz for dependency graphs (IKE-Network/ike-issues#406).
        // #533: Render via Kroki + collapsed DOT source, since most
        // markdown viewers (GitHub web, VS Code preview, Claude Code's
        // session viewer) showed the bare ```dot``` block as raw text.
        report.section("Dependency Graph")
                .table(List.of("#", "Subproject", "Dependencies"), graphRows)
                .raw(DotGraphSupport.buildDotReportSection(graph, krokiUrl));

        // #763/#767: the working-set Status table — one row per member,
        // the workspace-root aggregator included, so its (possibly stale)
        // pom version is visible. Read-only goal ⇒ final column "Status".
        // The summary lead-in precedes the section that render() opens.
        report.paragraph(cloned + " cloned, " + notCloned + " not cloned, "
                + modified + " with uncommitted changes.");
        WorkingSetReportTable.render(report, "Status", "Status", statusRows);

        if (!divergenceRows.isEmpty()) {
            report.section("Feature Branch Divergence")
                    .table(List.of("Subproject", "Branch", "Behind", "Ahead",
                            "Status"), divergenceRows);
        }

        if (!cascadeRows.isEmpty()) {
            report.section("Cascade")
                    .table(List.of("Subproject", "Triggered By"), cascadeRows);
        }

        return report.build();
    }

    /**
     * Read the {@code <version>} from {@code <dir>/pom.xml}. Returns
     * {@code "—"} if the POM is missing or unreadable so the overview
     * never fails on a partially-cloned subproject. The POM is the
     * source of truth for #533 — the workspace.yaml's version field
     * can drift if a feature branch was version-bumped but the
     * manifest didn't get a corresponding update.
     *
     * @param dir subproject directory
     * @return the POM {@code <version>} or {@code "—"} when unavailable
     */
    private String readPomVersion(File dir) {
        File pom = new File(dir, "pom.xml");
        if (!pom.isFile()) return "—";
        try {
            return ReleaseSupport.readPomVersion(pom);
        } catch (MojoException e) {
            return "—";
        }
    }

    // ── DOT output ──────────────────────────────────────────────────

    private void printDot(WorkspaceGraph graph) {
        for (String line
                : DotGraphSupport.dotFromGraph(graph).split("\n")) {
            getLog().info(line);
        }
    }
}
