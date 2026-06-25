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
     * via {@link AbstractWorkspaceMojo#requireParam}. Throws a clear
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
        int failed = 0;

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
            CommitOutcome outcome = commitOne(dir, label);
            committed += outcome.committed;
            skippedClean += outcome.skippedClean;
            skippedUnstaged += outcome.skippedUnstaged;
            failed += outcome.failed;
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
        if (failed > 0) {
            summary.append(", ").append(failed).append(" failed");
        }
        getLog().info("  Done: " + summary);
        getLog().info("");

        if (workingSet.isWorkspace()) {
            PostMutationSync.refresh(workingSet.root().toFile(), getLog());
        }

        if (failed > 0) {
            throw new MojoException(failed
                    + " commit(s) failed — check output above for details.");
        }

        return new WorkspaceReportSpec(WsGoal.COMMIT_PUBLISH, summary + "\n");
    }


    /**
     * Commit a single repository, returning a tally for aggregation. The
     * tally always sums to one — exactly one of {committed, skippedClean,
     * skippedUnstaged, failed} is set.
     */
    private CommitOutcome commitOne(File dir, String label) {
        try {
            int modCount = VcsOperations.modifiedTrackedCount(dir);
            List<String> newFiles = VcsOperations.untrackedFiles(dir);

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
                return CommitOutcome.SKIPPED_CLEAN;
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
                return CommitOutcome.SKIPPED_UNSTAGED;
            }

            VcsOperations.commit(dir, getLog(), message);
            VcsOperations.writeVcsState(dir, VcsState.Action.COMMIT);

            if (push) {
                String branch = VcsOperations.currentBranch(dir);
                VcsOperations.push(dir, getLog(), "origin", branch);
                VcsOperations.writeVcsState(dir, VcsState.Action.PUSH);
            }

            getLog().info(Ansi.green("  ✓ ") + label
                    + " — " + previewSummary(modCount, newFiles));
            return CommitOutcome.COMMITTED;
        } catch (MojoException e) {
            getLog().warn(Ansi.red("  ✗ ") + label + " — " + e.getMessage());
            return CommitOutcome.FAILED;
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


    /** Per-repo outcome tally. Exactly one counter is set per repo. */
    private record CommitOutcome(int committed, int skippedClean,
                                 int skippedUnstaged, int failed) {
        static final CommitOutcome COMMITTED = new CommitOutcome(1, 0, 0, 0);
        static final CommitOutcome SKIPPED_CLEAN = new CommitOutcome(0, 1, 0, 0);
        static final CommitOutcome SKIPPED_UNSTAGED = new CommitOutcome(0, 0, 1, 0);
        static final CommitOutcome FAILED = new CommitOutcome(0, 0, 0, 1);
    }
}
