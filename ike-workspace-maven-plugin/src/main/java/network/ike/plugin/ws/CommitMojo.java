package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
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
 * <p>Usage:
 * <pre>{@code
 * mvn ws:commit -Dmessage="my commit message"               # stage all + commit (default)
 * mvn ws:commit -Dmessage="..." -DstagedOnly                # commit only what is already staged
 * mvn ws:commit -Dmessage="..." -Dpush=true                 # commit then push
 * }</pre>
 *
 * <p>See issue #195 and the {@code dev-workspace-ops-completion} topic
 * in {@code ike-lab-documents} for the design rationale.
 */
@Mojo(name = "commit", projectRequired = false, aggregator = true)
public class CommitMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public CommitMojo() {}

    /**
     * Commit message. If omitted, git opens the editor and the
     * prepare-commit-msg hook generates a message via Claude.
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

    @Override
    public void execute() throws MojoException {
        if (isWorkspaceMode()) {
            executeWorkspace();
        } else {
            executeSingleRepo(new File(System.getProperty("user.dir")));
        }
    }

    private void executeWorkspace() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        List<String> sorted = graph.topologicalSort();

        getLog().info("");
        getLog().info(header("Commit"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        int committed = 0;
        int skippedClean = 0;
        int skippedUnstaged = 0;
        int failed = 0;

        // Include workspace root in commit scan (#102)
        if (new File(root, ".git").exists()) {
            CommitOutcome outcome = commitOne(root, "workspace root");
            committed += outcome.committed;
            skippedClean += outcome.skippedClean;
            skippedUnstaged += outcome.skippedUnstaged;
            failed += outcome.failed;
        }

        for (String name : sorted) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().debug(name + " — not cloned, skipping");
                skippedClean++;
                continue;
            }

            CommitOutcome outcome = commitOne(dir, name);
            committed += outcome.committed;
            skippedClean += outcome.skippedClean;
            skippedUnstaged += outcome.skippedUnstaged;
            failed += outcome.failed;
        }

        getLog().info("");
        var summary = new StringBuilder();
        summary.append(committed).append(" committed");
        if (skippedClean > 0) {
            summary.append(", ").append(skippedClean).append(" clean");
        }
        if (skippedUnstaged > 0) {
            summary.append(", ").append(skippedUnstaged)
                    .append(" skipped (untracked — drop -DstagedOnly to include)");
        }
        if (failed > 0) {
            summary.append(", ").append(failed).append(" failed");
        }
        getLog().info("  Done: " + summary);
        getLog().info("");

        if (failed > 0) {
            getLog().warn("  Some commits failed — check output above for details.");
        }

        writeReport(WsGoal.COMMIT, summary + "\n");

        IdeProfileSync.run(root, getLog());
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

            if (!stagedOnly && !newFiles.isEmpty()) {
                VcsOperations.addAll(dir, getLog());
            } else if (!stagedOnly && modCount > 0
                    && !VcsOperations.hasStagedChanges(dir)) {
                // tracked-modified but none staged — still need addAll
                VcsOperations.addAll(dir, getLog());
            }

            if (!VcsOperations.hasStagedChanges(dir)
                    && VcsOperations.isClean(dir)) {
                getLog().debug(label + " — clean, skipping");
                return CommitOutcome.SKIPPED_CLEAN;
            }

            if (!VcsOperations.hasStagedChanges(dir)) {
                // stagedOnly=true and the user didn't stage anything,
                // but there are untracked or unstaged changes
                String files = VcsOperations.unstagedFiles(dir);
                String suffix = files.isEmpty()
                        ? newFiles.size() + " untracked"
                        : "unstaged: " + files;
                getLog().warn(Ansi.yellow("  ⚠ ") + label
                        + " — skipped (" + suffix + ")");
                getLog().warn("    Drop -DstagedOnly to stage and commit");
                return CommitOutcome.SKIPPED_UNSTAGED;
            }

            if (message != null && !message.isBlank()) {
                VcsOperations.commit(dir, getLog(), message);
            } else {
                VcsOperations.commitStaged(dir, getLog(), null);
            }
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
     * Format a one-line summary like {@code "7 modified, 1 new
     * (.idea/kotlinc.xml)"}. New file paths are listed inline so the
     * developer can see at a glance what {@code addAll} pulled in.
     */
    private static String previewSummary(int modCount, List<String> newFiles) {
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

    private void executeSingleRepo(File dir) throws MojoException {
        getLog().info("");
        getLog().info("IKE VCS Bridge — Commit");
        getLog().info("══════════════════════════════════════════════════════════════");

        VcsOperations.catchUp(dir, getLog());

        if (!stagedOnly) {
            getLog().info("  Staging all changes...");
            VcsOperations.addAll(dir, getLog());
        }

        if (message != null && !message.isBlank()) {
            getLog().info("  Committing...");
            VcsOperations.commit(dir, getLog(), message);
        } else {
            getLog().info("  Committing (editor will open for message)...");
            VcsOperations.commitStaged(dir, getLog(), null);
        }

        VcsOperations.writeVcsState(dir, VcsState.Action.COMMIT);

        if (push) {
            String branch = VcsOperations.currentBranch(dir);
            getLog().info("  Pushing to origin/" + branch + "...");
            VcsOperations.push(dir, getLog(), "origin", branch);
            VcsOperations.writeVcsState(dir, VcsState.Action.PUSH);
        }

        getLog().info("");
        getLog().info("  Done.");
        getLog().info("");
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
