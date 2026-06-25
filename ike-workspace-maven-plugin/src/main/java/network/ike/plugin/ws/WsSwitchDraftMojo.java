package network.ike.plugin.ws;

import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkingSet;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.bootstrap.SubprojectInitializer;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
@Mojo(name = "switch-draft", projectRequired = false, aggregator = true)
public class WsSwitchDraftMojo extends AbstractWorkspaceMojo {

    /** Full ref prefix for auto-stash refs (see #153). */
    static final String STASH_REF_PREFIX = ParkSupport.STASH_REF_PREFIX;

    /** Remote name for auto-stash push/fetch/delete. */
    static final String STASH_REMOTE = ParkSupport.STASH_REMOTE;

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
    protected WorkspaceReportSpec runGoal() throws MojoException {
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
            return new WorkspaceReportSpec(
                    publish ? WsGoal.SWITCH_PUBLISH : WsGoal.SWITCH_DRAFT,
                    "Already on `" + currentBranch + "` — nothing to do.\n");
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
        int parked = 0;
        int restored = 0;
        // Draft-mode preview tallies (publish uses the live counters above).
        List<String> wouldStash = new ArrayList<>();
        List<String> wouldRestore = new ArrayList<>();
        List<String> wouldPark = new ArrayList<>();
        // Per-member effect for the working-set table (#764/#767): keyed by
        // subproject name; the aggregator's effect is recorded after the loop.
        Map<String, String> effects = new java.util.LinkedHashMap<>();

        // Subprojects declared on the target branch (#573). A current
        // subproject absent here is parked rather than force-switched; null
        // means membership is indeterminate (treat all as members).
        Set<String> targetMembers = targetBranchMembers(root, branch);

        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                effects.put(name, "skipped (not cloned)");
                skipped++;
                continue;
            }

            String compBranch = gitBranch(dir);

            // ── Park: <name> is not a member of the target branch (#573) ─
            // Set it aside (origin is the park) rather than force-switch it
            // onto a branch whose workspace.yaml doesn't declare it.
            boolean targetMember = targetMembers == null
                    || targetMembers.contains(name);
            if (!targetMember) {
                if (draft) {
                    boolean willStash = !noStash && !VcsOperations.isClean(dir);
                    if (willStash) wouldStash.add(name);
                    wouldPark.add(name);
                    getLog().info("  [draft] " + name + " — would PARK (not a "
                            + "member of " + branch + ")"
                            + (willStash ? " (stash first)" : ""));
                    effects.put(name, "would park (not on `" + branch + "`)"
                            + (willStash ? " — stash first" : ""));
                    parked++;
                    continue;
                }
                if (!noStash && !VcsOperations.isClean(dir)) {
                    ParkSupport.stashLeave(dir, getLog(), slug, compBranch);
                    stashed++;
                }
                parkSubproject(dir, name, compBranch);
                effects.put(name, "parked — branch pushed to " + STASH_REMOTE
                        + ", clone removed");
                parked++;
                continue;
            }

            if (compBranch.equals(branch)) {
                getLog().info("  " + Ansi.green("✓ ") + name + " — already on " + branch);
                effects.put(name, "already on `" + branch + "`");
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
                    effects.put(name, "skipped (branch `" + branch
                            + "` absent locally)");
                    skipped++;
                    continue;
                }
            }

            if (draft) {
                // Mirror the publish branch's decisions without executing,
                // so the report previews exactly what would be stashed and
                // restored (post-skip, per subproject).
                boolean willStash = !noStash && !VcsOperations.isClean(dir);
                boolean willRestore = !noStash
                        && draftHasTargetStash(dir, slug, branch);
                if (willStash) wouldStash.add(name);
                if (willRestore) wouldRestore.add(name);
                String note = willStash && willRestore ? " (would stash + restore)"
                        : willStash ? " (would stash)"
                        : willRestore ? " (would restore)"
                        : "";
                getLog().info("  [draft] " + name + " — would switch "
                        + compBranch + " → " + branch + note);
                effects.put(name, "would switch `" + compBranch + "` → `"
                        + branch + "`" + note);
                switched++;
                continue;
            }

            // ── Leave flow: stash work on source branch ──────────
            boolean didStash = false;
            if (!noStash && !VcsOperations.isClean(dir)) {
                ParkSupport.stashLeave(dir, getLog(), slug, compBranch);
                stashed++;
                didStash = true;
            }

            // ── Checkout target ──────────────────────────────────
            getLog().info("  " + Ansi.cyan("→ ") + name + ": " + compBranch + " → " + branch);
            VcsOperations.checkout(dir, getLog(), branch);
            switched++;

            // ── Arrive flow: apply stash on target branch if any ─
            boolean didApply = false;
            if (!noStash) {
                if (ParkSupport.stashArrive(dir, getLog(), slug, branch)) {
                    applied++;
                    didApply = true;
                }
            }
            String note = didStash && didApply ? " (stashed + applied)"
                    : didStash ? " (stashed)"
                    : didApply ? " (applied)"
                    : "";
            effects.put(name, "switched `" + compBranch + "` → `" + branch
                    + "`" + note);
        }

        // ── Switch the workspace root, then restore target-only members ──
        // The target branch's committed workspace.yaml is authoritative
        // (#573): switch the root to it (no source-side pre-edit, so the
        // checkout doesn't conflict on a branch-divergent workspace), then
        // clone any declared-but-missing member that was previously parked.
        if (!draft && (switched > 0 || parked > 0)) {
            switchWorkspaceRepo(branch);
            restored = restoreMembers(root, slug);
        }

        // ── Aggregator (workspace root) effect for the working-set table ──
        // The root is a first-class member (#764); record what the switch did
        // (or would do) to it so the table never hides a stale aggregator left
        // on the source branch (#763). The root's directory always exists.
        String rootName = root.getName();
        if (draft) {
            effects.put(rootName, currentBranch.equals(branch)
                    ? "would stay on `" + currentBranch + "`"
                    : "would switch `" + currentBranch + "` → `" + branch + "`");
        } else {
            String rootBranch = gitBranch(root);
            String rootEffect = rootBranch.equals(branch)
                    ? "switched to `" + branch + "`"
                    : "stayed on `" + rootBranch + "`";
            if (restored > 0) {
                rootEffect += "; restored " + restored + " parked member"
                        + (restored == 1 ? "" : "s");
            }
            effects.put(rootName, rootEffect);
        }

        // ── Console summary ──────────────────────────────────────
        getLog().info("");
        if (draft) {
            StringBuilder s = new StringBuilder();
            s.append(switched).append(" to switch, ")
                    .append(skipped).append(" to skip");
            if (!wouldPark.isEmpty()) {
                s.append(", ").append(wouldPark.size()).append(" to park");
            }
            if (!noStash) {
                s.append(" | would stash: ").append(wouldStash.size())
                        .append(" | would restore: ").append(wouldRestore.size());
            }
            getLog().info("  " + s);
        } else {
            var summaryParts = new StringBuilder();
            summaryParts.append("Switched: ").append(switched)
                    .append(" | Skipped: ").append(skipped);
            if (parked > 0) summaryParts.append(" | Parked: ").append(parked);
            if (restored > 0) summaryParts.append(" | Restored: ").append(restored);
            if (stashed > 0) summaryParts.append(" | Stashed: ").append(stashed);
            if (applied > 0) summaryParts.append(" | Applied: ").append(applied);
            getLog().info("  " + summaryParts);
        }
        getLog().info("");

        // ── Report ───────────────────────────────────────────────
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**From:** `" + currentBranch + "` **To:** `" + branch
                + "`" + (draft ? "  _(draft — no changes made)_" : ""));

        // Working-set table: one row per member, the aggregator included
        // (#764/#767). The Effect column states what the switch did (publish)
        // or would do (draft) to each member. The aggregator row makes a stale
        // workspace root visible (#763) rather than hiding it as a
        // subproject-only table did.
        renderWorkingSet(report, root, sorted, effects);

        if (draft) {
            report.paragraph("**" + switched + "** to switch, **"
                    + skipped + "** to skip.");
            if (!wouldPark.isEmpty()) {
                report.section("Park plan");
                report.paragraph("**" + wouldPark.size() + "** subproject(s) "
                        + "not declared on `" + branch + "` would be parked "
                        + "(branch pushed to " + STASH_REMOTE + ", clone "
                        + "removed) and restored on switch-back:");
                for (String n : wouldPark) report.bullet("`" + n + "`");
            }
            if (noStash) {
                report.paragraph("Auto-stash disabled (`-DnoStash=true`) — "
                        + "uncommitted work will fail the switch.");
            } else {
                report.section("Stash plan");
                if (wouldStash.isEmpty()) {
                    report.paragraph("Working trees clean — nothing to stash.");
                } else {
                    report.paragraph("**" + wouldStash.size()
                            + "** subproject(s) with uncommitted work would be "
                            + "stashed to `" + ParkSupport.stashRef(slug, currentBranch)
                            + "` on `" + STASH_REMOTE + "`:");
                    for (String n : wouldStash) report.bullet("`" + n + "`");
                    report.paragraph("Restored automatically when you return to `"
                            + currentBranch + "`. Inspect: `git ls-remote "
                            + STASH_REMOTE + " 'refs/ws-stash/*'`");
                }
                if (wouldRestore.isEmpty()) {
                    report.paragraph("No parked stash on `" + branch
                            + "` — nothing to restore.");
                } else {
                    report.paragraph("**" + wouldRestore.size()
                            + "** subproject(s) have a parked stash on `" + branch
                            + "` that would be restored from `"
                            + ParkSupport.stashRef(slug, branch) + "`:");
                    for (String n : wouldRestore) report.bullet("`" + n + "`");
                }
            }
        } else {
            StringBuilder counts = new StringBuilder();
            counts.append("**").append(switched).append("** switched, **")
                  .append(skipped).append("** skipped");
            if (stashed > 0) counts.append(", **").append(stashed)
                    .append("** stashed");
            if (applied > 0) counts.append(", **").append(applied)
                    .append("** applied");
            counts.append(".");
            report.paragraph(counts.toString());
        }

        return new WorkspaceReportSpec(
                publish ? WsGoal.SWITCH_PUBLISH : WsGoal.SWITCH_DRAFT,
                report.build());
    }

    // ── Working-set table (#764/#767) ────────────────────────────────

    /**
     * Render the switch's effect on the working set as the shared table —
     * one {@link WorkingSetReportTable.Row} per member, the aggregator
     * (workspace root) included. The members are the subprojects the goal
     * processed (in topological order) followed by the workspace root, each
     * with its current version/branch/SHA gathered the same way; the root's
     * version is read too (the #763 fix — a stale aggregator is no longer
     * hidden). The {@code Effect} cell comes from the per-member map built
     * during the switch loop; a member parked (clone removed) shows
     * {@code "—"} for branch/SHA since its tree is gone.
     *
     * @param report  the report builder to append the table to
     * @param root    the workspace root directory
     * @param sorted  the subprojects in topological order (loop order)
     * @param effects per-member effect, keyed by member name
     */
    private void renderWorkingSet(GoalReportBuilder report, File root,
                                  List<String> sorted,
                                  Map<String, String> effects) {
        List<WorkingSetReportTable.Row> rows = new ArrayList<>();
        for (String name : sorted) {
            File dir = new File(root, name);
            boolean cloned = new File(dir, ".git").exists();
            String version = cloned ? readPomVersion(dir) : null;
            String branchName = cloned ? gitBranch(dir) : null;
            String sha = cloned ? gitShortSha(dir) : null;
            rows.add(new WorkingSetReportTable.Row(
                    WorkingSet.Member.subproject(name, dir.toPath()),
                    version, branchName, sha, effects.get(name)));
        }
        // The aggregator (workspace root) — its tree always exists, so its
        // version is gathered the same way (the #763 fix).
        rows.add(new WorkingSetReportTable.Row(
                WorkingSet.Member.aggregator(root.getName(), root.toPath()),
                readPomVersion(root), gitBranch(root), gitShortSha(root),
                effects.get(root.getName())));
        WorkingSetReportTable.render(report, "Working set", rows);
    }

    /**
     * Read a member's POM {@code <version>}, or {@code null} when there is no
     * {@code pom.xml} (a POM-less aggregator root) or it cannot be parsed.
     *
     * @param dir the member directory
     * @return the POM version, or {@code null} when unavailable
     */
    private String readPomVersion(File dir) {
        File pom = new File(dir, "pom.xml");
        if (!pom.isFile()) return null;
        try {
            return network.ike.plugin.ReleaseSupport.readPomVersion(pom);
        } catch (MojoException e) {
            return null;
        }
    }

    // ── #573 park / restore: branch-scoped membership ────────────────

    /**
     * Read the subprojects declared on the target branch's committed
     * {@code workspace.yaml} (via {@code git show <branch>:workspace.yaml}) —
     * the authoritative membership for that branch (#573). A subproject
     * present on the current branch but absent here is parked rather than
     * force-switched. Returns {@code null} when membership can't be
     * determined (root is not a git repo, or the branch has no committed
     * {@code workspace.yaml}); callers then treat every current subproject
     * as a member, preserving pre-#573 behavior.
     *
     * @param root   the workspace root
     * @param branch the target branch
     * @return declared subproject names on the target branch, or {@code null}
     */
    private Set<String> targetBranchMembers(File root, String branch) {
        try {
            Process proc = new ProcessBuilder(
                    "git", "show", branch + ":workspace.yaml")
                    .directory(root)
                    .start();
            byte[] out = proc.getInputStream().readAllBytes();
            if (proc.waitFor() != 0) {
                return null;
            }
            Manifest m = ManifestReader.read(
                    new StringReader(new String(out, StandardCharsets.UTF_8)));
            return new LinkedHashSet<>(m.subprojects().keySet());
        } catch (Exception e) {
            getLog().debug("Could not read " + branch + ":workspace.yaml — "
                    + "treating all subprojects as members: " + e.getMessage());
            return null;
        }
    }

    /**
     * Park a subproject that is not a member of the target branch (#573):
     * push its branch to {@value #STASH_REMOTE} so no work is lost, then
     * remove the local clone. Aborts in place (no deletion) if the push
     * fails — the working tree is never reduced below what is on origin.
     *
     * <p>Delegates to {@link ParkSupport#parkSubproject} (the shared
     * primitive, ike-issues#575).
     *
     * @param dir        the subproject directory
     * @param name       the subproject name
     * @param compBranch the branch the subproject is currently on
     * @throws MojoException if the branch cannot be pushed (park aborts)
     */
    private void parkSubproject(File dir, String name, String compBranch)
            throws MojoException {
        ParkSupport.parkSubproject(dir, getLog(), name, compBranch);
    }

    /**
     * After the workspace root is on the target branch, materialize any
     * declared-but-missing member (a previously parked subproject) by
     * reusing the {@code scaffold-init} clone path, then re-apply its
     * parked stash (#573).
     *
     * @param root the workspace root
     * @param slug the user stash slug, or {@code null} under {@code -DnoStash}
     * @return the number of subprojects restored
     * @throws MojoException if cloning fails
     */
    private int restoreMembers(File root, String slug) throws MojoException {
        WorkspaceGraph graph = loadGraph();
        List<String> missing = new ArrayList<>();
        for (String name : graph.manifest().subprojects().keySet()) {
            if (!new File(new File(root, name), ".git").exists()) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }
        new SubprojectInitializer(graph, root, root.getName(), getLog()).run();
        int restored = 0;
        for (String name : missing) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                continue;   // clone failed — surfaced by the initializer
            }
            if (slug != null) {
                String memberBranch =
                        graph.manifest().subprojects().get(name).branch();
                if (memberBranch != null && !memberBranch.isBlank()) {
                    try {
                        ParkSupport.stashArrive(dir, getLog(), slug, memberBranch);
                    } catch (MojoException e) {
                        getLog().warn("    could not restore stash for " + name
                                + ": " + e.getMessage());
                    }
                }
            }
            getLog().info("  " + Ansi.green("⇱ ") + name + " — restored");
            restored++;
        }
        return restored;
    }

    /**
     * Probe whether a parked stash exists on the target branch for this
     * user, for draft-mode preview only. An unreachable origin is treated
     * as "no stash" — {@link #runAutoStashPreflight} already warns about
     * unreachable remotes, so draft stays read-only and never fails on a
     * network blip.
     *
     * @param dir          the subproject directory
     * @param slug         user slug
     * @param targetBranch the branch we would switch to
     * @return {@code true} if a stash ref is present on the remote,
     *         {@code false} if absent or the remote is unreachable
     */
    private boolean draftHasTargetStash(File dir, String slug,
                                        String targetBranch) {
        if (slug == null) return false;
        try {
            return VcsOperations.remoteRefExists(dir, STASH_REMOTE,
                    ParkSupport.stashRef(slug, targetBranch));
        } catch (MojoException e) {
            return false;
        }
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
                String sourceRef = ParkSupport.stashRef(slug, sourceBranch);
                try {
                    if (VcsOperations.remoteRefExists(dir, STASH_REMOTE, sourceRef)) {
                        collisions.add(name + " (" + sourceRef + ")");
                    }
                } catch (MojoException e) {
                    unreachable.add(name);
                }
            }

            String targetRef = ParkSupport.stashRef(slug, targetBranch);
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
                    + "  git fetch origin " + ParkSupport.stashRef(slug, sourceBranch) + ":"
                    + ParkSupport.stashRef(slug, sourceBranch) + "\n"
                    + "  git stash apply " + ParkSupport.stashRef(slug, sourceBranch) + "\n"
                    + "  # OR, if the old stash is obsolete:\n"
                    + "  git push origin :" + ParkSupport.stashRef(slug, sourceBranch) + "\n"
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

        // Resolve via requireParam — accepts either an index (1..N) or a
        // typed branch name. The prompt label becomes the property hint
        // a non-interactive caller would see.
        String input = requireParam(null, "branch",
                "Select branch [1-" + branches.size() + "] or branch name");

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
