package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import network.ike.workspace.WorkingSet;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Push with a VCS bridge catch-up preamble.
 *
 * <p>When run from a workspace root (where {@code workspace.yaml} exists),
 * iterates the workspace's subprojects and root and pushes each. When run
 * from a single repository, operates on that repository only. The scope is
 * resolved via {@link #resolveWorkingSet()} (ike-issues#611).
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ws:push
 * }</pre>
 */
@Mojo(name = "push", projectRequired = false, aggregator = true)
public class PushMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public PushMojo() {}

    /**
     * Remote name to push to.
     */
    @Parameter(property = "remote", defaultValue = "origin")
    String remote;

    /**
     * When {@code true}, the first push failure (e.g. non-fast-forward,
     * authentication error) halts the goal with a {@link MojoException}
     * instead of warning and continuing. Default {@code false} preserves
     * the per-subproject best-effort behavior; {@link WsSyncMojo} sets
     * this to {@code true} so a non-fast-forward leaves the workspace
     * in a known state for the user to resolve, rather than appearing
     * to succeed for some subprojects and silently failing for others.
     */
    @Parameter(property = "failFast", defaultValue = "false")
    boolean failFast;

    /**
     * Restrict the push to members currently on {@code feature/<name>}.
     *
     * <p>Without it every member of the working set is pushed, including the
     * workspace root — which fails outright for a contributor with read-only
     * access to the aggregator repository, taking the whole goal down with
     * it. Scoping to a feature branch pushes only the repositories that
     * actually carry the work.
     *
     * <p>Members on any other branch are reported as out-of-scope rather
     * than silently omitted, so the summary still accounts for every member.
     */
    @Parameter(property = "feature")
    String feature;

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        WorkingSet workingSet = resolveWorkingSet();

        String featureBranch = (feature == null || feature.isBlank())
                ? null
                : FeatureScope.branchFor(validateFeatureName(feature).value());

        getLog().info("");
        getLog().info(header("Push"));
        getLog().info("══════════════════════════════════════════════════════════════");
        if (featureBranch != null) {
            getLog().info("  Scope: members on " + featureBranch);
        }
        getLog().info("");

        List<PushRow> rows = new ArrayList<>();
        for (WorkingSet.Member member : workingSet.members()) {
            File dir = member.directory().toFile();
            if (!new File(dir, ".git").exists()) {
                getLog().debug(member.name() + " — not cloned, skipping");
                rows.add(PushRow.notCloned(member.name()));
                continue;
            }
            String label = workingSet.isWorkspace()
                    && member.directory().equals(workingSet.root())
                    ? "workspace root" : member.name();
            if (featureBranch != null) {
                String current = VcsOperations.currentBranch(dir);
                if (!featureBranch.equals(current)) {
                    getLog().info("  · " + label + " — on " + current
                            + ", not " + featureBranch + ", skipping");
                    rows.add(PushRow.outOfScope(label, current));
                    continue;
                }
            }
            rows.add(pushOne(dir, label));
        }

        int pushed = (int) rows.stream().filter(r -> r.outcome == Outcome.PUSHED).count();
        int upToDate = (int) rows.stream().filter(r -> r.outcome == Outcome.UP_TO_DATE).count();
        int skipped = (int) rows.stream().filter(r -> r.outcome == Outcome.NOT_CLONED).count();
        int outOfScope = (int) rows.stream()
                .filter(r -> r.outcome == Outcome.OUT_OF_SCOPE).count();
        int failed = (int) rows.stream().filter(r -> r.outcome == Outcome.FAILED).count();
        int uncommitted = (int) rows.stream().filter(r -> r.uncommittedWorkLeft).count();

        getLog().info("");
        StringBuilder summary = new StringBuilder();
        summary.append(pushed).append(" pushed");
        if (upToDate > 0) summary.append(", ").append(upToDate).append(" up-to-date");
        if (skipped > 0) summary.append(", ").append(skipped).append(" skipped");
        if (outOfScope > 0) {
            summary.append(", ").append(outOfScope)
                    .append(" not on feature branch");
        }
        if (uncommitted > 0) {
            summary.append(", ").append(uncommitted)
                    .append(" with uncommitted changes");
        }
        if (failed > 0) summary.append(", ").append(failed).append(" failed");
        getLog().info("  Done: " + summary);
        getLog().info("");

        if (failed > 0) {
            getLog().warn("  Some pushes failed — check output above for details.");
            if (failFast) {
                // failFast hard-fails inside pushOne already; defensive guard.
                throw new MojoException(failed + " push(es) failed.");
            }
        }

        return new WorkspaceReportSpec(WsGoal.PUSH,
                buildPushReport(rows, summary.toString()));
    }

    /**
     * Push a single git directory and capture enough state to render
     * an audit-quality row in the markdown report (#540): old/new SHA
     * range, commit count + subjects, remote URL, and any failure
     * message and retry command. Per-subproject failures are
     * captured into the row rather than thrown, unless
     * {@code failFast=true}.
     *
     * @param dir   git directory to push
     * @param label name to use in the report ("workspace root" or
     *              subproject name)
     * @return populated {@link PushRow}
     */
    private PushRow pushOne(File dir, String label) {
        String branch;
        try {
            branch = VcsOperations.currentBranch(dir);
        } catch (MojoException e) {
            getLog().warn(Ansi.red("  ✗ ") + label + " — "
                    + e.getMessage());
            return PushRow.failure(label, "?", null, e.getMessage(),
                    "git push " + remote, null);
        }

        String bridgeNote = null;
        try {
            bridgeNote = VcsOperations.catchUp(dir, getLog()).note();

            // Capture the pre-push state so we can express the SHA delta
            // even after the push has moved origin/<branch> forward.
            String preHead = VcsOperations.headSha(dir);
            Optional<String> preRemote = VcsOperations.remoteSha(dir, remote, branch);

            // No-op detection — if the remote already points at our HEAD,
            // there is genuinely nothing to push. The original code
            // ran the push regardless, which works (git says "Everything
            // up-to-date") but loses the signal in the report.
            if (preRemote.isPresent() && preRemote.get().equals(preHead)) {
                String url = VcsOperations.remoteUrl(dir, remote).orElse(remote);
                getLog().info(Ansi.green("  = ") + label + " → "
                        + remote + "/" + branch
                        + " (already up-to-date)");
                return PushRow.upToDate(label, branch, preHead, url,
                        !VcsOperations.isClean(dir), bridgeNote);
            }

            // Snapshot commits about to land BEFORE pushing so we can
            // include subject lines in the report. After push, @{u}
            // converges with HEAD and the range disappears.
            List<String> subjectsToPush;
            int commitCount;
            if (preRemote.isPresent()) {
                List<String> commits = safeCommitLog(dir, preRemote.get(), preHead);
                commitCount = commits.size();
                subjectsToPush = capCommitSubjects(commits);
            } else {
                // New ref — count everything reachable from HEAD that
                // isn't already on the remote's default. Cap subjects
                // either way.
                List<String> commits = safeCommitLog(dir, "HEAD~10", preHead);
                commitCount = commits.size();
                subjectsToPush = capCommitSubjects(commits);
            }

            try {
                VcsOperations.push(dir, getLog(), remote, branch);
            } catch (MojoException e) {
                if (e.getMessage() != null
                        && e.getMessage().contains("has no upstream")) {
                    getLog().info("  " + label
                            + " — setting upstream and pushing...");
                    VcsOperations.pushWithUpstream(
                            dir, getLog(), remote, branch);
                } else {
                    throw e;
                }
            }
            VcsOperations.writeVcsState(dir, VcsState.Action.PUSH);

            String url = VcsOperations.remoteUrl(dir, remote).orElse(remote);
            getLog().info(Ansi.green("  ✓ ") + label + " → "
                    + remote + "/" + branch
                    + (preRemote.isPresent()
                        ? " (" + shorten(preRemote.get())
                            + ".." + shorten(preHead) + ", "
                            + commitCount + " commit"
                            + (commitCount == 1 ? "" : "s") + ")"
                        : " (new ref)"));

            boolean uncommitted = !VcsOperations.isClean(dir);
            if (uncommitted) {
                getLog().warn(Ansi.yellow("  ⚠ ") + label
                        + " — has uncommitted changes (not pushed)");
            }

            return PushRow.pushed(label, branch, preRemote.orElse(null),
                    preHead, commitCount, subjectsToPush, url, uncommitted, bridgeNote);
        } catch (MojoException e) {
            String retry = buildRetryCommand(label, branch);
            if (failFast) {
                throw new MojoException("push failed at " + label + ": "
                        + e.getMessage(), e);
            }
            getLog().warn(Ansi.red("  ✗ ") + label + " — " + e.getMessage());
            return PushRow.failure(label, branch, null, e.getMessage(), retry, bridgeNote);
        }
    }

    /**
     * Compose a copy-pasteable retry command for the per-subproject
     * failure section. {@code workspace root} resolves to the root
     * directory; subprojects get a {@code cd} prefix so the user can
     * paste from anywhere under the workspace.
     */
    private String buildRetryCommand(String label, String branch) {
        if ("workspace root".equals(label)) {
            return "git push " + remote + " " + branch;
        }
        return "(cd " + label + " && git push " + remote + " " + branch + ")";
    }

    /**
     * {@code git rev-list a..b} returns commits *reachable from b but
     * not from a*. We swallow MojoException here because the SHA
     * range is a best-effort enrichment for the report — a failure
     * to compute it must not mask the push outcome.
     */
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
     * Render the push markdown report — per-subproject SHA delta + commit
     * subjects table, plus a Failures section with copy-pasteable retry
     * commands when anything failed (#540 + drafts-actionable-remediation).
     */
    private String buildPushReport(List<PushRow> rows, String summary) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Result:** " + summary + ".");

        List<String[]> tableRows = new ArrayList<>();
        for (PushRow r : rows) {
            String shaDelta;
            String commits;
            switch (r.outcome) {
                case PUSHED -> {
                    shaDelta = (r.oldSha == null)
                            ? "(new ref) → `" + shorten(r.newSha) + "`"
                            : "`" + shorten(r.oldSha) + "`..`" + shorten(r.newSha) + "`";
                    commits = String.valueOf(r.commitCount);
                }
                case UP_TO_DATE -> {
                    shaDelta = "`" + shorten(r.newSha) + "`";
                    commits = "0";
                }
                default -> {
                    shaDelta = "—";
                    commits = "—";
                }
            }
            String status = switch (r.outcome) {
                case PUSHED -> "✓ pushed";
                case UP_TO_DATE -> "= up-to-date";
                case NOT_CLONED -> "not cloned";
                case OUT_OF_SCOPE -> "· not on feature branch";
                case FAILED -> "✗ failed";
            };
            if (r.uncommittedWorkLeft) {
                status += " (uncommitted changes left behind)";
            }
            tableRows.add(new String[]{r.label, r.branch, shaDelta, commits, status});
        }
        report.section("Subprojects")
                .table(List.of("Subproject", "Branch", "SHA delta",
                        "Commits", "Status"), tableRows);

        // Detail: per-subproject commit subjects for the actually-pushed ones.
        List<PushRow> withSubjects = rows.stream()
                .filter(r -> r.outcome == Outcome.PUSHED
                        && !r.subjects.isEmpty())
                .toList();
        if (!withSubjects.isEmpty()) {
            report.section("Pushed commits");
            for (PushRow r : withSubjects) {
                report.bullet("**" + r.label + "** → `" + r.remoteUrl + "`");
                for (String s : r.subjects) {
                    report.bullet("    • " + s);
                }
                if (r.commitCount > r.subjects.size()) {
                    report.bullet("    • +" + (r.commitCount - r.subjects.size())
                            + " more");
                }
            }
        }

        // Bridge-state notes (ike-issues#819) — e.g. a stale .ike/vcs-state
        // checkpoint that was self-healed because origin-parity was already
        // confirmed. Informational, not actionable, so it sits ahead of
        // Failures rather than reading as alarming.
        List<PushRow> withNotes = rows.stream()
                .filter(r -> r.bridgeNote != null && !r.bridgeNote.isBlank())
                .toList();
        if (!withNotes.isEmpty()) {
            report.section("Notes");
            for (PushRow r : withNotes) {
                report.bullet("**" + r.label + "** — " + r.bridgeNote);
            }
        }

        // Failures section — surface git stderr AND a copy-pasteable
        // retry block. The user shouldn't have to re-derive the
        // command from the failing subproject's name + branch.
        List<PushRow> failures = rows.stream()
                .filter(r -> r.outcome == Outcome.FAILED).toList();
        if (!failures.isEmpty()) {
            report.section("Failures");
            for (PushRow r : failures) {
                report.bullet("**" + r.label + "** — `" + r.failureMessage + "`");
            }
            StringBuilder retry = new StringBuilder();
            for (PushRow r : failures) {
                retry.append(r.retryCommand).append("\n");
            }
            report.paragraph("Retry the failed pushes individually:");
            report.codeBlock("bash", retry.toString().stripTrailing());
        }

        return report.build();
    }

    /** Outcome state for a single subproject's push attempt. */
    enum Outcome {PUSHED, UP_TO_DATE, NOT_CLONED, OUT_OF_SCOPE, FAILED}

    /**
     * One row of per-subproject push detail. Capture-once, render-many:
     * the CLI log emits a one-line summary as work progresses; the
     * markdown report consumes the full record at the end of the goal.
     */
    record PushRow(
            String label,
            String branch,
            Outcome outcome,
            String oldSha,
            String newSha,
            int commitCount,
            List<String> subjects,
            String remoteUrl,
            boolean uncommittedWorkLeft,
            String failureMessage,
            String retryCommand,
            String bridgeNote) {

        static PushRow pushed(String label, String branch, String oldSha,
                              String newSha, int commits,
                              List<String> subjects, String remoteUrl,
                              boolean uncommitted, String bridgeNote) {
            return new PushRow(label, branch, Outcome.PUSHED, oldSha, newSha,
                    commits, subjects, remoteUrl, uncommitted, null, null, bridgeNote);
        }

        static PushRow upToDate(String label, String branch, String headSha,
                                 String remoteUrl, boolean uncommitted, String bridgeNote) {
            return new PushRow(label, branch, Outcome.UP_TO_DATE, headSha,
                    headSha, 0, List.of(), remoteUrl, uncommitted, null, null, bridgeNote);
        }

        static PushRow notCloned(String name) {
            return new PushRow(name, "—", Outcome.NOT_CLONED, null, null, 0,
                    List.of(), "—", false, null, null, null);
        }

        /**
         * A member excluded by {@code -Dfeature} because it sits on another
         * branch. Its actual branch is retained so the report shows why it
         * was left out rather than just that it was.
         */
        static PushRow outOfScope(String label, String branch) {
            return new PushRow(label, branch, Outcome.OUT_OF_SCOPE, null, null,
                    0, List.of(), "—", false, null, null, null);
        }

        static PushRow failure(String label, String branch, String oldSha,
                                String message, String retry, String bridgeNote) {
            return new PushRow(label, branch, Outcome.FAILED, oldSha, null, 0,
                    List.of(), "—", false, message, retry, bridgeNote);
        }
    }

}
