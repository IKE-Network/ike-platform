package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Preview what {@code ws:commit-publish} would commit across the
 * workspace — read-only.
 *
 * <p>The {@code -draft} half of the commit pair. For each repository
 * (workspace root plus each cloned subproject) it runs
 * {@code git status --porcelain} and reports the uncommitted work that
 * {@link WsCommitPublishMojo ws:commit-publish} would stage and commit:
 * every changed file with its porcelain status flag
 * ({@code  M}, {@code ??}, {@code A }, etc.). Repos with nothing to
 * commit are reported clean.
 *
 * <p>Read-only: no VCS bridge catch-up, no {@code git add}, no
 * {@code git commit}, no push, and no {@code -Dmessage} required. The
 * {@code .mvn/jvm.config} preflight lint still runs as a hard gate — a
 * hash-comment'd {@code jvm.config} would block the real commit, so the
 * draft surfaces it the same way (ike-issues#217).
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ws:commit-draft
 * }</pre>
 *
 * @see WsCommitPublishMojo
 */
@Mojo(name = "commit-draft", projectRequired = false, aggregator = true)
public class WsCommitDraftMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public WsCommitDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (isWorkspaceMode()) {
            return previewWorkspace();
        }
        return previewSingleRepo(new File(System.getProperty("user.dir")));
    }

    // ── Workspace mode ──────────────────────────────────────────

    private WorkspaceReportSpec previewWorkspace() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        List<String> sorted = graph.topologicalSort();

        // Same pre-commit hygiene gate as ws:commit-publish — a
        // #-comment'd .mvn/jvm.config would block the real commit, so
        // the draft fails the same way rather than previewing a commit
        // that cannot happen (ike-issues#217).
        network.ike.plugin.ws.preflight.Preflight.of(
                java.util.List.of(network.ike.plugin.ws.preflight
                        .PreflightCondition.JVM_CONFIG_NO_HASH_COMMENTS),
                network.ike.plugin.ws.preflight.PreflightContext.of(
                        root, graph, sorted))
                .requirePassed(WsGoal.COMMIT_DRAFT);

        getLog().info("");
        getLog().info(header("Commit (draft)"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        List<RepoStatus> statuses = new ArrayList<>();
        if (new File(root, ".git").exists()) {
            statuses.add(statusOf(root, "(workspace root)"));
        }
        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                getLog().debug(name + " — not cloned, skipping");
                continue;
            }
            statuses.add(statusOf(dir, name));
        }

        int pending = 0;
        int clean = 0;
        for (RepoStatus s : statuses) {
            printToConsole(s);
            if (s.hasWork()) {
                pending++;
            } else {
                clean++;
            }
        }

        String summary = pending + " repo(s) with uncommitted work, "
                + clean + " clean";
        getLog().info("");
        getLog().info("  " + summary);
        if (pending > 0) {
            getLog().info("  Run "
                    + WsGoal.COMMIT_PUBLISH.qualified()
                    + " -Dmessage=\"...\" to commit.");
        }
        getLog().info("");

        return new WorkspaceReportSpec(WsGoal.COMMIT_DRAFT,
                buildReport(root.toString(), statuses, summary, pending));
    }

    // ── Single-repo mode ────────────────────────────────────────

    private WorkspaceReportSpec previewSingleRepo(File dir) {
        getLog().info("");
        getLog().info("IKE VCS Bridge — Commit (draft)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        RepoStatus s = statusOf(dir, dir.getName());
        printToConsole(s);

        String summary = s.hasWork()
                ? s.fileCount() + " uncommitted file(s) — run "
                  + WsGoal.COMMIT_PUBLISH.qualified() + " to commit"
                : "Clean — nothing to commit";
        getLog().info("");
        getLog().info("  " + summary);
        getLog().info("");

        return new WorkspaceReportSpec(WsGoal.COMMIT_DRAFT,
                buildReport(dir.toString(), List.of(s), summary,
                        s.hasWork() ? 1 : 0));
    }

    // ── Per-repo status capture ─────────────────────────────────

    /**
     * Per-repo status snapshot: the repo's label and the raw
     * {@code git status --porcelain} output lines (each carries the
     * two-character status flag and the path).
     *
     * @param label           the human-readable repo label
     * @param porcelainLines  one porcelain line per changed file;
     *                        empty list means the repo is clean
     */
    private record RepoStatus(String label, List<String> porcelainLines) {
        boolean hasWork() { return !porcelainLines.isEmpty(); }
        int fileCount() { return porcelainLines.size(); }
    }

    /**
     * Run {@code git status --porcelain} in {@code dir} and capture its
     * output as a {@link RepoStatus}. On failure logs a warning and
     * returns a clean-looking status — the goal is read-only, so a
     * scan failure is reported but does not abort the walk.
     *
     * @param dir   the repo directory
     * @param label the repo label for output
     * @return the captured status (clean if porcelain is empty or
     *         the scan failed)
     */
    private RepoStatus statusOf(File dir, String label) {
        try {
            String output = ReleaseSupport.execCapture(dir,
                    "git", "status", "--porcelain");
            if (output == null || output.isBlank()) {
                return new RepoStatus(label, List.of());
            }
            List<String> lines = new ArrayList<>();
            for (String line : output.split("\n")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return new RepoStatus(label, lines);
        } catch (RuntimeException e) {
            getLog().warn(label + " — git status failed: " + e.getMessage());
            return new RepoStatus(label, List.of());
        }
    }

    private void printToConsole(RepoStatus s) {
        if (!s.hasWork()) {
            getLog().info("  · " + s.label + " — clean");
            return;
        }
        getLog().info(Ansi.yellow("  ⟳ ") + s.label + " — "
                + s.fileCount() + " file(s):");
        for (String line : s.porcelainLines) {
            getLog().info("      " + line);
        }
    }

    /**
     * Build the Markdown report body: a leading summary, then a
     * section per non-clean repo listing every changed file as a
     * bullet with its porcelain status flag.
     *
     * @param rootLabel the workspace root path (or single-repo path)
     * @param statuses  per-repo status snapshots
     * @param summary   the one-line summary used in the console
     * @param pending   how many repos have uncommitted work
     * @return the Markdown report body
     */
    private String buildReport(String rootLabel, List<RepoStatus> statuses,
                                String summary, int pending) {
        StringBuilder body = new StringBuilder();
        body.append("**Workspace:** `").append(rootLabel).append("`\n\n");
        body.append(summary).append(".\n");
        if (pending > 0) {
            for (RepoStatus s : statuses) {
                if (!s.hasWork()) continue;
                body.append("\n### ").append(s.label).append(" — ")
                    .append(s.fileCount()).append(" file(s)\n");
                for (String line : s.porcelainLines) {
                    body.append("- `").append(line).append("`\n");
                }
            }
            body.append("\nRun `")
                .append(WsGoal.COMMIT_PUBLISH.qualified())
                .append(" -Dmessage=\"...\"` to commit.\n");
        }
        return body.toString();
    }
}
