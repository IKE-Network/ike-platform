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
 * <p>When run from a workspace root (where {@code workspace.yaml} exists),
 * iterates all subproject repositories in topological order, staging and
 * committing changes in each. When run from a single repository, operates
 * on the current directory only.
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ws:commit -Dmessage="my commit message" -DaddAll=true
 * }</pre>
 */
@Mojo(name = "commit", projectRequired = false)
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
     * Stage all changes before committing ({@code git add -A}).
     */
    @Parameter(property = "addAll", defaultValue = "false")
    boolean addAll;

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
            try {
                // Skip catch-up if user has pending changes to commit (#132)
                boolean hasWork = addAll
                        ? !VcsOperations.isClean(root)
                        : VcsOperations.hasStagedChanges(root);
                if (hasWork) {
                    if (addAll) {
                        VcsOperations.addAll(root, getLog());
                    }
                } else {
                    VcsOperations.catchUp(root, getLog());
                    if (addAll) {
                        VcsOperations.addAll(root, getLog());
                    }
                }
                if (VcsOperations.hasStagedChanges(root)) {
                    if (message != null && !message.isBlank()) {
                        VcsOperations.commit(root, getLog(), message);
                    } else {
                        VcsOperations.commitStaged(root, getLog(), null);
                    }
                    VcsOperations.writeVcsState(root, VcsState.Action.COMMIT);
                    if (push) {
                        String branch = VcsOperations.currentBranch(root);
                        VcsOperations.push(root, getLog(), "origin", branch);
                        VcsOperations.writeVcsState(root, VcsState.Action.PUSH);
                    }
                    getLog().info(Ansi.green("  ✓ ") + "workspace root");
                    committed++;
                } else if (!VcsOperations.isClean(root)) {
                    String files = VcsOperations.unstagedFiles(root);
                    getLog().warn(Ansi.yellow("  ⚠ ") + "workspace root"
                            + " — skipped (unstaged: " + files + ")");
                    getLog().warn("    Use -DaddAll=true to stage and commit");
                    skippedUnstaged++;
                } else {
                    getLog().debug("workspace root — clean, skipping");
                    skippedClean++;
                }
            } catch (MojoException e) {
                getLog().warn(Ansi.red("  ✗ ") + "workspace root — " + e.getMessage());
                failed++;
            }
        }

        for (String name : sorted) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().debug(name + " — not cloned, skipping");
                skippedClean++;
                continue;
            }

            try {
                // Skip catch-up if user has pending changes to commit (#132)
                boolean hasWork = addAll
                        ? !VcsOperations.isClean(dir)
                        : VcsOperations.hasStagedChanges(dir);
                if (hasWork) {
                    if (addAll) {
                        VcsOperations.addAll(dir, getLog());
                    }
                } else {
                    VcsOperations.catchUp(dir, getLog());
                    if (addAll) {
                        VcsOperations.addAll(dir, getLog());
                    }
                }

                if (!VcsOperations.hasStagedChanges(dir) && VcsOperations.isClean(dir)) {
                    getLog().debug(name + " — clean, skipping");
                    skippedClean++;
                    continue;
                }

                if (!VcsOperations.hasStagedChanges(dir)) {
                    String files = VcsOperations.unstagedFiles(dir);
                    getLog().warn(Ansi.yellow("  ⚠ ") + name
                            + " — skipped (unstaged: " + files + ")");
                    getLog().warn("    Use -DaddAll=true to stage and commit");
                    skippedUnstaged++;
                    continue;
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

                getLog().info(Ansi.green("  ✓ ") + name);
                committed++;
            } catch (MojoException e) {
                getLog().warn(Ansi.red("  ✗ ") + name + " — " + e.getMessage());
                failed++;
            }
        }

        getLog().info("");
        var summary = new StringBuilder();
        summary.append(committed).append(" committed");
        if (skippedClean > 0) {
            summary.append(", ").append(skippedClean).append(" clean");
        }
        if (skippedUnstaged > 0) {
            summary.append(", ").append(skippedUnstaged)
                    .append(" skipped (unstaged — use -DaddAll=true)");
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
    }

    private void executeSingleRepo(File dir) throws MojoException {
        getLog().info("");
        getLog().info("IKE VCS Bridge — Commit");
        getLog().info("══════════════════════════════════════════════════════════════");

        VcsOperations.catchUp(dir, getLog());

        if (addAll) {
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
}
