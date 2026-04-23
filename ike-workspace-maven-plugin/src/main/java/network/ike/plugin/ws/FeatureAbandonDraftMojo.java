package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;

import network.ike.workspace.Subproject;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Abandon a feature branch across all workspace subprojects.
 *
 * <p>The draft variant previews what would be abandoned — which components,
 * how many unmerged commits, what would be lost. The publish variant
 * prompts for confirmation then executes the deletion.
 *
 * <p>Components are processed in reverse topological order (downstream
 * first) to avoid transient dependency issues.
 *
 * <pre>{@code
 * mvn ws:feature-abandon-draft                       # preview
 * mvn ws:feature-abandon-publish                     # execute (with confirmation)
 * mvn ws:feature-abandon-publish -Dforce=true        # skip confirmation
 * mvn ws:feature-abandon-publish -DdeleteRemote=true # also delete remote branches
 * }</pre>
 *
 * @see FeatureStartDraftMojo for creating feature branches
 */
@Mojo(name = "feature-abandon-draft", projectRequired = false)
public class FeatureAbandonDraftMojo extends AbstractWorkspaceMojo {

    @Parameter(property = "feature")
    String feature;

    @Parameter(property = "targetBranch")
    String targetBranch;

    @Parameter(property = "deleteRemote", defaultValue = "false")
    boolean deleteRemote;

    @Parameter(property = "force", defaultValue = "false")
    boolean force;

    /** Execute the abandon. Default is draft (preview only). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Creates this goal instance. */
    public FeatureAbandonDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        if (!isWorkspaceMode()) {
            executeBareMode();
            return;
        }

        executeWorkspaceMode();
    }

    private void executeWorkspaceMode() throws MojoException {
        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        if (targetBranch == null || targetBranch.isBlank()) {
            targetBranch = graph.manifest().defaults().branch();
            if (targetBranch == null) targetBranch = "main";
        }

        Set<String> targets = graph.manifest().subprojects().keySet();

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));
        List<String> reversed = new ArrayList<>(sorted);
        Collections.reverse(reversed);

        // Preflight: all working trees must be clean (#132)
        PreflightResult preflight = Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, sorted));
        if (draft) {
            preflight.warnIfFailed(getLog(), WsGoal.FEATURE_ABANDON_PUBLISH);
        } else {
            preflight.requirePassed(WsGoal.FEATURE_ABANDON_PUBLISH);
        }

        // Auto-detect feature branch if not specified
        if (feature == null || feature.isBlank()) {
            feature = detectFeatureBranch(root, reversed);
        }
        String branchName = "feature/" + feature;

        // Collect eligible components and show preview
        getLog().info("");
        getLog().info(header("Feature Abandon"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
        if (deleteRemote) getLog().info("  Remote:   will delete origin/" + branchName);
        if (draft) getLog().info("  Mode:     DRAFT");
        getLog().info("");

        List<String> eligible = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String[]> reportRows = new ArrayList<>();
        int totalUnmerged = 0;

        for (String name : reversed) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().info(Ansi.yellow("  · ") + name + " — not cloned");
                skipped.add(name);
                reportRows.add(new String[]{name, "not cloned", "0"});
                continue;
            }

            String currentBranch = gitBranch(dir);
            if (!currentBranch.equals(branchName)) {
                getLog().info(Ansi.yellow("  · ") + name + " — on "
                        + currentBranch + ", not on feature");
                skipped.add(name);
                reportRows.add(new String[]{name, "not on feature", "0"});
                continue;
            }

            // Check for uncommitted changes
            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                throw new MojoException(
                        name + " has uncommitted changes. Commit, stash, or discard before abandoning.");
            }

            // Check for unmerged commits
            int unmergedCount = 0;
            try {
                String unmerged = ReleaseSupport.execCapture(dir,
                        "git", "log", "--oneline",
                        targetBranch + ".." + branchName);
                if (!unmerged.isBlank()) {
                    unmergedCount = (int) unmerged.lines().count();
                }
            } catch (MojoException e) {
                // Target branch may not exist locally
            }

            totalUnmerged += unmergedCount;

            if (unmergedCount > 0) {
                String label = draft ? "would abandon" : "abandon";
                getLog().info(Ansi.yellow("  ⚠ ") + name + " — "
                        + unmergedCount + " unmerged commit(s) — " + label);
            } else {
                String label = draft ? "would abandon (clean)" : "abandon";
                getLog().info(Ansi.cyan("  → ") + name + " — " + label);
            }
            eligible.add(name);
            reportRows.add(new String[]{name,
                    draft ? "would abandon" : "abandoned",
                    String.valueOf(unmergedCount)});
        }

        if (eligible.isEmpty()) {
            getLog().info("  No components on " + branchName + " — nothing to abandon.");
            getLog().info("");
            return;
        }

        getLog().info("");
        getLog().info("  " + eligible.size() + " subproject(s) on " + branchName);
        if (totalUnmerged > 0) {
            getLog().warn("  " + totalUnmerged + " total unmerged commit(s) will be lost");
        }

        if (draft) {
            getLog().info("");
            getLog().info("  Next: mvn ws:feature-abandon-publish"
                    + (force ? "" : " (will prompt for confirmation)"));
            getLog().info("");
            writeAbandonReport(branchName, reportRows, eligible, skipped, draft);
            return;
        }

        // Publish mode — prompt for confirmation
        if (!force) {
            java.io.Console console = System.console();
            if (console != null) {
                String response = console.readLine(
                        Ansi.YELLOW + "  Abandon feature/%s? (yes/no): " + Ansi.RESET,
                        feature);
                if (response == null || !response.trim().toLowerCase().startsWith("y")) {
                    throw new MojoException("Abandon cancelled.");
                }
            } else {
                throw new MojoException(
                        "No interactive console for confirmation. Use -Dforce=true to skip.");
            }
        }

        // Execute
        for (String name : eligible) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);

            // Strip branch-qualified versions before switching
            FeatureFinishSupport.stripBranchVersion(dir, subproject, branchName, getLog());

            VcsOperations.checkout(dir, getLog(), targetBranch);
            VcsOperations.deleteBranch(dir, getLog(), branchName);

            if (deleteRemote) {
                try {
                    VcsOperations.deleteRemoteBranch(dir, getLog(), "origin", branchName);
                } catch (MojoException e) {
                    getLog().warn("    could not delete remote branch: " + e.getMessage());
                }
            }

            VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);
            getLog().info(Ansi.green("  ✓ ") + name + " → " + targetBranch);
        }

        // Update workspace.yaml and workspace repo
        if (!eligible.isEmpty()) {
            abandonWorkspaceRepo(manifestPath, eligible, branchName);
        }

        getLog().info("");
        getLog().info("  Abandoned: " + eligible.size()
                + " | Skipped: " + skipped.size());
        if (!deleteRemote) {
            getLog().info("  Remote branches kept. Use -DdeleteRemote=true to delete them.");
        }
        getLog().info("");

        writeAbandonReport(branchName, reportRows, eligible, skipped, draft);
    }

    private void writeAbandonReport(String branchName, List<String[]> rows,
                                     List<String> eligible, List<String> skipped,
                                     boolean isDraft) {
        var sb = new StringBuilder();
        sb.append("**Branch:** `").append(branchName).append("`\n\n");
        sb.append("| Subproject | Status | Unmerged Commits |\n");
        sb.append("|-----------|--------|------------------|\n");
        for (String[] row : rows) {
            sb.append("| ").append(row[0])
              .append(" | ").append(row[1])
              .append(" | ").append(row[2])
              .append(" |\n");
        }
        sb.append("\n**").append(eligible.size()).append("** ")
          .append(isDraft ? "would be abandoned" : "abandoned")
          .append(", **").append(skipped.size()).append("** skipped.\n");
        writeReport(publish ? WsGoal.FEATURE_ABANDON_PUBLISH : WsGoal.FEATURE_ABANDON_DRAFT,
                sb.toString());
    }

    // ── Auto-detect ─────────────────────────────────────────────────

    /**
     * Scan workspace subprojects for feature branches and return
     * the feature name. If multiple features are found, prompts
     * the user to choose.
     */
    private String detectFeatureBranch(File root, List<String> components)
            throws MojoException {
        Set<String> features = new TreeSet<>();

        for (String name : components) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            String branch = gitBranch(dir);
            if (branch.startsWith("feature/")) {
                features.add(branch.substring("feature/".length()));
            }
        }

        if (features.isEmpty()) {
            throw new MojoException(
                    "No components are on a feature branch. Nothing to abandon.");
        }

        if (features.size() == 1) {
            String detected = features.iterator().next();
            getLog().info("  Detected feature: " + detected);
            return detected;
        }

        // Multiple features — list them and prompt
        getLog().info("  Multiple feature branches detected:");
        int i = 1;
        List<String> featureList = new ArrayList<>(features);
        for (String f : featureList) {
            getLog().info("    " + i + ". " + f);
            i++;
        }

        java.io.Console console = System.console();
        if (console != null) {
            String response = console.readLine(
                    Ansi.YELLOW + "  Feature to abandon (name or number): " + Ansi.RESET);
            if (response != null && !response.isBlank()) {
                String trimmed = response.trim();
                try {
                    int idx = Integer.parseInt(trimmed) - 1;
                    if (idx >= 0 && idx < featureList.size()) {
                        return featureList.get(idx);
                    }
                } catch (NumberFormatException _) {
                    // Not a number — treat as name
                }
                return trimmed;
            }
        }

        throw new MojoException(
                "Multiple features found: " + features
                        + ". Specify with -Dfeature=<name>.");
    }

    // ── Bare mode ───────────────────────────────────────────────────

    private void executeBareMode() throws MojoException {
        boolean draft = !publish;
        File dir = new File(System.getProperty("user.dir"));

        if (targetBranch == null || targetBranch.isBlank()) {
            targetBranch = "main";
        }

        String currentBranch = gitBranch(dir);
        if (feature == null || feature.isBlank()) {
            if (currentBranch.startsWith("feature/")) {
                feature = currentBranch.substring("feature/".length());
            } else {
                throw new MojoException(
                        "Not on a feature branch (on " + currentBranch
                                + "). Specify with -Dfeature=<name>.");
            }
        }
        String branchName = "feature/" + feature;

        getLog().info("");
        getLog().info("IKE Feature Abandon (bare repo)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName + " → " + targetBranch);
        if (draft) getLog().info("  Mode:    DRAFT");
        getLog().info("");

        if (!currentBranch.equals(branchName)) {
            throw new MojoException(
                    "Not on " + branchName + " (currently on " + currentBranch + ")");
        }
        if (!gitStatus(dir).isEmpty()) {
            throw new MojoException(
                    "Uncommitted changes. Commit, stash, or discard first.");
        }

        if (draft) {
            getLog().info("  [draft] Would abandon " + branchName
                    + " and switch to " + targetBranch);
            getLog().info("");
            getLog().info("  Next: mvn ws:feature-abandon-publish");
            getLog().info("");
            return;
        }

        // Publish mode — prompt for confirmation
        if (!force) {
            java.io.Console console = System.console();
            if (console != null) {
                String response = console.readLine(
                        Ansi.YELLOW + "  Abandon feature/%s? (yes/no): " + Ansi.RESET,
                        feature);
                if (response == null || !response.trim().toLowerCase().startsWith("y")) {
                    throw new MojoException("Abandon cancelled.");
                }
            } else {
                throw new MojoException(
                        "No interactive console for confirmation. Use -Dforce=true to skip.");
            }
        }

        FeatureFinishSupport.stripBranchVersionBare(dir, branchName, getLog());

        VcsOperations.checkout(dir, getLog(), targetBranch);
        VcsOperations.deleteBranch(dir, getLog(), branchName);
        getLog().info(Ansi.green("  ✓ ") + "Switched to " + targetBranch
                + ", deleted " + branchName);

        if (deleteRemote) {
            try {
                VcsOperations.deleteRemoteBranch(dir, getLog(), "origin", branchName);
                getLog().info(Ansi.green("  ✓ ") + "Deleted remote branch");
            } catch (MojoException e) {
                getLog().warn("  Could not delete remote branch: " + e.getMessage());
            }
        }

        VcsOperations.writeVcsState(dir, VcsState.Action.FEATURE_FINISH);
        getLog().info("");
    }

    // ── Workspace repo cleanup ──────────────────────────────────────

    private void abandonWorkspaceRepo(Path manifestPath,
                                       List<String> components,
                                       String branchName)
            throws MojoException {
        try {
            Map<String, String> updates = new LinkedHashMap<>();
            for (String name : components) {
                updates.put(name, targetBranch);
            }
            ManifestWriter.updateBranches(manifestPath, updates);

            File wsRoot = manifestPath.getParent().toFile();
            if (!new File(wsRoot, ".git").exists()) return;

            String wsBranch = gitBranch(wsRoot);
            if (wsBranch.equals(branchName)) {
                ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                if (VcsOperations.hasStagedChanges(wsRoot)) {
                    VcsOperations.commit(wsRoot, getLog(),
                            "workspace: revert branches for abandon " + branchName);
                }

                VcsOperations.checkout(wsRoot, getLog(), targetBranch);

                try {
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "cherry-pick", branchName);
                } catch (MojoException e) {
                    ManifestWriter.updateBranches(manifestPath, updates);
                    ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                    if (VcsOperations.hasStagedChanges(wsRoot)) {
                        VcsOperations.commit(wsRoot, getLog(),
                                "workspace: revert branches after abandon " + branchName);
                    }
                }

                VcsOperations.deleteBranch(wsRoot, getLog(), branchName);

                if (deleteRemote) {
                    try {
                        VcsOperations.deleteRemoteBranch(wsRoot, getLog(), "origin", branchName);
                    } catch (MojoException e) {
                        getLog().warn("  Could not delete workspace remote branch: "
                                + e.getMessage());
                    }
                }
            } else {
                ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                if (VcsOperations.hasStagedChanges(wsRoot)) {
                    VcsOperations.commit(wsRoot, getLog(),
                            "workspace: revert branches after abandon " + branchName);
                }
            }

            VcsOperations.pushIfRemoteExists(wsRoot, getLog(), "origin", targetBranch);

        } catch (IOException e) {
            getLog().warn("  Could not update workspace.yaml: " + e.getMessage());
        }
    }
}
