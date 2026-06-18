package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.vcs.VcsOperations;

import network.ike.workspace.WorkingSet;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pull latest changes across the workspace.
 *
 * <p>When the workspace root is itself a git repository (i.e., it has a
 * {@code .git} directory), it is pulled first so any changes to the root
 * POM or {@code workspace.yaml} land before subproject operations run.
 * Runs {@code git pull --rebase} in each cloned subproject directory in
 * topological order (dependencies first). Uninitialized components are
 * skipped with a warning.
 *
 * <p><strong>Single repo (no {@code workspace.yaml})</strong>: operates on
 * the current repository only — a working set of one — pulling its tracked
 * branch with {@code git pull --rebase}. The scope is resolved via
 * {@link #resolveWorkingSet()} (IKE-Network/ike-issues#703, completing the
 * #611 working-set migration so {@code ws:pull} matches {@code ws:commit} /
 * {@code ws:push}).
 *
 * <pre>{@code
 * mvn ws:pull
 * }</pre>
 */
@Mojo(name = "pull", projectRequired = false, aggregator = true)
public class PullWorkspaceMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public PullWorkspaceMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        // Resolve the scope once: a workspace pulls its subprojects + root;
        // a bare repo is a working set of one (IKE-Network/ike-issues#703,
        // completing the #611 working-set migration).
        WorkingSet workingSet = resolveWorkingSet();
        if (!workingSet.isWorkspace()) {
            return pullSingleRepo(workingSet.root().toFile(),
                    workingSet.members().getFirst().name());
        }
        return pullWorkspace();
    }

    /**
     * Pull every member of a workspace: the workspace root first (so any
     * update to the root POM or {@code workspace.yaml} is observed before
     * subproject pulls, #179), then each cloned subproject in topological
     * order. Gated by a workspace-wide working-tree-clean preflight
     * (#132/#154).
     *
     * @return the pull report for the workspace
     */
    private WorkspaceReportSpec pullWorkspace() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        Set<String> targets = graph.manifest().subprojects().keySet();

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        // Preflight: all working trees must be clean (#132, #154)
        Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, sorted))
                .requirePassed(WsGoal.PULL);

        printPullBanner();

        List<PullRow> rows = new ArrayList<>();

        // Pull workspace root if it has a .git directory (#179). Must run
        // before subproject pulls so any update to the root POM or
        // workspace.yaml is observed by downstream steps.
        if (new File(root, ".git").exists()) {
            rows.add(pullOne(root, "workspace root", true));
        }

        for (String name : sorted) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().info(Ansi.yellow("  ⚠ ") + name + " — not cloned, skipping");
                rows.add(PullRow.notCloned(name));
                continue;
            }
            rows.add(pullOne(dir, name, false));
        }

        WorkspaceReportSpec spec = pullReport(rows);
        PostMutationSync.refresh(root, getLog());
        return spec;
    }

    /**
     * Pull a single repository — a working set of one (no
     * {@code workspace.yaml}, IKE-Network/ike-issues#703). Applies the same
     * {@code git pull --rebase} per-repo logic the workspace path uses for
     * each member, with no workspace preflight ({@code git}'s own rebase
     * guard rejects a dirty tree) and no {@link PostMutationSync}
     * (workspace-only).
     *
     * @param dir  the single repository's directory
     * @param name the repository's directory name, used as the report label
     * @return the pull report for the one repository
     */
    private WorkspaceReportSpec pullSingleRepo(File dir, String name) {
        printPullBanner();
        if (!new File(dir, ".git").exists()) {
            throw new MojoException("ws:pull: " + dir
                    + " is not a git repository.");
        }
        return pullReport(List.of(pullOne(dir, name, true)));
    }

    /** Print the goal banner, shared by the workspace and bare paths. */
    private void printPullBanner() {
        getLog().info("");
        getLog().info(header("Pull"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");
    }

    /**
     * Tally the per-repo outcomes, log the one-line summary, and build the
     * {@link WorkspaceReportSpec}. Shared by the workspace and single-repo
     * paths so both render an identical report shape.
     *
     * @param rows the per-repository pull outcomes
     * @return the pull report
     */
    private WorkspaceReportSpec pullReport(List<PullRow> rows) {
        int pulled = (int) rows.stream().filter(r -> r.outcome == Outcome.PULLED).count();
        int upToDate = (int) rows.stream().filter(r -> r.outcome == Outcome.UP_TO_DATE).count();
        int noUpstream = (int) rows.stream().filter(r -> r.outcome == Outcome.NO_UPSTREAM).count();
        int notCloned = (int) rows.stream().filter(r -> r.outcome == Outcome.NOT_CLONED).count();
        int failed = (int) rows.stream().filter(r -> r.outcome == Outcome.FAILED).count();

        getLog().info("");
        StringBuilder summary = new StringBuilder();
        summary.append(pulled).append(" pulled");
        if (upToDate > 0) summary.append(", ").append(upToDate).append(" up-to-date");
        if (noUpstream > 0) summary.append(", ").append(noUpstream).append(" without upstream");
        if (notCloned > 0) summary.append(", ").append(notCloned).append(" not cloned");
        if (failed > 0) summary.append(", ").append(failed).append(" failed");
        getLog().info("  Done: " + summary);
        getLog().info("");

        if (failed > 0) {
            getLog().warn("  Some pulls failed — check output above for details.");
        }

        return new WorkspaceReportSpec(WsGoal.PULL,
                buildPullReport(rows, summary.toString()));
    }

    /**
     * Pull a single git directory and capture enough state to render
     * an audit-quality row in the markdown report (#541): pre/post
     * HEAD SHA, commit count + subjects merged in, explicit
     * up-to-date detection, no-upstream detection with a copy-paste
     * recovery command, and failure detail with a retry command.
     *
     * @param dir   the git directory to pull
     * @param label the report label ("workspace root", a subproject name,
     *              or a single repository's directory name)
     * @param atCwd {@code true} when {@code dir} is the invocation directory
     *              (the workspace root, or the lone repo of a working set of
     *              one) — recovery commands are then emitted plain; {@code
     *              false} for a subproject, which gets a {@code cd} prefix
     * @return the populated row
     */
    private PullRow pullOne(File dir, String label, boolean atCwd) {
        String branch;
        try {
            branch = VcsOperations.currentBranch(dir);
        } catch (MojoException e) {
            getLog().warn(Ansi.red("  ✗ ") + label + " — "
                    + e.getMessage());
            return PullRow.failure(label, "?", e.getMessage(),
                    buildRetryCommand(label, atCwd));
        }

        // Detect "no upstream" up front. ReleaseSupport.exec routes
        // git's stderr through the Maven logger but the resulting
        // MojoException only carries "Command failed (exit N)", so
        // post-failure string-matching on the exception message can't
        // distinguish a missing-upstream case from a generic network
        // error. Pre-check is reliable and surfaces the right
        // recovery command (drafts-actionable-remediation).
        if (VcsOperations.aheadBehindUpstream(dir).isEmpty()) {
            getLog().warn(Ansi.yellow("  ⚠ ") + label
                    + " — no upstream configured");
            return PullRow.noUpstream(label, branch,
                    buildSetUpstreamCommand(label, branch, atCwd));
        }

        String preHead;
        try {
            preHead = VcsOperations.headSha(dir);
        } catch (MojoException e) {
            getLog().warn(Ansi.red("  ✗ ") + label + " — "
                    + e.getMessage());
            return PullRow.failure(label, branch, e.getMessage(),
                    buildRetryCommand(label, atCwd));
        }

        getLog().info(Ansi.cyan("  ↓ ") + label);
        try {
            ReleaseSupport.exec(dir, getLog(),
                    "git", "pull", "--rebase");
        } catch (MojoException e) {
            String msg = String.valueOf(e.getMessage());
            getLog().warn(Ansi.red("  ✗ ") + label + " — pull failed: "
                    + msg);
            return PullRow.failure(label, branch, msg,
                    buildRetryCommand(label, atCwd));
        }

        String postHead;
        try {
            postHead = VcsOperations.headSha(dir);
        } catch (MojoException e) {
            // The pull succeeded but we can't read HEAD — treat as
            // pulled with unknown delta rather than fabricating one.
            getLog().debug("Could not read HEAD after pull in " + label
                    + ": " + e.getMessage());
            return PullRow.pulledUnknown(label, branch);
        }

        if (preHead.equals(postHead)) {
            getLog().info(Ansi.green("  = ") + label
                    + " — already up-to-date (" + shorten(preHead) + ")");
            return PullRow.upToDate(label, branch, preHead);
        }

        List<String> commits = safeCommitLog(dir, preHead, postHead);
        int commitCount = commits.size();
        List<String> subjects = capCommitSubjects(commits);

        getLog().info(Ansi.green("  ✓ ") + label + " — "
                + shorten(preHead) + ".." + shorten(postHead)
                + " (" + commitCount + " commit"
                + (commitCount == 1 ? "" : "s") + ")");
        return PullRow.pulled(label, branch, preHead, postHead,
                commitCount, subjects);
    }

    private static String buildRetryCommand(String label, boolean atCwd) {
        if (atCwd) {
            return "git pull --rebase";
        }
        return "(cd " + label + " && git pull --rebase)";
    }

    private static String buildSetUpstreamCommand(String label, String branch,
                                                  boolean atCwd) {
        if (atCwd) {
            return "git push -u origin " + branch;
        }
        return "(cd " + label + " && git push -u origin " + branch + ")";
    }

    private List<String> safeCommitLog(File dir, String base, String head) {
        try {
            return VcsOperations.commitLog(dir, base, head);
        } catch (MojoException e) {
            getLog().debug("Could not compute commit log " + base
                    + ".." + head + ": " + e.getMessage());
            return List.of();
        }
    }

    private static String shorten(String sha) {
        if (sha == null || sha.length() <= 7) return sha;
        return sha.substring(0, 7);
    }

    private static List<String> capCommitSubjects(List<String> commits) {
        if (commits.isEmpty()) return List.of();
        int max = Math.min(5, commits.size());
        List<String> capped = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            String line = commits.get(i);
            int sp = line.indexOf(' ');
            capped.add(sp >= 0 ? line.substring(sp + 1) : line);
        }
        return capped;
    }

    /**
     * Render the pull markdown report — Subprojects table with the
     * per-subproject SHA delta and a Merged commits section listing
     * subjects (#541). Failures and no-upstream cases both get a
     * copy-pasteable recovery command (drafts-actionable-remediation).
     */
    private String buildPullReport(List<PullRow> rows, String summary) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Result:** " + summary + ".");

        List<String[]> tableRows = new ArrayList<>();
        for (PullRow r : rows) {
            String shaDelta;
            String commits;
            switch (r.outcome) {
                case PULLED -> {
                    shaDelta = "`" + shorten(r.preHead) + "`..`"
                            + shorten(r.postHead) + "`";
                    commits = String.valueOf(r.commitCount);
                }
                case UP_TO_DATE -> {
                    shaDelta = "`" + shorten(r.preHead) + "`";
                    commits = "0";
                }
                default -> {
                    shaDelta = "—";
                    commits = "—";
                }
            }
            String status = switch (r.outcome) {
                case PULLED -> "✓ pulled (rebase)";
                case UP_TO_DATE -> "= up-to-date";
                case NO_UPSTREAM -> "⚠ no upstream";
                case NOT_CLONED -> "not cloned";
                case FAILED -> "✗ failed";
            };
            tableRows.add(new String[]{r.label, r.branch, shaDelta, commits, status});
        }
        report.section("Subprojects")
                .table(List.of("Subproject", "Branch", "SHA delta",
                        "Commits", "Status"), tableRows);

        // Detail: per-subproject subject lines for the actually-pulled ones.
        List<PullRow> withSubjects = rows.stream()
                .filter(r -> r.outcome == Outcome.PULLED
                        && !r.subjects.isEmpty()).toList();
        if (!withSubjects.isEmpty()) {
            report.section("Merged commits");
            for (PullRow r : withSubjects) {
                report.bullet("**" + r.label + "** — `"
                        + shorten(r.preHead) + "`..`"
                        + shorten(r.postHead) + "`");
                for (String s : r.subjects) {
                    report.bullet("    • " + s);
                }
                if (r.commitCount > r.subjects.size()) {
                    report.bullet("    • +" + (r.commitCount - r.subjects.size())
                            + " more");
                }
            }
        }

        // Subprojects whose branch has no upstream — copy-paste recovery.
        List<PullRow> noUpstream = rows.stream()
                .filter(r -> r.outcome == Outcome.NO_UPSTREAM).toList();
        if (!noUpstream.isEmpty()) {
            report.section("No upstream configured");
            report.paragraph("These subprojects' current branch has no "
                    + "tracking branch on `origin`. To set one and "
                    + "establish a baseline:");
            StringBuilder fix = new StringBuilder();
            for (PullRow r : noUpstream) {
                fix.append(r.retryCommand).append("\n");
            }
            report.codeBlock("bash", fix.toString().stripTrailing());
        }

        List<PullRow> failures = rows.stream()
                .filter(r -> r.outcome == Outcome.FAILED).toList();
        if (!failures.isEmpty()) {
            report.section("Failures");
            for (PullRow r : failures) {
                report.bullet("**" + r.label + "** — `" + r.failureMessage + "`");
            }
            StringBuilder retry = new StringBuilder();
            for (PullRow r : failures) {
                retry.append(r.retryCommand).append("\n");
            }
            report.paragraph("Retry the failed pulls individually:");
            report.codeBlock("bash", retry.toString().stripTrailing());
        }

        return report.build();
    }

    /** Outcome state for a single subproject's pull attempt. */
    enum Outcome {PULLED, UP_TO_DATE, NO_UPSTREAM, NOT_CLONED, FAILED}

    /**
     * One row of per-subproject pull detail. Capture-once,
     * render-many: the CLI log emits a one-line summary as work
     * progresses; the markdown report consumes the full record at
     * the end of the goal.
     */
    record PullRow(
            String label,
            String branch,
            Outcome outcome,
            String preHead,
            String postHead,
            int commitCount,
            List<String> subjects,
            String failureMessage,
            String retryCommand) {

        static PullRow pulled(String label, String branch, String preHead,
                              String postHead, int commits,
                              List<String> subjects) {
            return new PullRow(label, branch, Outcome.PULLED, preHead, postHead,
                    commits, subjects, null, null);
        }

        static PullRow pulledUnknown(String label, String branch) {
            return new PullRow(label, branch, Outcome.PULLED, null, null,
                    0, List.of(), null, null);
        }

        static PullRow upToDate(String label, String branch, String headSha) {
            return new PullRow(label, branch, Outcome.UP_TO_DATE,
                    headSha, headSha, 0, List.of(), null, null);
        }

        // A NO_UPSTREAM row never also FAILS, so it reuses the
        // retryCommand slot to carry its set-upstream recovery command.
        static PullRow noUpstream(String label, String branch,
                                  String recoveryCommand) {
            return new PullRow(label, branch, Outcome.NO_UPSTREAM,
                    null, null, 0, List.of(), null, recoveryCommand);
        }

        static PullRow notCloned(String name) {
            return new PullRow(name, "—", Outcome.NOT_CLONED,
                    null, null, 0, List.of(), null, null);
        }

        static PullRow failure(String label, String branch, String message,
                                String retry) {
            return new PullRow(label, branch, Outcome.FAILED,
                    null, null, 0, List.of(), message, retry);
        }
    }
}
