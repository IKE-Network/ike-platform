package network.ike.plugin.ws;

import network.ike.workspace.ManifestWriter;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Switch all workspace subprojects to a different branch with optional
 * auto-stash.
 *
 * <p>Discovers all local feature branches across subprojects and presents
 * an interactive menu. The selected branch is checked out in every
 * subproject that has it locally; subprojects without the branch are
 * skipped with a warning.
 *
 * <p>By default, uncommitted work is automatically stashed to a pushable
 * custom ref ({@code refs/ws-stash/<user-slug>/<branch>}) on origin
 * before switching, and any pre-existing stash on the target branch is
 * fetched and applied after switching — work follows you across
 * branches and machines (see #153). The stash ref is per-user
 * (keyed by {@code git config user.email}) so multiple developers on
 * the same repository have isolated stashes.
 *
 * <p>Pass {@code -DnoStash=true} to restore the pre-feature behavior:
 * uncommitted changes fail the goal with the working-tree-clean
 * preflight.
 *
 * <p>After switching, updates workspace.yaml branch fields and commits
 * the change.
 *
 * <pre>{@code
 * mvn ws:switch                        # interactive menu + auto-stash
 * mvn ws:switch -Dbranch=feature/foo   # non-interactive
 * mvn ws:switch -Dbranch=main          # switch all to main
 * mvn ws:switch -DnoStash=true         # fail on uncommitted work
 * }</pre>
 *
 * @see FeatureStartDraftMojo for creating feature branches
 */
@Mojo(name = "switch-draft", projectRequired = false)
public class WsSwitchDraftMojo extends AbstractWorkspaceMojo {

    /** Full ref prefix for auto-stash refs (see #153). */
    static final String STASH_REF_PREFIX = "refs/ws-stash/";

    /** Remote name for auto-stash push/fetch/delete. */
    static final String STASH_REMOTE = "origin";

    /** Creates this goal instance. */
    public WsSwitchDraftMojo() {}

    /**
     * Target branch to switch to. If omitted, presents an interactive
     * menu of available branches.
     */
    @Parameter(property = "branch")
    String branch;

    /** Execute the switch. Default is draft (preview only). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /**
     * Opt out of auto-stash. When {@code true}, uncommitted changes
     * fail the goal (pre-#153 behavior); when {@code false} (default),
     * uncommitted work is stashed to {@code refs/ws-stash/...} on
     * origin before switching and re-applied on return.
     */
    @Parameter(property = "noStash", defaultValue = "false")
    boolean noStash;

    @Override
    public void execute() throws MojoException {
        if (!isWorkspaceMode()) {
            throw new MojoException(
                    "ws:switch requires a workspace (workspace.yaml). "
                    + "Use 'git checkout <branch>' for single-repo switching.");
        }

        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        Set<String> targets = graph.manifest().subprojects().keySet();

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        // ── Discover branches ────────────────────────────────────
        // Map: branch name → set of subprojects that have it locally
        Map<String, Set<String>> branchSubprojects = new TreeMap<>();
        branchSubprojects.put("main", new TreeSet<>());

        String currentBranch;
        Map<String, Integer> branchCounts = new TreeMap<>();

        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            String compBranch = gitBranch(dir);
            branchCounts.merge(compBranch, 1, Integer::sum);

            // Add main for every cloned subproject
            branchSubprojects.get("main").add(name);

            // Discover all local feature branches
            List<String> localFeatures = VcsOperations.localBranches(dir, "feature/");
            for (String fb : localFeatures) {
                branchSubprojects.computeIfAbsent(fb, _ -> new TreeSet<>()).add(name);
            }
        }

        // Determine current branch (majority vote)
        currentBranch = branchCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("main");

        // ── Resolve target branch ────────────────────────────────
        if (branch == null || branch.isBlank()) {
            branch = promptForBranch(branchSubprojects, currentBranch);
        }

        if (branch.equals(currentBranch)) {
            getLog().info("Already on " + currentBranch + " — nothing to do.");
            return;
        }

        // Validate the target branch exists somewhere (or is main)
        if (!branch.equals("main") && !branchSubprojects.containsKey(branch)) {
            throw new MojoException(
                    "Branch '" + branch + "' does not exist in any subproject. "
                    + "Available: " + branchSubprojects.keySet());
        }

        getLog().info("");
        getLog().info(header("Switch"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  From:  " + currentBranch);
        getLog().info("  To:    " + branch);
        if (draft) getLog().info("  Mode:  DRAFT");
        if (noStash) getLog().info("  Stash: disabled (-DnoStash=true)");
        getLog().info("");

        // ── Preflight ─────────────────────────────────────────────
        if (noStash) {
            // Old behavior: hard-fail on uncommitted changes (#154).
            PreflightResult switchPreflight = Preflight.of(
                    List.of(PreflightCondition.WORKING_TREE_CLEAN),
                    PreflightContext.of(root, graph, sorted));
            if (draft) {
                switchPreflight.warnIfFailed(getLog(), WsGoal.SWITCH_PUBLISH);
            } else {
                switchPreflight.requirePassed(WsGoal.SWITCH_PUBLISH);
            }
        } else {
            // Auto-stash mode: run the #153 read-only preflight.
            runAutoStashPreflight(root, sorted, currentBranch, branch, draft);
        }

        // ── Per-subproject switch (with auto-stash) ──────────────
        String slug = noStash ? null : VcsOperations.userSlug(
                VcsOperations.userEmail(root));
        int switched = 0;
        int skipped = 0;
        int stashed = 0;
        int applied = 0;

        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                skipped++;
                continue;
            }

            String compBranch = gitBranch(dir);
            if (compBranch.equals(branch)) {
                getLog().info("  " + Ansi.green("✓ ") + name + " — already on " + branch);
                switched++;
                continue;
            }

            // Target-branch existence check: main is always valid; for
            // feature branches, the branch must exist locally.
            if (!branch.equals("main")) {
                List<String> localBranches = VcsOperations.localBranches(dir, "");
                if (!localBranches.contains(branch)) {
                    getLog().info("  " + Ansi.yellow("⚠ ") + name
                            + " — branch " + branch + " does not exist locally, skipping");
                    skipped++;
                    continue;
                }
            }

            if (draft) {
                getLog().info("  [draft] " + name + " — would switch "
                        + compBranch + " → " + branch);
                switched++;
                continue;
            }

            // ── Leave flow: stash work on source branch ──────────
            if (!noStash && !VcsOperations.isClean(dir)) {
                stashLeave(dir, slug, compBranch);
                stashed++;
            }

            // ── Checkout target ──────────────────────────────────
            getLog().info("  " + Ansi.cyan("→ ") + name + ": " + compBranch + " → " + branch);
            VcsOperations.checkout(dir, getLog(), branch);
            switched++;

            // ── Arrive flow: apply stash on target branch if any ─
            if (!noStash) {
                if (stashArrive(dir, slug, branch)) {
                    applied++;
                }
            }
        }

        // ── Update workspace.yaml ────────────────────────────────
        if (!draft && switched > 0) {
            updateWorkspaceYaml(sorted, branch);
            switchWorkspaceRepo(branch);
        }

        getLog().info("");
        var summaryParts = new StringBuilder();
        summaryParts.append("Switched: ").append(switched)
                .append(" | Skipped: ").append(skipped);
        if (stashed > 0) summaryParts.append(" | Stashed: ").append(stashed);
        if (applied > 0) summaryParts.append(" | Applied: ").append(applied);
        getLog().info("  " + summaryParts);
        getLog().info("");

        // Write report
        var sb = new StringBuilder();
        sb.append("**From:** `").append(currentBranch)
          .append("` **To:** `").append(branch).append("`\n\n");
        sb.append("**").append(switched).append("** switched, **")
          .append(skipped).append("** skipped");
        if (stashed > 0) sb.append(", **").append(stashed).append("** stashed");
        if (applied > 0) sb.append(", **").append(applied).append("** applied");
        sb.append(".\n");
        writeReport(publish ? WsGoal.SWITCH_PUBLISH : WsGoal.SWITCH_DRAFT,
                sb.toString());
    }

    /**
     * Build the auto-stash ref path for a given user slug and branch.
     * Branches with {@code /} in their name (e.g. {@code feature/A})
     * become multi-segment ref paths, which git supports.
     *
     * @param slug   user slug from {@link VcsOperations#userSlug(String)}
     * @param branch the branch name
     * @return full ref path, e.g.
     *         {@code "refs/ws-stash/kec--knowledge-design/feature/A"}
     */
    static String stashRef(String slug, String branch) {
        return STASH_REF_PREFIX + slug + "/" + branch;
    }

    /**
     * Execute the leave flow on a subproject with uncommitted work:
     * stash (including untracked), move stash to custom ref, drop local
     * stash entry, push ref to origin. A collision on the source ref is
     * detected at preflight; hitting it here means state changed between
     * preflight and execute (racy), so fail loudly.
     *
     * @param dir          the subproject directory
     * @param slug         user slug
     * @param sourceBranch the branch we're leaving
     * @throws MojoException if any step fails
     */
    private void stashLeave(File dir, String slug, String sourceBranch)
            throws MojoException {
        String ref = stashRef(slug, sourceBranch);
        String message = "ws-auto/" + sourceBranch;
        VcsOperations.stashPushUntracked(dir, getLog(), message);
        VcsOperations.updateRef(dir, getLog(), ref, "refs/stash");
        VcsOperations.stashDrop(dir, getLog());
        VcsOperations.pushRef(dir, getLog(), STASH_REMOTE, ref);
        getLog().info("    " + Ansi.yellow("↟ ") + "stashed → " + ref);
    }

    /**
     * Execute the arrive flow on a subproject that's just checked out
     * the target branch: probe for a remote stash ref for this
     * user/branch; if present, fetch it, apply it, and delete the ref
     * locally and remotely.
     *
     * @param dir          the subproject directory
     * @param slug         user slug
     * @param targetBranch the branch we just switched to
     * @return {@code true} if a stash was applied, {@code false} if no
     *         stash was present
     * @throws MojoException if the apply or cleanup fails
     */
    private boolean stashArrive(File dir, String slug, String targetBranch)
            throws MojoException {
        String ref = stashRef(slug, targetBranch);
        boolean present;
        try {
            present = VcsOperations.remoteRefExists(dir, STASH_REMOTE, ref);
        } catch (MojoException e) {
            getLog().warn("    " + Ansi.yellow("⚠ ") + "could not probe "
                    + STASH_REMOTE + " for " + ref + " — " + e.getMessage());
            return false;
        }
        if (!present) return false;

        VcsOperations.fetchRef(dir, getLog(), STASH_REMOTE, ref);
        VcsOperations.stashApply(dir, getLog(), ref);
        VcsOperations.deleteLocalRef(dir, getLog(), ref);
        VcsOperations.deleteRemoteRef(dir, getLog(), STASH_REMOTE, ref);
        getLog().info("    " + Ansi.green("↡ ") + "stash applied from " + ref);
        return true;
    }

    /**
     * Read-only preflight for the auto-stash switch flow. Verifies:
     * user.email is configured, the workspace root is reachable at
     * origin, source-branch stash collisions are absent, and per-
     * subproject target-branch stash presence is reported (informational).
     *
     * <p>On any hard failure (missing user.email, source-stash
     * collision), draft mode throws — matching the #154 contract that
     * draft exits non-zero when publish would fail.
     *
     * @param root          workspace root directory
     * @param sorted        subprojects in topological order
     * @param sourceBranch  the branch we're leaving
     * @param targetBranch  the branch we're arriving at
     * @param draft         whether this is a draft (report) or publish run
     * @throws MojoException on preflight failure
     */
    private void runAutoStashPreflight(File root, List<String> sorted,
                                       String sourceBranch, String targetBranch,
                                       boolean draft) throws MojoException {
        // 1) user.email configured (workspace root is the reference point)
        String email;
        try {
            email = VcsOperations.userEmail(root);
        } catch (MojoException e) {
            throw new MojoException(
                    "ws:switch requires git user.email. Set it with "
                            + "`git config --global user.email <your-email>`.");
        }
        String slug = VcsOperations.userSlug(email);

        // 2) Per-subproject checks: source-stash collision + target-stash probe
        List<String> collisions = new ArrayList<>();
        List<String> targetStashes = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();

        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) continue;

            boolean hasWip = !VcsOperations.isClean(dir);
            if (hasWip) {
                String sourceRef = stashRef(slug, sourceBranch);
                try {
                    if (VcsOperations.remoteRefExists(dir, STASH_REMOTE, sourceRef)) {
                        collisions.add(name + " (" + sourceRef + ")");
                    }
                } catch (MojoException e) {
                    unreachable.add(name);
                }
            }

            String targetRef = stashRef(slug, targetBranch);
            try {
                if (VcsOperations.remoteRefExists(dir, STASH_REMOTE, targetRef)) {
                    targetStashes.add(name);
                }
            } catch (MojoException e) {
                if (!unreachable.contains(name)) unreachable.add(name);
            }
        }

        // 3) Report findings
        if (!targetStashes.isEmpty()) {
            getLog().info("  Target-branch stashes found (will be applied): "
                    + targetStashes.size() + " subproject(s)");
        }
        if (!unreachable.isEmpty()) {
            getLog().warn("  " + Ansi.yellow("⚠ ") + "Could not reach "
                    + STASH_REMOTE + " for: " + unreachable);
            getLog().warn("    The switch will proceed but stash operations "
                    + "may fail mid-flight.");
        }

        if (!collisions.isEmpty()) {
            String msg = "Refusing to switch: pre-existing stash ref(s) would "
                    + "be overwritten:\n"
                    + collisions.stream()
                            .reduce((a, b) -> a + "\n  " + b)
                            .map(s -> "  " + s)
                            .orElse("")
                    + "\n\nResolve manually (per subproject):\n"
                    + "  # recover the old stash:\n"
                    + "  git fetch origin " + stashRef(slug, sourceBranch) + ":"
                    + stashRef(slug, sourceBranch) + "\n"
                    + "  git stash apply " + stashRef(slug, sourceBranch) + "\n"
                    + "  # OR, if the old stash is obsolete:\n"
                    + "  git push origin :" + stashRef(slug, sourceBranch) + "\n"
                    + "\nThen retry ws:switch.";
            if (draft) {
                getLog().warn("");
                getLog().warn(msg);
                getLog().warn("");
                throw new MojoException(
                        "ws:switch preflight failed — source-branch stash "
                                + "collision. See warnings above.");
            } else {
                throw new MojoException(msg);
            }
        }
    }

    /**
     * Present an interactive menu of available branches and prompt for selection.
     *
     * @param branchSubprojects map of branch name to subprojects that have it
     * @param currentBranch     the current majority branch
     * @return the selected branch name
     * @throws MojoException if no console or invalid selection
     */
    private String promptForBranch(Map<String, Set<String>> branchSubprojects,
                                    String currentBranch)
            throws MojoException {
        java.io.Console console = System.console();
        if (console == null) {
            throw new MojoException(
                    "No interactive console available. Use -Dbranch=<name> to specify target.");
        }

        List<String> branches = new ArrayList<>(branchSubprojects.keySet());

        getLog().info("");
        getLog().info(header("Switch"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");
        getLog().info("  Available branches:");
        getLog().info("");
        for (int i = 0; i < branches.size(); i++) {
            String b = branches.get(i);
            int count = branchSubprojects.get(b).size();
            String current = b.equals(currentBranch) ? " (current)" : "";
            getLog().info("    " + (i + 1) + ". " + b
                    + " (" + count + " subproject" + (count == 1 ? "" : "s") + ")"
                    + current);
        }
        getLog().info("");

        String input = console.readLine("  Select branch [1-" + branches.size() + "]: ");
        if (input == null || input.isBlank()) {
            throw new MojoException("No selection made.");
        }

        try {
            int idx = Integer.parseInt(input.trim()) - 1;
            if (idx < 0 || idx >= branches.size()) {
                throw new MojoException(
                        "Invalid selection: " + input + ". Expected 1-" + branches.size());
            }
            return branches.get(idx);
        } catch (NumberFormatException e) {
            // Allow typing the branch name directly
            String typed = input.trim();
            if (branchSubprojects.containsKey(typed) || "main".equals(typed)) {
                return typed;
            }
            throw new MojoException(
                    "Unknown branch: " + typed + ". Available: " + branchSubprojects.keySet());
        }
    }

    /**
     * Update workspace.yaml branch fields and commit the change.
     */
    private void updateWorkspaceYaml(List<String> subprojects, String targetBranch)
            throws MojoException {
        try {
            Path manifestPath = resolveManifest();
            Map<String, String> updates = new LinkedHashMap<>();
            for (String name : subprojects) {
                updates.put(name, targetBranch);
            }
            ManifestWriter.updateBranches(manifestPath, updates);
            getLog().info("  Updated workspace.yaml branches → " + targetBranch);
        } catch (IOException e) {
            getLog().warn("  Could not update workspace.yaml: " + e.getMessage());
        }
    }

    /**
     * Switch the workspace repo itself to the target branch and commit
     * the workspace.yaml update.
     */
    private void switchWorkspaceRepo(String targetBranch) throws MojoException {
        try {
            Path manifestPath = resolveManifest();
            File wsRoot = manifestPath.getParent().toFile();
            if (!new File(wsRoot, ".git").exists()) return;

            String wsBranch = VcsOperations.currentBranch(wsRoot);
            if (!wsBranch.equals(targetBranch)) {
                // Check if target branch exists locally in workspace repo
                List<String> localBranches = VcsOperations.localBranches(wsRoot, "");
                if (localBranches.contains(targetBranch) || "main".equals(targetBranch)) {
                    getLog().info("  Workspace repo: " + wsBranch + " → " + targetBranch);
                    VcsOperations.checkout(wsRoot, getLog(), targetBranch);
                }
            }

            // Stage and commit workspace.yaml if changed
            network.ike.plugin.ReleaseSupport.exec(
                    wsRoot, getLog(), "git", "add", "workspace.yaml");
            if (VcsOperations.hasStagedChanges(wsRoot)) {
                VcsOperations.commit(wsRoot, getLog(),
                        "workspace: switch branches to " + targetBranch);
            }

            VcsOperations.writeVcsState(wsRoot, VcsState.Action.SWITCH);
        } catch (MojoException e) {
            getLog().warn("  Could not switch workspace repo: " + e.getMessage());
        }
    }
}
