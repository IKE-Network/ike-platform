package network.ike.plugin.ws;

import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.support.GoalReportBuilder;
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
 * Scan the working set for finished feature branches and offer deletion.
 *
 * <p>Classifies every local {@code feature/*} branch — across all
 * subprojects AND the workspace root — into three states
 * (IKE-Network/ike-issues#946):
 *
 * <ul>
 *   <li><b>merged</b> — an ancestor of the target (no-ff merges);</li>
 *   <li><b>squash-merged</b> — not an ancestor, but its tip's tree equals
 *       a recent target commit's tree: the content landed via
 *       {@code ws:feature-finish-squash-*}, the recommended strategy,
 *       which ancestry classification can never see;</li>
 *   <li><b>active</b> — genuinely unmerged work.</li>
 * </ul>
 *
 * <p>Classification runs against {@code origin/<targetBranch>} (after a
 * fetch) whenever that ref resolves — the same source-of-truth doctrine
 * as the feature-lifecycle goals (#857). That also makes deletion of the
 * matching <em>remote</em> feature branches safe: squash-merged means the
 * content is already on the remote target.
 *
 * <p>In draft mode (default), only reports. In publish mode, deletes the
 * merged and squash-merged branches — local and remote (remote deletion
 * soft-fails per #532).
 *
 * <pre>{@code
 * mvn ws:cleanup-draft                    # list finished branches
 * mvn ws:cleanup-publish                  # delete merged + squash-merged
 * mvn ws:cleanup-draft -DtargetBranch=develop
 * }</pre>
 */
@Mojo(name = "cleanup-draft", projectRequired = false, aggregator = true)
public class CleanupWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * How many recent target commits the squash-merge tree comparison
     * scans. Squash finishes land near the target tip, so a modest
     * window is ample; the cap keeps the scan O(1) per branch.
     */
    private static final int TREE_SCAN_LIMIT = 500;

    /** Creates this goal instance. */
    public CleanupWorkspaceMojo() {}

    /** Branch to check merge status against. */
    @Parameter(property = "targetBranch", defaultValue = "main")
    String targetBranch;

    /** Execute deletions (true) or just report (false). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
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

        // The working set includes the root repo (#946/#763): a finish
        // with keepBranch leaves the root's feature branch behind too.
        Map<String, File> memberDirs = new LinkedHashMap<>();
        for (String name : sorted) {
            memberDirs.put(name, new File(root, name));
        }
        memberDirs.put(RefreshMainSupport.ROOT_LABEL, root);

        // Collect merged / squash-merged / active feature branches per member.
        Map<String, List<String>> mergedByMember = new LinkedHashMap<>();
        Map<String, List<String>> squashedByMember = new LinkedHashMap<>();
        Map<String, List<String>> activeByMember = new LinkedHashMap<>();
        Set<String> allMerged = new TreeSet<>();
        Set<String> allSquashed = new TreeSet<>();
        Set<String> allActive = new TreeSet<>();

        for (Map.Entry<String, File> entry : memberDirs.entrySet()) {
            String name = entry.getKey();
            File dir = entry.getValue();
            if (!new File(dir, ".git").exists()) continue;

            // Assess against origin truth when available (#857): fetch,
            // then compare against origin/<target> so a stale local
            // target neither hides a finished branch nor legitimizes
            // deleting one whose content is not actually on the remote.
            try {
                VcsOperations.fetch(dir, getLog());
            } catch (MojoException e) {
                getLog().warn("  " + name + " — fetch failed ("
                        + e.getMessage() + "); assessing against local "
                        + targetBranch);
            }
            String assessRef = RefreshMainSupport.assessmentRef(dir, targetBranch);

            List<String> merged = VcsOperations.mergedBranches(
                    dir, assessRef, "feature/");
            List<String> allFeature = VcsOperations.localBranches(dir, "feature/");
            List<String> squashed = allFeature.stream()
                    .filter(b -> !merged.contains(b))
                    .filter(b -> VcsOperations.branchTreeLandedOn(
                            dir, b, assessRef, TREE_SCAN_LIMIT))
                    .toList();
            List<String> active = allFeature.stream()
                    .filter(b -> !merged.contains(b))
                    .filter(b -> !squashed.contains(b))
                    .toList();

            if (!merged.isEmpty()) {
                mergedByMember.put(name, merged);
                allMerged.addAll(merged);
            }
            if (!squashed.isEmpty()) {
                squashedByMember.put(name, squashed);
                allSquashed.addAll(squashed);
            }
            if (!active.isEmpty()) {
                activeByMember.put(name, active);
                allActive.addAll(active);
            }
        }

        // Report the three classes.
        if (allMerged.isEmpty() && allSquashed.isEmpty()) {
            getLog().info("  No finished feature branches found.");
        }
        if (!allMerged.isEmpty()) {
            getLog().info("  Merged branches (ancestry — safe to delete):");
            logBranchGroup(allMerged, mergedByMember, memberDirs, Ansi.green("    ✓ "));
        }
        if (!allSquashed.isEmpty()) {
            getLog().info("  Squash-merged branches (content landed — safe to delete):");
            logBranchGroup(allSquashed, squashedByMember, memberDirs, Ansi.green("    ✓ "));
        }
        if (!allActive.isEmpty()) {
            getLog().info("");
            getLog().info("  Active branches (not merged):");
            logBranchGroup(allActive, activeByMember, memberDirs, Ansi.yellow("    · "));
        }

        getLog().info("");
        getLog().info("  Summary: " + allMerged.size() + " merged, "
                + allSquashed.size() + " squash-merged, "
                + allActive.size() + " active");

        // In publish mode, delete merged AND squash-merged branches —
        // local and remote (#946; remote deletion soft-fails per #532).
        if (publish && (!allMerged.isEmpty() || !allSquashed.isEmpty())) {
            getLog().info("");
            int deleted = 0;
            Map<String, List<String>> deletable = new LinkedHashMap<>();
            mergedByMember.forEach((m, bs) ->
                    deletable.merge(m, new ArrayList<>(bs), (a, b) -> {
                        a.addAll(b);
                        return a;
                    }));
            squashedByMember.forEach((m, bs) ->
                    deletable.merge(m, new ArrayList<>(bs), (a, b) -> {
                        a.addAll(b);
                        return a;
                    }));
            for (Map.Entry<String, List<String>> entry : deletable.entrySet()) {
                File dir = memberDirs.get(entry.getKey());
                for (String branch : entry.getValue()) {
                    try {
                        FeatureFinishSupport.deleteBranch(
                                dir, getLog(), branch, false);
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

        return new WorkspaceReportSpec(
                publish ? WsGoal.CLEANUP_PUBLISH : WsGoal.CLEANUP_DRAFT,
                buildCleanupReport(allMerged, allSquashed, allActive,
                        mergedByMember, squashedByMember, memberDirs));
    }

    /**
     * Log one line per branch in a classification group: the branch, how
     * many members carry it, and its last-commit date.
     *
     * @param branches   the group's branch names
     * @param byMember   member → branches in this group
     * @param memberDirs member → repository directory
     * @param bullet     the (colored) line prefix
     */
    private void logBranchGroup(Set<String> branches,
                                Map<String, List<String>> byMember,
                                Map<String, File> memberDirs,
                                String bullet) {
        for (String branch : branches) {
            int count = (int) byMember.values().stream()
                    .filter(list -> list.contains(branch))
                    .count();
            String date = lastCommitDate(branch, byMember, memberDirs);
            getLog().info(bullet + branch + " (" + count
                    + " member" + (count == 1 ? "" : "s")
                    + ", last commit: " + date + ")");
        }
    }

    /**
     * Last-commit date of a branch, read from the first member that
     * carries it.
     *
     * @param branch     the branch name
     * @param byMember   member → branches
     * @param memberDirs member → repository directory
     * @return the date string, or {@code "unknown"}
     */
    private String lastCommitDate(String branch,
                                  Map<String, List<String>> byMember,
                                  Map<String, File> memberDirs) {
        for (Map.Entry<String, List<String>> entry : byMember.entrySet()) {
            if (entry.getValue().contains(branch)) {
                return VcsOperations.branchLastCommitDate(
                        memberDirs.get(entry.getKey()), branch);
            }
        }
        return "unknown";
    }

    /**
     * Build the markdown report: merged and squash-merged sections (both
     * deletable) plus the active section.
     *
     * @param merged           ancestry-merged branch names
     * @param squashed         squash-merged (content-landed) branch names
     * @param active           active branch names
     * @param mergedByMember   member → ancestry-merged branches
     * @param squashedByMember member → squash-merged branches
     * @param memberDirs       member → repository directory
     * @return the rendered report body
     */
    private String buildCleanupReport(Set<String> merged, Set<String> squashed,
                                      Set<String> active,
                                      Map<String, List<String>> mergedByMember,
                                      Map<String, List<String>> squashedByMember,
                                      Map<String, File> memberDirs) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph(merged.size() + " merged, " + squashed.size()
                + " squash-merged, " + active.size()
                + " active feature branch"
                + (active.size() == 1 ? "" : "es") + ".");

        if (!merged.isEmpty()) {
            report.section("Merged — ancestry (safe to delete)")
                    .table(List.of("Branch", "Members", "Last Commit"),
                            branchRows(merged, mergedByMember, memberDirs));
        }
        if (!squashed.isEmpty()) {
            report.section("Squash-merged — content landed (safe to delete)")
                    .table(List.of("Branch", "Members", "Last Commit"),
                            branchRows(squashed, squashedByMember, memberDirs));
        }
        if (!active.isEmpty()) {
            List<String[]> activeRows = new ArrayList<>();
            for (String branch : active) {
                activeRows.add(new String[]{branch});
            }
            report.section("Active")
                    .table(List.of("Branch"), activeRows);
        }

        return report.build();
    }

    /**
     * Rows for a deletable-branch table: branch, member count, last date.
     *
     * @param branches   the group's branch names
     * @param byMember   member → branches in this group
     * @param memberDirs member → repository directory
     * @return one row per branch
     */
    private List<String[]> branchRows(Set<String> branches,
                                      Map<String, List<String>> byMember,
                                      Map<String, File> memberDirs) {
        List<String[]> rows = new ArrayList<>();
        for (String branch : branches) {
            int count = (int) byMember.values().stream()
                    .filter(list -> list.contains(branch))
                    .count();
            rows.add(new String[]{branch, String.valueOf(count),
                    lastCommitDate(branch, byMember, memberDirs)});
        }
        return rows;
    }
}
