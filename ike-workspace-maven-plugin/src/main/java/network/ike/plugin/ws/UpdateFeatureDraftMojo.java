package network.ike.plugin.ws;

import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Update the current feature branch by incorporating changes from main.
 *
 * <p>For long-lived feature branches, main may advance significantly.
 * This goal brings the feature branch up to date, surfacing merge
 * conflicts incrementally rather than at feature-finish time.
 *
 * <p>Uses merge (not rebase) to incorporate main — this preserves all
 * commit hashes and is safe for branches shared via Syncthing or pushed
 * to a remote.
 *
 * <p>Components are processed in topological order. If a conflict occurs,
 * the goal stops and reports the conflicting files with instructions for
 * resolving in IntelliJ. Re-running the goal after resolution continues
 * with the remaining components.
 *
 * <p>The draft variant predicts conflicts using {@code git merge-tree}
 * without touching the working tree — safe to run at any time.
 *
 * <pre>{@code
 * mvn ws:update-feature-draft    # preview + predict conflicts
 * mvn ws:update-feature-publish  # merge main into feature branch
 * }</pre>
 *
 * @see FeatureStartDraftMojo for creating feature branches
 * @see FeatureFinishSquashDraftMojo for merging back to main
 */
@Mojo(name = "update-feature-draft", projectRequired = false)
public class UpdateFeatureDraftMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public UpdateFeatureDraftMojo() {}

    /**
     * Feature name. If omitted, auto-detected from subproject branches.
     */
    @Parameter(property = "feature")
    String feature;

    /** Merge strategy — always merge (rebase is not supported). */
    private final String strategy = "merge";

    /** Target branch to update from. */
    @Parameter(property = "targetBranch", defaultValue = "main")
    String targetBranch;

    /** Execute the update. Default is draft (preview only). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    @Override
    public void execute() throws MojoException {
        if (!isWorkspaceMode()) {
            throw new MojoException(
                    "ws:update-feature requires a workspace (workspace.yaml).");
        }

        // strategy is always "merge" — see field declaration

        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // Auto-detect feature if not specified
        if (feature == null || feature.isBlank()) {
            List<String> all = graph.topologicalSort();
            feature = FeatureFinishSupport.detectFeature(
                    root, all, this, getLog());
        }
        String branchName = "feature/" + feature;

        Set<String> targets = graph.manifest().subprojects().keySet();

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        getLog().info("");
        getLog().info(header("Update Feature"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:   " + feature);
        getLog().info("  Branch:    " + branchName);
        getLog().info("  From:      " + targetBranch);
        getLog().info("  Strategy:  " + strategy);
        if (draft) getLog().info("  Mode:      DRAFT");
        getLog().info("");

        // Validate clean working trees and collect eligible components
        List<String> eligible = new ArrayList<>();
        List<String> uncommitted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                skipped.add(name);
                continue;
            }

            String currentBranch = gitBranch(dir);
            if (!currentBranch.equals(branchName)) {
                skipped.add(name);
                getLog().info("  " + Ansi.yellow("· ") + name
                        + " — not on " + branchName + ", skipping");
                continue;
            }

            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                uncommitted.add(name);
                continue;
            }

            eligible.add(name);
        }

        // Check workspace root
        if (new File(root, ".git").exists() && !gitStatus(root).isEmpty()) {
            uncommitted.add("workspace root");
        }

        if (!uncommitted.isEmpty()) {
            var sb = new StringBuilder();
            sb.append("Cannot update — uncommitted changes in:\n");
            for (String name : uncommitted) {
                sb.append("  ").append(name).append("\n");
            }
            sb.append("Commit or stash, then try again.");
            throw new MojoException(sb.toString());
        }

        if (eligible.isEmpty()) {
            getLog().info("  No components on " + branchName + " — nothing to update.");
            return;
        }

        // Show how far behind each subproject is + collect report data
        List<String[]> reportRows = new ArrayList<>();
        for (String name : eligible) {
            File dir = new File(root, name);
            try {
                // Fetch to get latest main from origin
                if (!draft) {
                    VcsOperations.fetch(dir, getLog());
                }

                List<String> behind = VcsOperations.commitLog(
                        dir, branchName, targetBranch);
                List<String> ahead = VcsOperations.commitLog(
                        dir, targetBranch, branchName);

                if (behind.isEmpty()) {
                    getLog().info("  " + Ansi.green("✓ ") + name
                            + " — up to date with " + targetBranch);
                    reportRows.add(new String[]{name, "0", "0", "", "up to date"});
                } else if (draft) {
                    // Predict conflicts without touching working tree
                    List<String> predicted = VcsOperations.predictConflicts(
                            dir, branchName, targetBranch);

                    if (predicted.isEmpty()) {
                        getLog().info("  " + Ansi.green("✓ ") + name + " — "
                                + behind.size() + " commit(s) behind "
                                + targetBranch + ", " + ahead.size()
                                + " ahead — clean update expected");
                        reportRows.add(new String[]{name,
                                String.valueOf(behind.size()),
                                String.valueOf(ahead.size()), "", "clean"});
                    } else {
                        getLog().warn("  " + Ansi.red("⚠ ") + name + " — "
                                + behind.size() + " commit(s) behind "
                                + targetBranch + ", " + ahead.size()
                                + " ahead — " + predicted.size()
                                + " conflict(s) expected:");
                        for (String file : predicted) {
                            getLog().warn("      • " + file);
                        }
                        getLog().warn("      Resolve in IntelliJ after running"
                                + " ws:update-feature-publish");
                        reportRows.add(new String[]{name,
                                String.valueOf(behind.size()),
                                String.valueOf(ahead.size()),
                                String.join(", ", predicted),
                                predicted.size() + " conflict(s)"});
                    }
                } else {
                    getLog().info("  " + Ansi.cyan("→ ") + name + " — "
                            + behind.size() + " commit(s) behind, "
                            + strategy + "...");

                    VcsOperations.mergeNoFf(dir, getLog(), targetBranch,
                            "update: merge " + targetBranch
                                    + " into " + branchName);

                    getLog().info("    " + Ansi.green("✓ ") + "updated");
                    reportRows.add(new String[]{name,
                            String.valueOf(behind.size()),
                            String.valueOf(ahead.size()), "", "merged"});
                }
            } catch (MojoException e) {
                List<String> conflicts = VcsOperations.conflictingFiles(dir);

                getLog().error("");
                getLog().error("  " + Ansi.red("✗ ") + name
                        + " — " + strategy + " failed");
                getLog().error("");

                if (!conflicts.isEmpty()) {
                    getLog().error("  Conflicting files in " + name + ":");
                    for (String file : conflicts) {
                        getLog().error("    • " + file);
                    }
                    getLog().error("");
                }

                getLog().error("  To resolve in IntelliJ:");
                getLog().error("    1. Open the " + name + " project");
                getLog().error("    2. IntelliJ will detect the conflicts automatically");
                getLog().error("    3. Git → Resolve Conflicts → resolve each file"
                        + " with the 3-way merge editor");
                getLog().error("    4. Commit the merge resolution");
                getLog().error("    5. Re-run: mvn ws:update-feature-publish");
                getLog().error("       (already-updated components will be skipped)");
                getLog().error("");
                throw new MojoException(
                        strategy + " failed for " + name
                                + " (" + conflicts.size() + " conflicting file"
                                + (conflicts.size() == 1 ? "" : "s")
                                + "). See above for resolution steps.", e);
            }
        }

        getLog().info("");
        if (draft) {
            getLog().info("  Components to update: " + eligible.size()
                    + " | Skipped: " + skipped.size());
            getLog().info("");
            getLog().info("  Next: mvn ws:update-feature-publish");
        } else {
            getLog().info("  Updated: " + eligible.size() + " subproject(s)"
                    + " | Skipped: " + skipped.size());
        }
        getLog().info("");

        // Write report
        var sb = new StringBuilder();
        sb.append("**Branch:** `").append(branchName)
          .append("` ← `").append(targetBranch).append("`\n\n");
        sb.append("| Subproject | Behind | Ahead | Conflicts | Status |\n");
        sb.append("|-----------|--------|-------|-----------|--------|\n");
        for (String[] row : reportRows) {
            sb.append("| ").append(row[0])
              .append(" | ").append(row[1])
              .append(" | ").append(row[2])
              .append(" | ").append(row[3])
              .append(" | ").append(row[4])
              .append(" |\n");
        }
        sb.append("\n**").append(eligible.size()).append("** subproject(s)")
          .append(draft ? " to update" : " updated")
          .append(", **").append(skipped.size()).append("** skipped.\n");
        writeReport(publish ? WsGoal.UPDATE_FEATURE_PUBLISH : WsGoal.UPDATE_FEATURE_DRAFT,
                sb.toString());
    }
}
