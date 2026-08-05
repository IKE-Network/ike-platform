package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.WorkspaceGraph;
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

/**
 * Adopt an <em>existing</em> feature branch in selected subprojects.
 *
 * <p>Where {@link FeatureStartDraftMojo} <em>creates</em> {@code feature/<name>}
 * from the current tip, this goal checks out a branch that already exists —
 * typically pushed from another clone or by another developer — in exactly the
 * subprojects named by {@code -Daffected}, and aligns
 * {@code workspace.yaml}'s {@code branch:} fields with the result.
 *
 * <p>It exists because neither adjacent goal covers this case:
 * <ul>
 *   <li>{@code ws:switch} is workspace-wide (no {@code -Daffected}) and skips
 *       any subproject whose target branch is not already present
 *       <em>locally</em> — so a branch that exists only on the remote is
 *       silently skipped everywhere.</li>
 *   <li>{@code ws:feature-start} creates a new branch, failing when the name
 *       is already taken.</li>
 * </ul>
 *
 * <p><strong>Per subproject, in {@code -Daffected} order:</strong>
 * <ol>
 *   <li>Already on {@code feature/<name>} → confirm, refreshing a stale
 *       {@code .ike/vcs-state} that would otherwise make the bridge switch
 *       the member away again (#903/#904)</li>
 *   <li>Local branch exists → check it out</li>
 *   <li>No local branch, but {@code <remote>/feature/<name>} exists → fetch
 *       and create a tracking branch from the remote tip</li>
 *   <li>Branch nowhere → skip with a warning, naming the subproject</li>
 * </ol>
 *
 * <p>Because the branch-coherence triad is {@code workspace.yaml} /
 * checkout / {@code .ike/vcs-state} (#904), all three are written: the
 * checkout moves, {@code VcsOperations.recordSwitch} stamps the bridge
 * state, and the manifest's {@code branch:} fields are updated and
 * committed in isolation. Leaving the manifest behind is precisely what
 * makes a later {@code ws:feature-finish-*} skip the subproject while
 * reporting success.
 *
 * <p>Unlike {@code ws:switch} this goal does <em>not</em> auto-stash: it
 * requires an unmodified tree so adopting someone else's branch can never
 * bury local work in a stash ref. Commit first, or pass
 * {@code -Dallow-uncommitted}.
 *
 * <pre>{@code
 * mvn ws:feature-track-draft   -Dfeature=grpc_plugin -Daffected=komet,tinkar-service
 * mvn ws:feature-track-publish -Dfeature=grpc_plugin -Daffected=komet,tinkar-service
 * }</pre>
 *
 * @see FeatureStartDraftMojo for creating a new feature branch
 * @see WsSwitchDraftMojo for switching the whole workspace
 * @see FeaturePrDraftMojo for opening review PRs on the tracked branch
 */
@Mojo(name = "feature-track-draft", projectRequired = false, aggregator = true)
public class FeatureTrackDraftMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public FeatureTrackDraftMojo() {}

    /**
     * Feature name without the {@code feature/} prefix. Prompted when
     * omitted.
     */
    @Parameter(property = "feature")
    String feature;

    /**
     * Comma-separated subproject names to move onto the branch. Each must
     * match a subproject in {@code workspace.yaml}; an unknown name fails
     * the goal early rather than being silently dropped.
     *
     * <p>Omitted, every subproject that has the branch (locally or on the
     * remote) is adopted — which is rarely what you want when a feature
     * spans only part of the workspace, so prefer naming them.
     */
    @Parameter(property = "affected")
    String affected;

    /** Remote to look for the branch on, and to track. */
    @Parameter(property = "remote", defaultValue = "origin")
    String remote;

    /** Execute the checkout. Default is draft (preview only). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (!isWorkspaceMode()) {
            throw new MojoException(
                    "ws:feature-track requires a workspace (workspace.yaml). "
                    + "For a single repository use "
                    + "'git checkout -b <branch> --track " + remote
                    + "/<branch>'.");
        }

        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        feature = requireParam(feature, "feature", "Feature name");
        String branch = "feature/" + validateFeatureName(feature).value();

        Set<String> known = graph.manifest().subprojects().keySet();
        Set<String> scope = FeatureScope.resolveAffected(affected, known);
        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(scope));

        getLog().info("");
        getLog().info(header("Feature Track"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branch);
        getLog().info("  Scope:   " + (affected == null || affected.isBlank()
                ? "whole workspace (no -Daffected)"
                : String.join(", ", sorted)));
        if (draft) getLog().info("  Mode:    DRAFT");
        getLog().info("");

        // Adopting a branch must never bury local work. ws:switch can
        // auto-stash because the user chose that branch; here the branch
        // content comes from elsewhere, so require a clean tree instead.
        PreflightResult pre = Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, sorted));
        if (draft) {
            pre.warnIfFailed(getLog(), WsGoal.FEATURE_TRACK_PUBLISH);
        } else {
            pre.requirePassed(WsGoal.FEATURE_TRACK_PUBLISH);
        }

        Map<String, String> effects = new LinkedHashMap<>();
        Map<String, String> manifestUpdates = new LinkedHashMap<>();
        List<String> adopted = new ArrayList<>();
        List<String> confirmed = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        int skipped = 0;

        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                effects.put(name, "skipped (not cloned)");
                skipped++;
                continue;
            }

            String current = gitBranch(dir);

            // ── Already there: confirm, and heal a stale bridge state ──
            if (branch.equals(current)) {
                boolean stale = VcsState.readFrom(dir.toPath())
                        .map(s -> !branch.equals(s.branch()))
                        .orElse(false);
                if (stale && !draft) {
                    VcsOperations.refreshStaleBranchState(dir, getLog());
                }
                getLog().info("  " + Ansi.green("✓ ") + name
                        + " — already on " + branch);
                effects.put(name, "already on `" + branch + "`"
                        + (stale
                                ? (draft ? " — would refresh stale vcs-state"
                                         : " — vcs-state refreshed")
                                : ""));
                confirmed.add(name);
                // Still record the manifest field: "already on the branch"
                // with the manifest saying otherwise is exactly the
                // incoherence this goal exists to close.
                manifestUpdates.put(name, branch);
                continue;
            }

            boolean localExists = VcsOperations.localBranchExists(dir, branch);

            // ── Not local: is it on the remote? ──
            if (!localExists) {
                // Fetch in draft mode too. A fetch only updates
                // refs/remotes — it touches no local branch, working tree,
                // or HEAD — and without it a branch pushed since this
                // clone's last fetch reads as absent, so the preview would
                // report "not found" for a branch the publish run then
                // adopts successfully. A draft that disagrees with the run
                // is worse than no draft.
                VcsOperations.fetch(dir, getLog());
                boolean remoteExists =
                        VcsOperations.remoteTrackingExists(dir, remote, branch);
                if (!remoteExists) {
                    getLog().info("  " + Ansi.yellow("⚠ ") + name
                            + " — " + branch + " exists neither locally nor on "
                            + remote + ", skipping");
                    effects.put(name, "skipped (`" + branch + "` not found on "
                            + remote + ")");
                    absent.add(name);
                    skipped++;
                    continue;
                }
            }

            String how = localExists
                    ? "checkout local"
                    : "create tracking branch from " + remote + "/" + branch;

            if (draft) {
                getLog().info("  [draft] " + name + " — would adopt "
                        + current + " → " + branch + " (" + how + ")");
                effects.put(name, "would adopt `" + current + "` → `"
                        + branch + "` (" + how + ")");
                adopted.add(name);
                manifestUpdates.put(name, branch);
                continue;
            }

            getLog().info("  " + Ansi.cyan("→ ") + name + ": " + current
                    + " → " + branch + " (" + how + ")");
            if (localExists) {
                VcsOperations.checkout(dir, getLog(), branch);
            } else {
                VcsOperations.checkoutTracking(dir, getLog(), remote, branch);
            }
            // Without this the bridge sync in the next ws:* goal restores
            // the member's previous state-file branch and undoes the
            // adoption (#903/#904).
            VcsOperations.recordSwitch(dir, getLog());
            adopted.add(name);
            manifestUpdates.put(name, branch);
            effects.put(name, "adopted `" + current + "` → `" + branch + "`");
        }

        // ── Manifest: close the coherence triad ──
        int manifestChanges = applyManifestBranches(graph, manifestUpdates,
                branch, draft);

        getLog().info("");
        getLog().info("  " + (draft
                ? adopted.size() + " to adopt, " + confirmed.size()
                        + " already on branch, " + skipped + " to skip"
                : "Adopted: " + adopted.size() + " | Already on branch: "
                        + confirmed.size() + " | Skipped: " + skipped));
        getLog().info("");

        // ── Report ──
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Feature:** `" + feature + "` **Branch:** `" + branch
                + "` **Remote:** `" + remote + "`"
                + (draft ? "  _(draft — no changes made)_" : ""));

        report.section("Working set");
        for (Map.Entry<String, String> e : effects.entrySet()) {
            report.bullet("`" + e.getKey() + "` — " + e.getValue());
        }

        if (!absent.isEmpty()) {
            report.section("Branch not found");
            report.paragraph("`" + branch + "` exists neither locally nor on `"
                    + remote + "` for these subprojects, so they were left "
                    + "untouched. Push the branch, or drop them from "
                    + "`-Daffected`:");
            for (String n : absent) report.bullet("`" + n + "`");
        }

        report.section("Manifest");
        if (manifestChanges == 0) {
            report.paragraph("`workspace.yaml` already records `" + branch
                    + "` for every adopted subproject — nothing to write.");
        } else if (draft) {
            report.paragraph("Would set `branch: " + branch + "` for **"
                    + manifestChanges + "** subproject(s) in `workspace.yaml` "
                    + "and commit that file in isolation.");
        } else {
            report.paragraph("Set `branch: " + branch + "` for **"
                    + manifestChanges + "** subproject(s) in `workspace.yaml` "
                    + "and committed it.");
        }

        if (!adopted.isEmpty()) {
            report.section("Next");
            report.paragraph("Commit work with `ws:commit-publish`, push the "
                    + "branch with `ws:push -Dfeature=" + feature + "`, then "
                    + "open review PRs with `ws:feature-pr-publish -Dfeature="
                    + feature + "`.");
        }

        return new WorkspaceReportSpec(
                publish ? WsGoal.FEATURE_TRACK_PUBLISH
                        : WsGoal.FEATURE_TRACK_DRAFT,
                report.build());
    }

    /**
     * Write {@code branch:} fields for the adopted subprojects and commit
     * {@code workspace.yaml} in isolation, returning the number of fields
     * that actually changed.
     *
     * <p>Only genuine changes are written: a subproject the manifest already
     * records on the branch is dropped, so a re-run is a no-op rather than an
     * empty commit.
     */
    private int applyManifestBranches(WorkspaceGraph graph,
                                      Map<String, String> updates,
                                      String branch,
                                      boolean draft) throws MojoException {
        Map<String, String> changed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : updates.entrySet()) {
            String declared = graph.manifest().subprojects()
                    .get(e.getKey()).branch();
            if (!e.getValue().equals(declared)) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        if (changed.isEmpty() || draft) {
            return changed.size();
        }

        Path manifestPath = resolveManifest();
        try {
            ManifestWriter.updateBranches(manifestPath, changed);
            getLog().info("  Manifest: set branch: " + branch + " for "
                    + changed.size() + " subproject(s)");
            File wsRoot = manifestPath.getParent().toFile();
            if (new File(wsRoot, ".git").exists()) {
                // Commit only the path this goal authored, never the whole
                // index (#780).
                VcsOperations.commitPaths(wsRoot, getLog(),
                        "workspace: track " + branch + " in "
                        + String.join(", ", changed.keySet())
                        + "\n\nRefs: IKE-Network/ike-issues#904",
                        "workspace.yaml");
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to update workspace.yaml: " + e.getMessage(), e);
        }
        return changed.size();
    }
}
