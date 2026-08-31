package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import network.ike.workspace.WorkingSet;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.List;

/**
 * Commit with a VCS bridge catch-up preamble.
 *
 * <p>By default stages all tracked-modified and untracked-not-ignored
 * files before committing — workspace-wide goals routinely create new
 * files (scaffold writes, IDE settings cleanup, generated configs) and
 * the previous staged-only default silently dropped them. Pass
 * {@code -DstagedOnly} to commit only what is already in the index for
 * the rare cases where that is wanted (positive-form flag per the
 * compiler-visibility principle).
 *
 * <p>Each subproject's commit line includes a count of modified vs. new
 * files, with the new file paths listed inline so the developer can see
 * what was pulled in without running {@code git status} after the fact:
 *
 * <pre>{@code
 *   ✓ komet-ws — 7 modified, 1 new (.idea/kotlinc.xml)
 * }</pre>
 *
 * <p>When run from a workspace root (where {@code workspace.yaml} exists),
 * iterates all subproject repositories in topological order, staging and
 * committing changes in each. When run from a single repository, operates
 * on the current directory only.
 *
 * <p>Before each repo's commit, a stale-drift gate
 * (IKE-Network/ike-issues#1082, {@link StaleDrift}) classifies the
 * staged delta against history: a delta whose every path byte-matches
 * an older committed state is time-reversed synced drift — a tree
 * lagging its refs — and is refused with per-path findings
 * (escape hatch {@code -Dallow-stale-drift=true} for deliberate
 * reverts); a mixed delta is committed with per-path warnings.
 *
 * <p>The {@code -publish} half of the commit pair — it mutates
 * (stages, commits, optionally pushes). The read-only preview is
 * {@link WsCommitDraftMojo ws:commit-draft}.
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ws:commit-publish -Dmessage="my commit message"        # stage all + commit (default)
 * mvn ws:commit-publish -Dmessage="..." -DstagedOnly         # commit only what is already staged
 * mvn ws:commit-publish -Dmessage="..." -Dpush=true          # commit then push
 * }</pre>
 *
 * <p>See issue #195 and the {@code dev-workspace-ops-completion} topic
 * in {@code ike-lab-documents} for the design rationale.
 *
 * @see WsCommitDraftMojo
 */
@Mojo(name = "commit-publish", projectRequired = false, aggregator = true)
public class WsCommitPublishMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public WsCommitPublishMojo() {}

    /**
     * Commit message. Required. When omitted on the command line, the
     * goal prompts interactively (terminal or IntelliJ Maven runner)
     * via {@code AbstractWorkspaceMojo#requireParam()}. Throws a clear
     * error when running in a non-interactive context (CI, piped
     * input). The same resolved message is used for every repo in the
     * workspace iteration.
     */
    @Parameter(property = "message")
    String message;

    /**
     * Commit only what is already in the index — skip the default
     * {@code git add -A} step. Use this when you have hand-staged a
     * subset of changes and want only those to land.
     */
    @Parameter(property = "stagedOnly", defaultValue = "false")
    boolean stagedOnly;

    /**
     * Push to origin after committing.
     */
    @Parameter(property = "push", defaultValue = "false")
    boolean push;

    /**
     * Commit even when every staged change is stale-shaped — its
     * content byte-matches an older committed state of the same path
     * (IKE-Network/ike-issues#1082). The default refuses such a commit:
     * in a synced working set a wholly historical delta is
     * time-reversed drift (the tree lagging its refs), not WIP, and
     * committing it re-commits history backwards. Pass
     * {@code -Dallow-stale-drift=true} only for a deliberate
     * hand-authored revert, after reading the per-path findings the
     * refusal reports.
     */
    @Parameter(property = "allow-stale-drift", defaultValue = "false")
    boolean allowStaleDrift;

    /**
     * Skip the {@code .mvn/jvm.config} hash-comment lint check that
     * runs before commit (ike-issues#217). Default is to run the
     * check; pass {@code -Dws.commit.skipLint=true} to opt out (rare —
     * the check exists because Maven's own validate phase can't catch
     * a {@code #}-comment'd jvm.config in the project that contains it).
     */
    @Parameter(property = "ws.commit.skipLint", defaultValue = "false")
    boolean skipLint;

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        WorkingSet workingSet = resolveWorkingSet();

        // Pre-commit hygiene (workspace mode): catch # comments in
        // .mvn/jvm.config before they reach git or Syncthing (#217). The
        // check is graph-scoped, so it runs only for a declared workspace.
        if (workingSet.isWorkspace() && !skipLint) {
            WorkspaceGraph graph = loadGraph();
            network.ike.plugin.ws.preflight.Preflight.of(
                    java.util.List.of(network.ike.plugin.ws.preflight
                            .PreflightCondition.JVM_CONFIG_NO_HASH_COMMENTS),
                    network.ike.plugin.ws.preflight.PreflightContext.of(
                            workingSet.root().toFile(), graph,
                            graph.topologicalSort()))
                    .requirePassed(WsGoal.COMMIT_PUBLISH);
        }

        // Resolve the message once — it applies to every repo this
        // invocation.
        message = requireParam(message, "message", "Commit message");

        getLog().info("");
        getLog().info(header("Commit"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        int committed = 0;
        int skippedClean = 0;
        int skippedUnstaged = 0;
        // #841: commit failures and push failures are DIFFERENT states with
        // different recoveries — a repo whose commit landed but whose push
        // failed must never be reported as a failed commit (the natural
        // response to that message — inspect or redo the commit — is the
        // wrong move on a repo whose commit is fine).
        List<RepoFailure> commitFailures = new java.util.ArrayList<>();
        List<RepoFailure> pushFailures = new java.util.ArrayList<>();

        for (WorkingSet.Member member : workingSet.members()) {
            File dir = member.directory().toFile();
            if (!new File(dir, ".git").exists()) {
                getLog().debug(member.name() + " — not cloned, skipping");
                skippedClean++;
                continue;
            }
            String label = workingSet.isWorkspace()
                    && member.directory().equals(workingSet.root())
                    ? "workspace root" : member.name();
            switch (commitOne(dir, label, commitFailures, pushFailures)) {
                case COMMITTED -> committed++;
                case SKIPPED_CLEAN -> skippedClean++;
                case SKIPPED_UNSTAGED -> skippedUnstaged++;
                case FAILED -> { /* recorded in the failure lists */ }
            }
        }

        getLog().info("");
        StringBuilder summary = new StringBuilder();
        summary.append(committed).append(" committed");
        if (skippedClean > 0) {
            summary.append(", ").append(skippedClean).append(" clean");
        }
        if (skippedUnstaged > 0) {
            summary.append(", ").append(skippedUnstaged)
                    .append(" skipped (uncommitted — drop -DstagedOnly to include)");
        }
        if (!commitFailures.isEmpty()) {
            summary.append(", ").append(commitFailures.size())
                    .append(" commit failed");
        }
        if (!pushFailures.isEmpty()) {
            summary.append(", ").append(pushFailures.size())
                    .append(" push failed (committed, local only)");
        }
        getLog().info("  Done: " + summary);
        getLog().info("");

        if (workingSet.isWorkspace()) {
            File root = workingSet.root().toFile();
            boolean manifestCommitted =
                    PostMutationSync.refresh(root, getLog());
            // The refresh runs after the commit loop, so a manifest commit
            // it makes is not covered by this goal's own push. When the
            // caller asked to push, push the root again so the re-derived
            // manifest reaches origin too (#774). A failure here is a PUSH
            // failure of the root — never reported as a commit failure (#841).
            if (manifestCommitted && push) {
                try {
                    VcsOperations.push(root, getLog(), "origin",
                            VcsOperations.currentBranch(root));
                } catch (MojoException e) {
                    pushFailures.add(new RepoFailure("workspace root",
                            shortHead(root), e.getMessage()));
                }
            }
        }

        // #841: name the operation, the member repo, and the cause — and
        // when only pushes failed, say plainly that every commit is sound
        // and hand over the copy-pasteable completion command.
        if (!commitFailures.isEmpty() || !pushFailures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (RepoFailure f : commitFailures) {
                sb.append(f.label()).append(" — commit failed: ")
                        .append(f.cause()).append("\n");
            }
            for (RepoFailure f : pushFailures) {
                sb.append(f.label()).append(" — push failed (commit ")
                        .append(f.sha()).append(" is committed, local only): ")
                        .append(f.cause()).append("\n");
            }
            if (commitFailures.isEmpty()) {
                sb.append("\nEvery commit succeeded — only pushes failed. ")
                        .append("Finish with:\n  ./mvnw ")
                        .append(WsGoal.PUSH.qualified());
            }
            throw new MojoException(sb.toString().stripTrailing());
        }

        return new WorkspaceReportSpec(WsGoal.COMMIT_PUBLISH, summary + "\n");
    }

    /**
     * A member repo's failure record for the end-of-run report (#841):
     * which repo, which commit (for push failures — the commit that is
     * now local-only), and the underlying git error.
     *
     * @param label the member label ("workspace root" or subproject name)
     * @param sha   short SHA of the local commit (push failures), or ""
     * @param cause the underlying git error message
     */
    private record RepoFailure(String label, String sha, String cause) {}

    /** Per-repo outcome classification; failures carry detail separately. */
    private enum Outcome {COMMITTED, SKIPPED_CLEAN, SKIPPED_UNSTAGED, FAILED}

    /**
     * Best-effort short HEAD SHA for failure reporting; empty when
     * unreadable.
     *
     * @param dir the repository root directory
     * @return the 8-char short SHA, or {@code ""}
     */
    private static String shortHead(File dir) {
        try {
            return VcsOperations.headSha(dir);
        } catch (MojoException e) {
            return "";
        }
    }


    /**
     * Commit (and optionally push) a single repository, classifying the
     * outcome. Commit failures and push failures are recorded separately
     * with the member label and underlying git error (#841): a push
     * failure leaves the repo COMMITTED (the commit is sound, merely
     * local-only) and is counted as such, never as a failed commit. A
     * transient push error is retried once before being recorded.
     *
     * @param dir            the repository root directory
     * @param label          the member label for reporting
     * @param commitFailures out-param collecting commit failures
     * @param pushFailures   out-param collecting push failures
     * @return the outcome classification
     */
    private Outcome commitOne(File dir, String label,
                              List<RepoFailure> commitFailures,
                              List<RepoFailure> pushFailures) {
        int modCount;
        List<String> newFiles;
        try {
            modCount = VcsOperations.modifiedTrackedCount(dir);
            newFiles = VcsOperations.untrackedFiles(dir);

            // catch-up if there's nothing to commit yet — preserves
            // the historical behavior where commit also serves as the
            // "make sure local is current" step (#132).
            boolean hasWork = stagedOnly
                    ? VcsOperations.hasStagedChanges(dir)
                    : !VcsOperations.isClean(dir) || !newFiles.isEmpty();
            if (!hasWork) {
                VcsOperations.catchUp(dir, getLog());
            }

            if (!stagedOnly && !VcsOperations.isClean(dir)) {
                // Stage everything tracked-modified and untracked-non-ignored
                // before committing. The earlier split-condition form skipped
                // addAll when staged-and-unstaged were mixed, silently
                // dropping the unstaged half from the commit (#536). Calling
                // addAll on already-staged files is a no-op.
                VcsOperations.addAll(dir, getLog());
            }

            if (!VcsOperations.hasStagedChanges(dir)
                    && VcsOperations.isClean(dir)) {
                getLog().debug(label + " — clean, skipping");
                return Outcome.SKIPPED_CLEAN;
            }

            if (!VcsOperations.hasStagedChanges(dir)) {
                // stagedOnly=true and the user didn't stage anything,
                // but there are untracked or unstaged changes — surface
                // both kinds so the developer sees exactly what would be
                // dropped vs. what -DstagedOnly=false would pick up. (#231)
                String suffix = formatUncommittedSuffix(
                        VcsOperations.unstagedFiles(dir), newFiles);
                getLog().warn(Ansi.yellow("  ⚠ ") + label
                        + " — skipped (" + suffix + ")");
                getLog().warn("    Drop -DstagedOnly to stage and commit");
                return Outcome.SKIPPED_UNSTAGED;
            }

            // Stale-drift gate (IKE-Network/ike-issues#1082): a staged
            // delta whose every path byte-matches an older committed
            // state is time-reversed synced drift, not WIP — refuse it.
            // Mixed deltas warn per stale path and proceed. Fails open:
            // an analysis failure never blocks the commit.
            StaleDrift.Analysis drift = analyzeStagedTolerant(dir, label);
            if (drift.whollyStale() && !allowStaleDrift) {
                StringBuilder cause = new StringBuilder(
                        "every staged change matches an older committed"
                                + " state (stale drift, not WIP):\n");
                for (String line : StaleDrift.describeStale(drift)) {
                    cause.append("      ").append(line).append("\n");
                }
                cause.append("    A synced tree lagging its refs presents"
                        + " old content as pending changes. If this is a"
                        + " deliberate revert, re-run with"
                        + " -Dallow-stale-drift=true.");
                getLog().warn(Ansi.red("  ✗ ") + label
                        + " — refused: " + cause);
                commitFailures.add(new RepoFailure(label, "",
                        cause.toString()));
                return Outcome.FAILED;
            }
            if (drift.hasStale()) {
                getLog().warn(Ansi.yellow("  ⚠ ") + label
                        + " — stale-shaped paths in this commit"
                        + " (committing anyway; novel changes present):");
                for (String line : StaleDrift.describeStale(drift)) {
                    getLog().warn("      " + line);
                }
            }

            VcsOperations.commit(dir, getLog(), message);
            VcsOperations.writeVcsState(dir, VcsState.Action.COMMIT);
        } catch (MojoException e) {
            getLog().warn(Ansi.red("  ✗ ") + label + " — commit failed: "
                    + e.getMessage());
            commitFailures.add(new RepoFailure(label, "", e.getMessage()));
            return Outcome.FAILED;
        }

        if (push) {
            try {
                pushWithOneRetry(dir, label);
                VcsOperations.writeVcsState(dir, VcsState.Action.PUSH);
            } catch (MojoException e) {
                getLog().warn(Ansi.red("  ✗ ") + label + " — push failed"
                        + " (commit " + shortHead(dir)
                        + " is committed, local only): " + e.getMessage());
                pushFailures.add(new RepoFailure(
                        label, shortHead(dir), e.getMessage()));
                // The COMMIT succeeded — count it as committed; the push
                // failure is reported separately (#841).
                return Outcome.COMMITTED;
            }
        }

        getLog().info(Ansi.green("  ✓ ") + label
                + " — " + previewSummary(modCount, newFiles));
        return Outcome.COMMITTED;
    }

    /**
     * Runs the stale-drift analysis, failing open: any analysis error
     * is logged and an empty (no-finding) analysis returned, so the
     * guard can never wedge a commit (IKE-Network/ike-issues#1082).
     *
     * @param dir   the repository root directory
     * @param label the member label for the log line
     * @return the analysis, or {@link StaleDrift.Analysis#EMPTY} when
     *         analysis fails
     */
    private StaleDrift.Analysis analyzeStagedTolerant(File dir, String label) {
        try {
            return StaleDrift.analyzeStaged(dir);
        } catch (RuntimeException e) {
            getLog().warn(label + " — stale-drift analysis failed ("
                    + e.getMessage() + "); committing without it");
            return StaleDrift.Analysis.EMPTY;
        }
    }

    /**
     * Push the current branch, retrying once on failure — the #841
     * incident was a transient ssh-agent hiccup that a single retry
     * would have absorbed.
     *
     * @param dir   the repository root directory
     * @param label the member label (for the retry log line)
     * @throws MojoException when the push fails twice
     */
    private void pushWithOneRetry(File dir, String label) throws MojoException {
        String branch = VcsOperations.currentBranch(dir);
        try {
            VcsOperations.push(dir, getLog(), "origin", branch);
        } catch (MojoException first) {
            getLog().info("  " + label + " — push failed once ("
                    + first.getMessage() + "); retrying...");
            VcsOperations.push(dir, getLog(), "origin", branch);
        }
    }

    /**
     * Build the parenthesized suffix for the {@code stagedOnly}
     * skip message. Reports both tracked-unstaged and untracked work
     * with file paths so the developer sees exactly what is sitting
     * uncommitted (#231).
     *
     * <p>Format examples:
     * <ul>
     *   <li>{@code "unstaged: a.java, b.java"} — tracked-unstaged only</li>
     *   <li>{@code "untracked: c.java, d.java"} — untracked only</li>
     *   <li>{@code "unstaged: a.java; untracked: b.java"} — both</li>
     * </ul>
     *
     * @param unstagedFiles comma-separated list of tracked-modified-but-
     *                      unstaged file paths (from
     *                      {@link VcsOperations#unstagedFiles})
     * @param newFiles      list of untracked-but-not-ignored file paths
     *                      (from {@link VcsOperations#untrackedFiles})
     * @return the suffix to interpolate inside {@code "skipped (...)"}
     */
    static String formatUncommittedSuffix(String unstagedFiles, List<String> newFiles) {
        StringBuilder sb = new StringBuilder();
        if (unstagedFiles != null && !unstagedFiles.isEmpty()) {
            sb.append("unstaged: ").append(unstagedFiles);
        }
        if (newFiles != null && !newFiles.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("untracked: ").append(String.join(", ", newFiles));
        }
        if (sb.length() == 0) {
            // Defensive: caller indicated uncommitted work, but no files
            // were captured. Emit a placeholder rather than empty parens.
            sb.append("uncommitted");
        }
        return sb.toString();
    }

    /**
     * Format a one-line summary like {@code "7 modified, 1 new
     * (.idea/kotlinc.xml)"}. New file paths are listed inline so the
     * developer can see at a glance what {@code addAll} pulled in.
     */
    static String previewSummary(int modCount, List<String> newFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append(modCount).append(" modified");
        if (!newFiles.isEmpty()) {
            sb.append(", ").append(newFiles.size()).append(" new (");
            int max = Math.min(3, newFiles.size());
            for (int i = 0; i < max; i++) {
                if (i > 0) sb.append(", ");
                sb.append(newFiles.get(i));
            }
            if (newFiles.size() > max) {
                sb.append(", +").append(newFiles.size() - max).append(" more");
            }
            sb.append(")");
        }
        return sb.toString();
    }


}
