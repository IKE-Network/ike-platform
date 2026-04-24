package network.ike.plugin.ws;

import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.plugin.descriptor.MojoDescriptor;
import org.apache.maven.api.plugin.descriptor.PluginDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Displays available ws: workspace goals, auto-discovered from the
 * Maven plugin descriptor.
 *
 * <p>Goal names and descriptions are read from the
 * {@link PluginDescriptor} injected by Maven at runtime, so the help
 * output is always in sync with the actual plugin — no manual list to
 * maintain.
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
public class WsHelpMojo implements Mojo {

    /** Maven logger, injected by the Maven 4 DI container. */
    @Inject
    private Log log;

    /** The plugin descriptor, injected by Maven 4 DI. */
    @Inject
    PluginDescriptor pluginDescriptor;

    /** Creates this goal instance. */
    public WsHelpMojo() {}

    /** Access the Maven logger. */
    private Log getLog() { return log; }

    @Override
    public void execute() throws MojoException {
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
                String goalName = "ws:" + g.name;
                String padding = " ".repeat(
                        Math.max(1, 46 - goalName.length()));
                getLog().info("  " + goalName + padding + g.summary);
            }
        }

        getLog().info("");
        printOptions();
        getLog().info("");
    }

    // ── Goal discovery ──────────────────────────────────────────

    /**
     * Read goal names and descriptions from the Maven
     * {@link PluginDescriptor} injected at runtime.
     *
     * @return list of discovered goals, sorted by name
     */
    private List<GoalInfo> discoverGoals() {
        List<GoalInfo> goals = new ArrayList<>();

        if (pluginDescriptor == null) {
            getLog().warn("Plugin descriptor not available — "
                    + "cannot discover goals");
            return goals;
        }

        for (MojoDescriptor mojo : pluginDescriptor.getMojos()) {
            String goal = mojo.getGoal();
            if (goal == null || goal.isBlank()) continue;

            String description = mojo.getDescription();
            String summary = firstSentence(description);
            goals.add(new GoalInfo(goal, summary));
        }

        goals.sort(Comparator.comparing(g -> g.name));
        return goals;
    }

    /**
     * Extract the first sentence from a description string.
     * Trims at the first period followed by whitespace, or at 80 chars.
     *
     * @param description full description
     * @return first sentence
     */
    private static String firstSentence(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        // Collapse whitespace
        String s = description.replaceAll("\\s+", " ").trim();

        // Find first sentence break
        int dot = s.indexOf(". ");
        if (dot > 0 && dot < 80) {
            return s.substring(0, dot + 1);
        }

        // Truncate if needed
        if (s.length() > 80) {
            return s.substring(0, 77) + "...";
        }
        return s;
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
                || name.startsWith("switch")) {
            return "Feature Branching";
        }
        if (name.startsWith("release") || name.startsWith("checkpoint")
                || name.equals("post-release") || name.equals("release-notes")) {
            return "Release & Checkpoint";
        }
        if (name.startsWith("set-parent") || name.startsWith("align")) {
            return "Parent & Version Alignment";
        }
        if (name.equals("commit") || name.equals("push")
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
        getLog().info("  -Dparent.version=<version>    Target parent version (ws:set-parent)");
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
     * A discovered goal with its name and summary description.
     *
     * @param name    goal name (without prefix)
     * @param summary first sentence of the javadoc description
     */
    private record GoalInfo(String name, String summary) {}
}
