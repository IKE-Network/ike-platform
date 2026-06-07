package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import org.apache.maven.api.plugin.MojoException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Displays available ws: workspace goals, enumerated from the
 * compile-time {@link WsGoal} registry.
 *
 * <p>Goal names and descriptions come from the {@link WsGoal} enum,
 * which is the single source of truth for every {@code ws:} goal in
 * this plugin. The compiler enforces that every mojo has a matching
 * enum entry, so the help output cannot drift from the actual plugin.
 *
 * <p>This used to read from the Maven {@code PluginDescriptor}, but
 * Maven 4.0.0-rc-5 does not bind {@code PluginDescriptor} in the DI
 * container, causing a startup crash. Iterating the enum is also
 * type-safe — no string lookups, no runtime resolution.
 *
 * <p>Goals are categorized by prefix convention:
 * <ul>
 *   <li>feature-*, update-feature-*, switch-* → Feature Branching</li>
 *   <li>set-parent-*, align-* → Parent &amp; Version Alignment</li>
 *   <li>release-*, checkpoint-*, post-release → Release &amp; Checkpoint</li>
 *   <li>commit, push, sync, check-branch → VCS Bridge</li>
 *   <li>cleanup-* → Branch Cleanup</li>
 *   <li>Everything else → Workspace Management</li>
 * </ul>
 *
 * @see <a href="https://github.com/IKE-Network/ike-platform">IKE Platform</a>
 */
@org.apache.maven.api.plugin.annotations.Mojo(name = "help", projectRequired = false, aggregator = true)
public class WsHelpMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public WsHelpMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        List<GoalInfo> goals = discoverGoals();

        getLog().info("");
        getLog().info("IKE Workspace Tools — Available Goals");
        getLog().info("══════════════════════════════════════════════════════════════");

        Map<String, List<GoalInfo>> categories = categorize(goals);

        for (Map.Entry<String, List<GoalInfo>> entry : categories.entrySet()) {
            getLog().info("");
            getLog().info("  ── " + entry.getKey() + " "
                    + "─".repeat(Math.max(1,
                    56 - entry.getKey().length())) + "─");
            for (GoalInfo g : entry.getValue()) {
                String goalName = g.goal.qualified();
                String padding = " ".repeat(
                        Math.max(1, 46 - goalName.length()));
                getLog().info("  " + goalName + padding + g.summary);
            }
        }

        getLog().info("");
        printOptions();
        getLog().info("");

        return new WorkspaceReportSpec(WsGoal.HELP, buildHelpReport(goals));
    }

    /**
     * Render the discovered goals as a Markdown table for the session
     * report.
     *
     * @param goals the goals discovered from the {@link WsGoal} registry
     * @return the Markdown report body
     */
    private static String buildHelpReport(List<GoalInfo> goals) {
        List<String[]> rows = new ArrayList<>();
        for (GoalInfo g : goals) {
            rows.add(new String[]{"`" + g.goal().qualified() + "`", g.summary()});
        }
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph(goals.size() + " `ws:*` goals.")
                .table(List.of("Goal", "Description"), rows);
        return report.build();
    }

    // ── Goal discovery ──────────────────────────────────────────

    /**
     * Enumerate goal names and descriptions from the {@link WsGoal}
     * registry. Each enum entry carries its own one-line description,
     * so no parsing is required.
     *
     * @return list of discovered goals, sorted by name
     */
    private static List<GoalInfo> discoverGoals() {
        List<GoalInfo> goals = new ArrayList<>();
        for (WsGoal goal : WsGoal.values()) {
            goals.add(new GoalInfo(goal, goal.goalName(), goal.description()));
        }
        goals.sort(Comparator.comparing(g -> g.name));
        return goals;
    }

    // ── Categorization ──────────────────────────────────────────

    /**
     * Group goals into named categories based on prefix conventions.
     *
     * @param goals sorted list of goals
     * @return ordered map of category name to goals
     */
    private static Map<String, List<GoalInfo>> categorize(
            List<GoalInfo> goals) {
        Map<String, List<GoalInfo>> categories = new LinkedHashMap<>();
        categories.put("Workspace Management", new ArrayList<>());
        categories.put("Parent & Version Alignment", new ArrayList<>());
        categories.put("Feature Branching", new ArrayList<>());
        categories.put("Release & Checkpoint", new ArrayList<>());
        categories.put("VCS Bridge", new ArrayList<>());
        categories.put("Branch Cleanup", new ArrayList<>());

        for (GoalInfo g : goals) {
            categories.get(categoryOf(g.name)).add(g);
        }

        // Remove empty categories
        categories.values().removeIf(List::isEmpty);
        return categories;
    }

    /**
     * Determine the category for a goal name.
     *
     * @param name goal name (without ws: prefix)
     * @return category name
     */
    private static String categoryOf(String name) {
        if (name.startsWith("feature-") || name.startsWith("update-feature")
                || name.startsWith("switch") || name.startsWith("sibling")) {
            return "Feature Branching";
        }
        if (name.startsWith("release") || name.startsWith("checkpoint")
                || name.equals("post-release") || name.equals("release-notes")) {
            return "Release & Checkpoint";
        }
        if (name.startsWith("set-parent") || name.startsWith("align")) {
            return "Parent & Version Alignment";
        }
        if (name.startsWith("commit") || name.equals("push")
                || name.equals("sync") || name.equals("check-branch")) {
            return "VCS Bridge";
        }
        if (name.startsWith("cleanup")) {
            return "Branch Cleanup";
        }
        return "Workspace Management";
    }

    // ── Options ─────────────────────────────────────────────────

    /**
     * Print common option groups. These remain static because they
     * document cross-cutting parameters, not individual goal metadata.
     */
    private void printOptions() {
        getLog().info("Common options:");
        getLog().info("  -Dworkspace.manifest=<path>   Path to workspace.yaml (auto-detected)");
        getLog().info("  -Dpublish=true                Execute (most goals default to draft)");
        getLog().info("");
        getLog().info("Parent version:");
        getLog().info("  -DparentVersion=<version>     Pin parent version for "
                + WsGoal.SCAFFOLD_PUBLISH.qualified() + " cascade");
        getLog().info("  -DupdateParent=false          Skip parent cascade in "
                + WsGoal.SCAFFOLD_PUBLISH.qualified());
        getLog().info("");
        getLog().info("Feature branching:");
        getLog().info("  -Dfeature=<name>              Feature name (branch: feature/<name>)");
        getLog().info("  -DskipVersion=true            Skip POM version qualification");
        getLog().info("  -DtargetBranch=<name>         Merge target (default: main)");
        getLog().info("  -DkeepBranch=true             Keep branch after merge");
        getLog().info("  -Dmessage=<msg>               Commit/squash message");
        getLog().info("");
        getLog().info("Release & checkpoint:");
        getLog().info("  -Dname=<name>                 Checkpoint name (auto-derived)");
        getLog().info("  -DdeploySite=true             Deploy site for each subproject");
        getLog().info("  -Dpush=true                   Push to origin");
    }

    // ── Internal record ─────────────────────────────────────────

    /**
     * A discovered goal with its registry entry, name and summary
     * description.
     *
     * @param goal    the {@link WsGoal} registry entry
     * @param name    goal name (without prefix)
     * @param summary first sentence of the javadoc description
     */
    private record GoalInfo(WsGoal goal, String name, String summary) {}
}
