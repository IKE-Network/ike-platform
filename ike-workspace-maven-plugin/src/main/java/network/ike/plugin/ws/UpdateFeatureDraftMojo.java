package network.ike.plugin.ws;

import network.ike.workspace.WorkingSet;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
 * <p>Both variants begin by refreshing local main from
 * {@code origin/main} via {@link RefreshMainSupport} so the merge runs
 * against current main rather than whatever stale state happens to be
 * on the local machine. The refresh fast-forwards local main when
 * possible and auto-resolves divergence with a merge commit when needed.
 * If the refresh would produce file conflicts (the rare "two machines
 * edited the same file on main without push/pull" case), the goal
 * hard-errors before touching the feature branch. See ike-issues#284.
 *
 * <p>Components are processed in topological order. If a conflict occurs
 * during the feature-side merge, the goal stops and reports the
 * conflicting files with instructions for resolving in IntelliJ.
 * Re-running the goal after resolution continues with the remaining
 * components.
 *
 * <p><strong>Single repo (no {@code workspace.yaml})</strong>: updates the
 * current repository only — a working set of one. The repo's own current
 * branch is taken as the feature branch (unless {@code -Dfeature} is given),
 * and {@code main} is merged in exactly as the workspace path does per
 * subproject (IKE-Network/ike-issues#703).
 *
 * <p>The draft variant fetches and refreshes local main but does not
 * modify the feature branch or working tree. Conflict prediction uses
 * {@code git merge-tree}.
 *
 * <pre>{@code
 * mvn ws:update-feature-draft    # refresh main + preview + predict conflicts
 * mvn ws:update-feature-publish  # refresh main + merge into feature branch
 * }</pre>
 *
 * @see RefreshMainSupport for the local-main refresh contract
 * @see FeatureStartDraftMojo for creating feature branches
 * @see FeatureFinishSquashDraftMojo for merging back to main
 */
@Mojo(name = "update-feature-draft", projectRequired = false, aggregator = true)
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
    protected WorkspaceReportSpec runGoal() throws MojoException {
        // strategy is always "merge" — see field declaration
        boolean draft = !publish;

        // Resolve scope: a workspace updates every subproject on the feature
        // branch; a bare repo is a working set of one (no workspace.yaml,
        // IKE-Network/ike-issues#703). Both drive the same eligibility +
        // refresh-main + merge loop below over (root, components).
        WorkingSet workingSet = resolveWorkingSet();
        File root;
        List<String> sorted;
        if (workingSet.isWorkspace()) {
            WorkspaceGraph graph = loadGraph();
            root = workspaceRoot();
            // Auto-detect feature if not specified
            if (feature == null || feature.isBlank()) {
                feature = FeatureFinishSupport.detectFeature(
                        root, graph.topologicalSort(), this, getLog());
            }
            sorted = graph.topologicalSort(new LinkedHashSet<>(
                    graph.manifest().subprojects().keySet()));
        } else {
            File repo = workingSet.root().toFile();
            if (!new File(repo, ".git").exists()) {
                throw new MojoException("ws:update-feature: " + repo
                        + " is not a git repository.");
            }
            // The repo's own current branch is the feature branch unless
            // -Dfeature pins one explicitly.
            if (feature == null || feature.isBlank()) {
                String current = gitBranch(repo);
                if (!current.startsWith("feature/")) {
                    throw new MojoException("ws:update-feature: "
                            + repo.getName() + " is on '" + current
                            + "', not a feature/* branch. Check out the feature"
                            + " branch, or pass -Dfeature=<name>.");
                }
                feature = current.substring("feature/".length());
            }
            // Drive the shared loop with the repo's parent as root so
            // new File(root, name) resolves back to the repo.
            root = repo.getParentFile();
            sorted = List.of(repo.getName());
        }

        validateFeatureName(feature);
        String branchName = "feature/" + feature;

        getLog().info("");
        getLog().info(header("Update Feature"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:   " + feature);
        getLog().info("  Branch:    " + branchName);
        getLog().info("  From:      " + targetBranch);
        getLog().info("  Strategy:  " + strategy);
        if (draft) getLog().info("  Mode:      DRAFT");
        getLog().info("");

        // Validate clean working trees and collect eligible components.
        // Per-member effect keyed by member name, so the working-set report
        // can name every member's planned/applied effect — the aggregator
        // included (the #763 gap), via WorkingSetReportTable below.
        List<String> eligible = new ArrayList<>();
        List<String> uncommitted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, String> effects = new LinkedHashMap<>();

        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                skipped.add(name);
                effects.put(name, "skipped (no git repo)");
                continue;
            }

            String currentBranch = gitBranch(dir);
            if (!currentBranch.equals(branchName)) {
                skipped.add(name);
                effects.put(name, "skipped (not on `" + branchName + "`)");
                getLog().info("  " + Ansi.yellow("· ") + name
                        + " — not on " + branchName + ", skipping");
                continue;
            }

            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                uncommitted.add(name);
                effects.put(name, "uncommitted changes");
                continue;
            }

            eligible.add(name);
        }

        // Check workspace root (workspace mode only — a bare repo's parent
        // directory is not part of the working set).
        if (workingSet.isWorkspace()
                && new File(root, ".git").exists()
                && !gitStatus(root).isEmpty()) {
            uncommitted.add("workspace root");
        }

        if (!uncommitted.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Cannot update — uncommitted changes in:\n");
            for (String name : uncommitted) {
                sb.append("  ").append(name).append("\n");
            }
            sb.append("Commit or stash, then try again.");
            throw new MojoException(sb.toString());
        }

        if (eligible.isEmpty()) {
            getLog().info("  No components on " + branchName + " — nothing to update.");
            return new WorkspaceReportSpec(
                    publish ? WsGoal.UPDATE_FEATURE_PUBLISH : WsGoal.UPDATE_FEATURE_DRAFT,
                    "No components on `" + branchName + "` — nothing to update.\n");
        }

        // Refresh local main from origin/main across eligible components
        // before any feature-side comparison or merge. In draft, preview
        // read-only — never mutate local main (#570). See ike-issues#284.
        if (publish) {
            RefreshMainSupport.refreshOrThrow(root, eligible, targetBranch, getLog());
        } else {
            RefreshMainSupport.previewRefresh(root, eligible, targetBranch, getLog());
        }

        // Show how far behind each subproject is + record its effect. The
        // working-set report (below) reads these effects per member; the
        // -draft variant phrases them as PLANNED, -publish as APPLIED.
        for (String name : eligible) {
            File dir = new File(root, name);
            try {
                List<String> behind = VcsOperations.commitLog(
                        dir, branchName, targetBranch);
                List<String> ahead = VcsOperations.commitLog(
                        dir, targetBranch, branchName);

                if (behind.isEmpty()) {
                    getLog().info("  " + Ansi.green("✓ ") + name
                            + " — up to date with " + targetBranch);
                    effects.put(name, "up to date with `" + targetBranch + "`");
                } else if (draft) {
                    // Predict conflicts without touching working tree
                    List<String> predicted = VcsOperations.predictConflicts(
                            dir, branchName, targetBranch);

                    if (predicted.isEmpty()) {
                        getLog().info("  " + Ansi.green("✓ ") + name + " — "
                                + behind.size() + " commit(s) behind "
                                + targetBranch + ", " + ahead.size()
                                + " ahead — clean update expected");
                        effects.put(name, "clean update expected ("
                                + behind.size() + " behind, " + ahead.size()
                                + " ahead)");
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
                                + " " + WsGoal.UPDATE_FEATURE_PUBLISH.qualified());
                        effects.put(name, predicted.size()
                                + " conflict(s) expected: "
                                + String.join(", ", predicted));
                    }
                } else {
                    getLog().info("  " + Ansi.cyan("→ ") + name + " — "
                            + behind.size() + " commit(s) behind, "
                            + strategy + "...");

                    VcsOperations.mergeNoFf(dir, getLog(), targetBranch,
                            "update: merge " + targetBranch
                                    + " into " + branchName);

                    getLog().info("    " + Ansi.green("✓ ") + "updated");
                    effects.put(name, "merged `" + targetBranch + "` ("
                            + behind.size() + " behind, " + ahead.size()
                            + " ahead)");
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
                getLog().error("    5. Re-run: mvn "
                        + WsGoal.UPDATE_FEATURE_PUBLISH.qualified());
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
            getLog().info("  Next: mvn "
                    + WsGoal.UPDATE_FEATURE_PUBLISH.qualified());
        } else {
            getLog().info("  Updated: " + eligible.size() + " subproject(s)"
                    + " | Skipped: " + skipped.size());
        }
        getLog().info("");

        // Write report. One WorkingSetReportTable.Row per working-set member —
        // the aggregator (workspace root) included — so the table shows the
        // root's version/branch/SHA that a subproject-only table hid (#763).
        // Each member's Effect comes from the eligibility/merge loops above;
        // a member with no recorded effect (e.g. the aggregator, which this
        // goal does not merge) is reported as skipped.
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Branch:** `" + branchName
                + "` ← `" + targetBranch + "`");

        List<WorkingSetReportTable.Row> rows = new ArrayList<>();
        for (WorkingSet.Member member : resolveWorkingSet().members()) {
            File dir = member.directory().toFile();
            String version = readPomVersion(dir);   // the aggregator too (#763)
            String branch = gitBranch(dir);
            String sha = gitShortSha(dir);
            String effect = effects.get(member.name());
            if (effect == null) {
                // No effect recorded for this member. The aggregator is not on
                // the feature branch (this goal merges only the subprojects),
                // so it is skipped; same for any member outside the eligible
                // loop.
                effect = member.isAggregator()
                        ? "skipped (aggregator)"
                        : "skipped (not on `" + branchName + "`)";
            }
            rows.add(new WorkingSetReportTable.Row(
                    member, version, branch, sha, effect));
        }
        WorkingSetReportTable.render(report, "Working set", rows);

        report.paragraph("**" + eligible.size() + "** subproject(s)"
                + (draft ? " to update" : " updated")
                + ", **" + skipped.size() + "** skipped.");
        return new WorkspaceReportSpec(
                publish ? WsGoal.UPDATE_FEATURE_PUBLISH : WsGoal.UPDATE_FEATURE_DRAFT,
                report.build());
    }

    /**
     * Read a member's POM {@code <version>}, returning the
     * {@link WorkingSetReportTable#NONE} placeholder when the POM is absent or
     * unreadable. Applied uniformly to every working-set member — the
     * aggregator (workspace root) included — which is the #763 fix: a
     * subproject-only table never surfaced the root's version.
     *
     * @param dir the member's working-tree directory
     * @return the POM version, or {@link WorkingSetReportTable#NONE}
     */
    private String readPomVersion(File dir) {
        File pom = new File(dir, "pom.xml");
        if (!pom.isFile()) {
            return WorkingSetReportTable.NONE;
        }
        try {
            return ReleaseSupport.readPomVersion(pom);
        } catch (MojoException e) {
            return WorkingSetReportTable.NONE;
        }
    }
}
