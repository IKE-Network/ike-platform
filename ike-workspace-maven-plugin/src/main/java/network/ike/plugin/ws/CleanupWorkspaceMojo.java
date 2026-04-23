package network.ike.plugin.ws;

import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Scan all workspace subprojects for merged feature branches and offer
 * interactive deletion.
 *
 * <p>Lists feature branches across all subprojects, classifies each as
 * merged (into the target branch) or active, and displays last-commit
 * timestamps. In draft mode (default), only reports. In publish mode,
 * prompts for deletion.
 *
 * <pre>{@code
 * mvn ws:cleanup                          # list stale branches (draft)
 * mvn ws:cleanup-publish                  # prompt for deletion
 * mvn ws:cleanup -DtargetBranch=develop   # check against develop
 * }</pre>
 */
@Mojo(name = "cleanup-draft", projectRequired = false)
public class CleanupWorkspaceMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public CleanupWorkspaceMojo() {}

    /** Branch to check merge status against. */
    @Parameter(property = "targetBranch", defaultValue = "main")
    String targetBranch;

    /** Execute deletions (true) or just report (false). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    @Override
    public void execute() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        boolean draft = !publish;
        List<String> sorted = graph.topologicalSort(
                new LinkedHashSet<>(graph.manifest().subprojects().keySet()));

        getLog().info("");
        getLog().info(header("Cleanup"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Target: " + targetBranch);
        if (draft) getLog().info("  Mode:   DRAFT — listing only");
        getLog().info("");

        // Collect merged and active feature branches per subproject
        Map<String, List<String>> mergedBySubproject = new LinkedHashMap<>();
        Map<String, List<String>> activeBySubproject = new LinkedHashMap<>();
        Set<String> allMerged = new TreeSet<>();
        Set<String> allActive = new TreeSet<>();

        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            List<String> merged = VcsOperations.mergedBranches(
                    dir, targetBranch, "feature/");
            List<String> allFeature = VcsOperations.localBranches(dir, "feature/");
            List<String> active = allFeature.stream()
                    .filter(b -> !merged.contains(b))
                    .toList();

            if (!merged.isEmpty()) {
                mergedBySubproject.put(name, merged);
                allMerged.addAll(merged);
            }
            if (!active.isEmpty()) {
                activeBySubproject.put(name, active);
                allActive.addAll(active);
            }
        }

        // Report merged branches
        if (allMerged.isEmpty()) {
            getLog().info("  No merged feature branches found.");
        } else {
            getLog().info("  Merged branches (safe to delete):");
            for (String branch : allMerged) {
                int count = (int) mergedBySubproject.values().stream()
                        .filter(list -> list.contains(branch))
                        .count();
                // Get last commit date from first subproject
                String date = "unknown";
                for (var entry : mergedBySubproject.entrySet()) {
                    if (entry.getValue().contains(branch)) {
                        date = VcsOperations.branchLastCommitDate(
                                new File(root, entry.getKey()), branch);
                        break;
                    }
                }
                getLog().info(Ansi.green("    ✓ ") + branch + " (" + count
                        + " subproject" + (count == 1 ? "" : "s")
                        + ", last commit: " + date + ")");
            }
        }

        // Report active branches
        if (!allActive.isEmpty()) {
            getLog().info("");
            getLog().info("  Active branches (not fully merged):");
            for (String branch : allActive) {
                int count = (int) activeBySubproject.values().stream()
                        .filter(list -> list.contains(branch))
                        .count();
                String date = "unknown";
                for (var entry : activeBySubproject.entrySet()) {
                    if (entry.getValue().contains(branch)) {
                        date = VcsOperations.branchLastCommitDate(
                                new File(root, entry.getKey()), branch);
                        break;
                    }
                }
                getLog().info(Ansi.yellow("    · ") + branch + " (" + count
                        + " subproject" + (count == 1 ? "" : "s")
                        + ", last commit: " + date + ")");
            }
        }

        getLog().info("");
        getLog().info("  Summary: " + allMerged.size() + " merged, "
                + allActive.size() + " active");

        // In publish mode, delete merged branches
        if (publish && !allMerged.isEmpty()) {
            getLog().info("");
            int deleted = 0;
            for (var entry : mergedBySubproject.entrySet()) {
                File dir = new File(root, entry.getKey());
                for (String branch : entry.getValue()) {
                    try {
                        VcsOperations.deleteBranch(dir, getLog(), branch);
                        getLog().info(Ansi.green("    ✓ ") + "deleted: "
                                + entry.getKey() + "/" + branch);
                        deleted++;

                        // Also clean up stale remote-tracking refs
                        try {
                            new ProcessBuilder("git", "remote", "prune", "origin")
                                    .directory(dir).start().waitFor();
                        } catch (Exception ignored) {}
                    } catch (MojoException e) {
                        getLog().warn(Ansi.red("    ✗ ") + entry.getKey()
                                + "/" + branch + " — " + e.getMessage());
                    }
                }
            }
            getLog().info("");
            getLog().info("  Deleted " + deleted + " branch reference"
                    + (deleted == 1 ? "" : "s") + ".");
        }

        getLog().info("");

        writeReport(publish ? WsGoal.CLEANUP_PUBLISH : WsGoal.CLEANUP_DRAFT, buildCleanupReport(
                allMerged, allActive, mergedBySubproject, root));
    }

    private String buildCleanupReport(Set<String> merged, Set<String> active,
                                       Map<String, List<String>> mergedBySubproject,
                                       File root) {
        var sb = new StringBuilder();
        sb.append(merged.size()).append(" merged, ")
          .append(active.size()).append(" active feature branch")
          .append(active.size() == 1 ? "" : "es")
          .append(".\n\n");

        if (!merged.isEmpty()) {
            sb.append("### Merged (safe to delete)\n\n");
            sb.append("| Branch | Subprojects | Last Commit |\n");
            sb.append("|--------|-------------|-------------|\n");
            for (String branch : merged) {
                int count = (int) mergedBySubproject.values().stream()
                        .filter(list -> list.contains(branch))
                        .count();
                String date = "unknown";
                for (var entry : mergedBySubproject.entrySet()) {
                    if (entry.getValue().contains(branch)) {
                        date = VcsOperations.branchLastCommitDate(
                                new File(root, entry.getKey()), branch);
                        break;
                    }
                }
                sb.append("| ").append(branch)
                  .append(" | ").append(count)
                  .append(" | ").append(date)
                  .append(" |\n");
            }
        }

        if (!active.isEmpty()) {
            sb.append("\n### Active\n\n");
            sb.append("| Branch | Subprojects |\n");
            sb.append("|--------|-------------|\n");
            for (String branch : active) {
                int count = (int) mergedBySubproject.values().stream()
                        .filter(list -> list.contains(branch))
                        .count();
                sb.append("| ").append(branch)
                  .append(" | ").append(count)
                  .append(" |\n");
            }
        }

        return sb.toString();
    }
}
