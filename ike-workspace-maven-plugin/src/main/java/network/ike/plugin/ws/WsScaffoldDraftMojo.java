package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.reconcile.DriftReport;
import network.ike.plugin.ws.reconcile.Reconciler;
import network.ike.plugin.ws.reconcile.ReconcilerOptions;
import network.ike.plugin.ws.reconcile.ReconcilerRegistry;
import network.ike.plugin.ws.reconcile.WorkspaceContext;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.verify.WorkspaceVerifier;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Session;
import org.apache.maven.api.Version;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.VersionRangeResolver;
import org.apache.maven.api.services.VersionRangeResolverResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Workspace-walking variant of {@code ike:scaffold-draft} (#350).
 *
 * <p>Iterates the workspace's subprojects (and the workspace root)
 * in declaration order, invoking {@code mvn validate ike:scaffold-draft}
 * in each via a subprocess {@code mvn} call. The {@code validate} phase
 * runs first so {@code ike-parent}'s {@code unpack-scaffold-templates}
 * execution populates {@code target/scaffold} before the scaffold goal
 * reads it (#449). Each per-subproject invocation is independent —
 * output is streamed straight through, drift reports surface inline.
 *
 * <p>Mirrors the {@code ws:release-publish} → per-subproject
 * {@code ike:release-publish} cascade pattern. Read-only: no POM
 * mutation, no lockfile writes; use {@link WsScaffoldPublishMojo}
 * to apply.
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ws:scaffold-draft        # report drift across every subproject
 * mvn ws:scaffold-publish      # apply (foundation drift opt-in)
 * }</pre>
 *
 * @see WsScaffoldPublishMojo
 */
@Mojo(name = "scaffold-draft", projectRequired = false, aggregator = true)
public class WsScaffoldDraftMojo extends AbstractWorkspaceMojo {

    /**
     * When {@code true}, the subprocess invocation is
     * {@code ike:scaffold-publish}; when {@code false} (default for
     * the draft variant), it is {@code ike:scaffold-draft}. Set by
     * {@link WsScaffoldPublishMojo} for the apply path.
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /**
     * Forwarded to per-subproject {@code ike:scaffold-publish}: opt
     * in to apply the foundation drift identified by #345's checker.
     * Only consulted when {@link #publish} is {@code true}.
     */
    @Parameter(property = "ike.scaffold.apply-foundation",
               defaultValue = "false")
    boolean applyFoundation;

    /**
     * Forwarded to per-subproject {@code ike:scaffold-publish}: resolve
     * the latest released foundation versions instead of the snapshot
     * baked into the scaffold zip, so a stale subproject jumps straight
     * to current. The escape hatch for the scaffold bootstrap loop.
     * Only consulted when {@link #publish} and {@link #applyFoundation}
     * are both {@code true}.
     */
    @Parameter(property = "ike.scaffold.resolve-foundation",
               defaultValue = "false")
    boolean resolveFoundation;

    /** Creates this goal instance. */
    public WsScaffoldDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (!isWorkspaceMode()) {
            return executeBareMode();
        }
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        String goal = publish ? "ike:scaffold-publish" : "ike:scaffold-draft";
        WsGoal goalEnum = publish ? WsGoal.SCAFFOLD_PUBLISH
                                  : WsGoal.SCAFFOLD_DRAFT;
        String goalLabel = goalEnum.qualified();

        getLog().info("");
        getLog().info(goalLabel);
        getLog().info("══════════════════════════════════════════════════════════════");

        // #919: in publish mode, require clean working trees up front — before
        // the reconcilers below rewrite POMs — so any dirtiness observed after
        // reconciliation is provably this goal's own and can be committed under
        // a goal-owned message without sweeping user WIP into it. Mirrors the
        // #537 precedent in ws:checkpoint-publish (requireCleanUserState).
        // #954: record each repo's HEAD right behind that guarantee — the
        // committed-work ledger at the end of the run reports everything in
        // baseline..HEAD as this goal's own, fan-out subprocesses included.
        Map<String, String> ledgerBaselines = Map.of();
        if (publish) {
            requireCleanTreesForPublish(graph, root);
            ledgerBaselines = captureLedgerBaselines(graph, root);
        }

        // Accumulate the markdown report alongside the console output
        // (IKE-Network/ike-issues#407) so the goal writes its
        // ws꞉scaffold-{draft,publish}.md like every other goal.
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Workspace:** `" + root + "`");

        // Workspace-wide verification (formerly ws:verify, retired in
        // #393). Runs in both draft and publish modes — it's read-only,
        // so the call shape is identical. Parent alignment was dropped
        // from this extraction because ParentVersionReconciler.detect()
        // (below) already covers it.
        WorkspaceVerifier verifier = new WorkspaceVerifier(getLog(), graph, root,
                resolveManifest(), false /* checkConvergence */,
                isWorkspaceMode());
        verifier.runAllChecks();
        report.paragraph("Workspace verification ran — see the console "
                + "output for the full check list.");

        // Workspace-level reconcilers run next (#393). Each owns one
        // dimension of workspace state (denormalized YAML fields,
        // parent version, alignment, etc.) and is reported (draft) or
        // applied (publish) before the per-subproject ike:scaffold
        // delegation runs.
        report.section(publish
                ? "Workspace reconcilers applied"
                : "Workspace reconciler drift");
        runWorkspaceReconcilers(graph, root, report);

        // #919: the reconcilers just rewrote each subproject's POM (parent
        // version cascade, alignment, …), dirtying every tree. Commit those
        // goal-owned edits now, under a goal-owned message, so the
        // per-subproject ike:scaffold-publish fan-out below sees a clean tree
        // and does not reject the goal on changes it produced itself (the #537
        // contract). Each subproject's ike:scaffold-publish then authors and
        // commits its own scaffold output in isolation, as before.
        if (publish) {
            commitReconcilerEdits(graph, root);
        }

        // #417: foundation currency — discover whether a newer parent
        // has been released and offer a deterministic upgrade command.
        // Draft only; publish is already applying.
        if (!publish) {
            reportLatestParent(root, report);
        }

        // Walk each subproject in topological order, then the
        // workspace root. Topological order isn't strictly needed
        // for scaffold (each project's drift is independent), but
        // keeps output predictable.
        List<String> targets = new ArrayList<>();
        for (String name : graph.topologicalSort()) {
            File subDir = new File(root, name);
            if (!new File(subDir, "pom.xml").exists()) {
                getLog().debug("  " + name + ": not cloned — skipping");
                continue;
            }
            targets.add(name);
        }
        // Workspace root last (mirrors ws:release-publish ordering).
        boolean walkRoot = new File(root, "pom.xml").exists();

        // #700: in publish mode the reconcilers above have just rewritten
        // each subproject's <version> to its branch-qualified form and
        // propagated the qualified BOM into consumers' dependencyManagement
        // imports — but the qualified BOM exists only as a rewritten POM on
        // disk; it was never mvn install-ed. The per-subproject walk below
        // delegates to a *standalone* mvn invocation outside the reactor,
        // which cannot resolve a sibling BOM and fails at validate with
        // "Non-resolvable import POM". Seed the local repo with the
        // qualified BOM/parent artifacts first so each delegation resolves.
        if (publish) {
            installReconciledBoms(root, targets, report);
        }

        report.section("Subprojects walked");
        int processed = 0;
        int failed = 0;
        for (String name : targets) {
            File subDir = new File(root, name);
            getLog().info("");
            getLog().info("── " + name + " ".repeat(Math.max(1, 60 - name.length())) + "──");
            try {
                runScaffoldInSubproject(subDir, goal);
                report.bullet("✓ " + name);
                processed++;
            } catch (MojoException e) {
                getLog().error("  ✗ " + name + ": " + e.getMessage());
                report.bullet("✗ " + name + " — " + e.getMessage());
                failed++;
            }
        }
        if (walkRoot) {
            getLog().info("");
            getLog().info("── (workspace root)" + " ".repeat(43) + "──");
            try {
                runScaffoldInSubproject(root, goal);
                report.bullet("✓ (workspace root)");
                processed++;
            } catch (MojoException e) {
                getLog().error("  ✗ workspace root: " + e.getMessage());
                report.bullet("✗ (workspace root) — " + e.getMessage());
                failed++;
            }
        }

        getLog().info("");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Walked " + processed + " project(s)"
                + (failed > 0 ? "; " + failed + " failed" : ""));

        report.paragraph(processed + " project(s) walked"
                + (failed > 0 ? "; " + failed + " failed" : "") + ".");
        report.paragraph("Per-subproject scaffold detail is in each "
                + "subproject's own `ike꞉scaffold-"
                + (publish ? "publish" : "draft") + ".md` report.");

        // #954: end the publish with the committed-work ledger — every
        // commit this run authored (directly, or via the fan-out's
        // subprocesses), from git truth, plus the push-next hint. Runs on
        // the failure path too, so a partial run still shows what landed.
        if (publish) {
            reportGoalChanges(root, ledgerBaselines, report);
        }

        if (failed > 0) {
            // #935: this run failed, but it still gathered per-subproject
            // results and any newly surfaced sections (e.g. a #417 foundation
            // upgrade). Persist the report — prefixed with a failure banner so
            // a partial/failed report cannot be mistaken for a clean current
            // one — before failing. WorkspaceReportException routes through the
            // base class's write-then-propagate path so the on-disk
            // ws꞉scaffold-*.md always reflects this run.
            int walked = processed + failed;
            String banner = "> ⚠️ **Run failed** — " + failed + " of " + walked
                    + " target(s) failed. This report reflects that failed "
                    + "run; see the failures under **Subprojects walked** "
                    + "below.\n\n";
            WorkspaceReportSpec failedSpec = new WorkspaceReportSpec(
                    goalEnum, banner + report.build());
            throw new WorkspaceReportException(
                    goalEnum.qualified() + " saw " + failed
                            + " per-subproject failure(s); see logs above.",
                    failedSpec);
        }

        return new WorkspaceReportSpec(goalEnum, report.build());
    }

    // ── Bare mode: single-repo scaffold (no workspace.yaml) ──────────

    /**
     * Bare mode: scaffold the current single repo by delegating to
     * {@code ike:scaffold-draft} (preview) or {@code ike:scaffold-publish}
     * (apply). The single-repo case of the same operation — a working set
     * of one — with no workspace-level reconcilers or per-subproject walk
     * (ike-issues#601). In one repo, {@code ws:scaffold} is exactly
     * {@code ike:scaffold}: the console verb forwarding to the per-repo
     * build-standards engine.
     *
     * @return the goal's report
     * @throws MojoException if there is no Maven project here, or the
     *                       delegated {@code ike:scaffold-*} fails
     */
    private WorkspaceReportSpec executeBareMode() throws MojoException {
        // Single-repo scaffold is a working set of one (ike-issues#611) —
        // resolve it through the shared resolver, not user.dir directly.
        File repo = resolveWorkingSet().members().getFirst().directory().toFile();
        if (!new File(repo, "pom.xml").exists()) {
            throw new MojoException(
                    "ws:scaffold: " + repo + " has no pom.xml and no "
                    + "workspace.yaml was found — run it in a Maven project, "
                    + "or a workspace.");
        }
        WsGoal goal = publish ? WsGoal.SCAFFOLD_PUBLISH : WsGoal.SCAFFOLD_DRAFT;
        String delegate = publish ? "ike:scaffold-publish" : "ike:scaffold-draft";

        getLog().info("");
        getLog().info(goal.qualified());
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Repo: " + repo.getName());
        getLog().info("  Mode: single repo (no workspace.yaml) → " + delegate);
        getLog().info("");

        ReleaseSupport.exec(repo, getLog(),
                bareScaffoldArgs(repo).toArray(new String[0]));

        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Repo:** `" + repo + "`");
        report.paragraph("Single-repo scaffold "
                + (publish ? "applied" : "previewed") + " via `" + delegate
                + "` — a working set of one, no workspace reconcilers.");
        return new WorkspaceReportSpec(goal, report.build());
    }

    /**
     * Build the {@code mvn} argv for the bare-mode single-repo scaffold,
     * reusing {@link #buildScaffoldArgs} so the delegated flag set is
     * identical to a per-subproject invocation. Package-private so the
     * delegation can be asserted without spawning a Maven subprocess.
     *
     * @param repo the single repo to scaffold
     * @return the argv, beginning with the resolved {@code mvn} command
     */
    List<String> bareScaffoldArgs(File repo) {
        String mvn = WsReleaseDraftMojo.resolveMvnCommand(repo);
        String goal = publish ? "ike:scaffold-publish" : "ike:scaffold-draft";
        return buildScaffoldArgs(mvn, goal, publish, applyFoundation,
                resolveFoundation);
    }

    /**
     * Run {@code mvn <goal>} in the given subproject directory. When
     * {@code goal} is {@code ike:scaffold-publish} and the
     * {@link #applyFoundation} flag is set, forwards
     * {@code -Dike.scaffold.apply-foundation=true} — plus
     * {@code -Dike.scaffold.skip-parent=true} always (the workspace's
     * {@code ParentVersionReconciler} owns the {@code <parent>}
     * cascade, #418), and {@code -Dike.scaffold.resolve-foundation=true}
     * when {@link #resolveFoundation} is set.
     *
     * @param subDir the subproject directory
     * @param goal   the ike goal to invoke (draft or publish)
     */
    private void runScaffoldInSubproject(File subDir, String goal)
            throws MojoException {
        String mvn = WsReleaseDraftMojo.resolveMvnCommand(subDir);
        List<String> args = buildScaffoldArgs(mvn, goal,
                publish, applyFoundation, resolveFoundation);
        ReleaseSupport.exec(subDir, getLog(), args.toArray(new String[0]));
    }

    /**
     * Build the {@code mvn} argv for one per-subproject scaffold
     * invocation. Extracted as a pure helper so the #440 and #449
     * regressions can assert the exact flag set without spawning a
     * Maven subprocess.
     *
     * <ul>
     *   <li>{@code validate} runs before {@code goal} so
     *       ike-parent's {@code unpack-scaffold-templates} (bound to
     *       {@code validate}) populates {@code target/scaffold}
     *       (#449).</li>
     *   <li>{@code -Dike.scaffold.skip-parent=true} is always set so
     *       the workspace's {@code ParentVersionReconciler} stays
     *       authoritative on {@code <parent>} (#418), and the inner
     *       {@code ike:scaffold-publish} dry-run never compares the
     *       live POM against the stale {@code foundation:} pin baked
     *       in the scaffold zip (#440).</li>
     *   <li>{@code -Dike.scaffold.apply-foundation=true} is forwarded
     *       only when both {@code publish} and {@code applyFoundation}
     *       are set; {@code -Dike.scaffold.resolve-foundation=true}
     *       additionally requires {@code resolveFoundation}.</li>
     * </ul>
     *
     * @param mvn                wrapper or {@code mvn} command resolved
     *                           for this subproject
     * @param goal               the ike-prefixed scaffold goal
     *                           ({@code ike:scaffold-draft} or
     *                           {@code ike:scaffold-publish})
     * @param publish            whether the workspace mojo is in
     *                           publish mode
     * @param applyFoundation    whether the foundation cascade should
     *                           be applied (not just previewed)
     * @param resolveFoundation  whether to forward the foundation-
     *                           resolution flag
     * @return mutable {@link List} of argv strings, beginning with
     *         {@code mvn}
     */
    static List<String> buildScaffoldArgs(String mvn, String goal,
                                           boolean publish,
                                           boolean applyFoundation,
                                           boolean resolveFoundation) {
        List<String> args = new ArrayList<>();
        args.add(mvn);
        args.add("validate");
        args.add(goal);
        args.add("-B");
        args.add("-Dike.scaffold.skip-parent=true");
        if (publish && applyFoundation) {
            args.add("-Dike.scaffold.apply-foundation=true");
            if (resolveFoundation) {
                args.add("-Dike.scaffold.resolve-foundation=true");
            }
        }
        return args;
    }

    /** Matches a project-level {@code <packaging>pom</packaging>}. */
    private static final Pattern POM_PACKAGING =
            Pattern.compile("<packaging>\\s*pom\\s*</packaging>");

    /**
     * Install the workspace's BOM/parent-only subprojects into the local
     * Maven repository so the per-subproject scaffold delegations that
     * follow can resolve them (IKE-Network/ike-issues#700).
     *
     * <p>Only {@code <packaging>pom</packaging>} subprojects are installed:
     * BOMs, parent POMs, and aggregators are the artifacts that downstream
     * subprojects import (via {@code <scope>import</scope>}) or inherit
     * (via {@code <parent>}), and after feature-version qualification the
     * only copy of the qualified artifact is the rewritten POM on disk. A
     * {@code mvn install -N} is cheap for a POM project — no compilation,
     * just the POM copied into {@code ~/.m2}.
     *
     * <p>{@code targets} is already in topological order, so a BOM whose
     * own {@code <parent>} is itself a workspace member is installed after
     * that parent. Each install uses the subproject's own Maven wrapper —
     * by this point {@link network.ike.plugin.ws.reconcile.MavenWrapperReconciler}
     * has already pinned every wrapper to the workspace Maven version (#701).
     *
     * @param root    the workspace root directory
     * @param targets the cloned subproject names, in topological order
     * @param report  the goal report being accumulated
     * @throws MojoException if a BOM install fails
     */
    private void installReconciledBoms(File root, List<String> targets,
            GoalReportBuilder report) throws MojoException {
        List<String> installed = new ArrayList<>();
        for (String name : targets) {
            File subDir = new File(root, name);
            if (!isPomPackaging(new File(subDir, "pom.xml"))) {
                continue;
            }
            getLog().info("");
            getLog().info("  Installing reconciled BOM/parent: " + name);
            String mvn = WsReleaseDraftMojo.resolveMvnCommand(subDir);
            ReleaseSupport.exec(subDir, getLog(),
                    mvn, "install", "-N", "-B");
            installed.add(name);
        }
        if (!installed.isEmpty()) {
            report.section("Reconciled BOMs installed")
                    .paragraph("Seeded the local repository with "
                            + installed.size() + " branch-qualified "
                            + "BOM/parent artifact(s) so each standalone "
                            + "subproject scaffold can resolve them "
                            + "(IKE-Network/ike-issues#700): `"
                            + String.join("`, `", installed) + "`.");
        }
    }

    /**
     * Report whether a POM declares {@code <packaging>pom</packaging>}.
     * Package-private and pure so the BOM-detection predicate can be
     * unit-tested without spawning Maven.
     *
     * @param pom the POM file to inspect
     * @return {@code true} when the file exists and declares pom packaging;
     *         {@code false} when absent, jar-packaged, or unreadable
     */
    static boolean isPomPackaging(File pom) {
        if (!pom.exists()) {
            return false;
        }
        try {
            String content =
                    Files.readString(pom.toPath(), StandardCharsets.UTF_8);
            return POM_PACKAGING.matcher(content).find();
        } catch (IOException e) {
            return false;
        }
    }

    /** Report label for the workspace root repository. */
    static final String WORKSPACE_ROOT_LABEL = "(workspace root)";

    /**
     * Record each repository's {@code HEAD} immediately after the publish
     * preflight, keyed by report label — the committed-work ledger
     * baselines of IKE-Network/ike-issues#954. Because
     * {@link #requireCleanTreesForPublish} has just proven every tree free
     * of uncommitted changes, every commit later found in
     * {@code baseline..HEAD} is goal-authored — including the ones made
     * inside the per-subproject {@code ike:scaffold-publish} subprocesses.
     *
     * <p>Subprojects appear in topological order with the workspace root
     * last, mirroring the walk. Repositories without a {@code .git} are
     * absent; a {@code null} value marks a repository with no commits yet.
     *
     * @param graph the workspace graph
     * @param root  the workspace root directory
     * @return insertion-ordered map of repo label → baseline {@code HEAD}
     *         short SHA
     */
    Map<String, String> captureLedgerBaselines(WorkspaceGraph graph,
            File root) {
        Map<String, String> baselines = new LinkedHashMap<>();
        for (String name : graph.topologicalSort()) {
            File dir = new File(root, name);
            if (new File(dir, ".git").exists()) {
                baselines.put(name, GoalCommitLedger.baselineSha(dir));
            }
        }
        if (new File(root, ".git").exists()) {
            baselines.put(WORKSPACE_ROOT_LABEL,
                    GoalCommitLedger.baselineSha(root));
        }
        return baselines;
    }

    /**
     * Append the committed-work ledger: what this run committed, per
     * repository, derived from git truth — and surface any uncommitted
     * residue as the anomaly it is (IKE-Network/ike-issues#954).
     *
     * <p>Replaces the #431-era "Uncommitted changes" checklist, which
     * #919's goal-owned commits made permanently misleading: with every
     * goal-authored change committed, a working-tree status check printed
     * "No files were modified." precisely when the goal had done the most
     * work. Each ledger entry lists the goal-authored commits in
     * {@code baseline..HEAD} with subject, short SHA, and changed files —
     * covering the reconciler commit and the per-subproject scaffold
     * commits alike — and the section states the transport status
     * explicitly: committed, not pushed, with the {@code ws:push} command
     * to run next. Residue after a publish indicates a failed goal commit
     * or an interrupted run, and is rendered as a warning.
     *
     * @param root      the workspace root directory
     * @param baselines repo label → baseline {@code HEAD} from
     *                  {@link #captureLedgerBaselines}
     * @param report    the goal report being accumulated
     */
    void reportGoalChanges(File root, Map<String, String> baselines,
            GoalReportBuilder report) {
        List<GoalCommitLedger.RepoChanges> repos = new ArrayList<>();
        for (Map.Entry<String, String> entry : baselines.entrySet()) {
            File dir = WORKSPACE_ROOT_LABEL.equals(entry.getKey())
                    ? root : new File(root, entry.getKey());
            repos.add(GoalCommitLedger.collect(entry.getKey(), dir,
                    entry.getValue()));
        }
        boolean anyCommits = repos.stream()
                .anyMatch(GoalCommitLedger.RepoChanges::hasCommits);
        boolean anyResidue = repos.stream()
                .anyMatch(GoalCommitLedger.RepoChanges::hasResidue);
        String pushCommand = "mvn " + WsGoal.PUSH.qualified();

        report.section("Committed by this goal");
        getLog().info("");
        if (anyCommits) {
            report.paragraph("This work is committed, not pushed. To push:");
            report.codeBlock("bash", pushCommand);
            report.raw(GoalCommitLedger.commitsToMarkdown(repos));
            getLog().info("  Committed by this goal (not pushed):");
            for (GoalCommitLedger.RepoChanges repo : repos) {
                for (GoalCommitLedger.Commit commit : repo.commits()) {
                    getLog().info("    " + repo.label() + ": "
                            + commit.sha() + " " + commit.subject()
                            + " (" + commit.files().size() + " file(s))");
                }
            }
            getLog().info("  To push: " + pushCommand);
        } else {
            report.paragraph("No changes were needed — every repository "
                    + "already matched; nothing was committed.");
            getLog().info("  No changes were needed; nothing was committed.");
        }
        if (anyResidue) {
            report.section("⚠ Left uncommitted");
            report.paragraph("A publish run ends fully committed "
                    + "(IKE-Network/ike-issues#919) — leftover files here "
                    + "mean a goal commit failed or the run was "
                    + "interrupted. Review and commit per repo:");
            report.raw(GoalCommitLedger.residueToMarkdown(repos));
            for (GoalCommitLedger.RepoChanges repo : repos) {
                if (repo.hasResidue()) {
                    getLog().warn("  ⚠ " + repo.label() + ": "
                            + repo.residue().size()
                            + " file(s) left uncommitted");
                }
            }
        }
    }

    /**
     * Commit message for the goal-owned reconciliation edits committed before
     * the per-subproject fan-out (IKE-Network/ike-issues#919).
     */
    static final String RECONCILE_COMMIT_MESSAGE =
            "ws:scaffold-publish: commit reconciliation edits\n\n"
            + "Parent-version cascade and other workspace reconciler output,\n"
            + "committed by the goal so the per-subproject ike:scaffold-publish\n"
            + "fan-out runs against a clean tree (#537 self-trip contract).\n\n"
            + "Refs: IKE-Network/ike-issues#919";

    /**
     * Require every working tree clean before publish-mode reconciliation
     * (IKE-Network/ike-issues#919). Runs {@link
     * PreflightCondition#WORKING_TREE_CLEAN} across the workspace so that any
     * dirtiness observed after the reconcilers run originates from the goal's
     * own writes — never from user WIP that {@link #commitReconcilerEdits}
     * would otherwise sweep into a goal-owned commit. Mirrors {@code
     * ws:checkpoint-publish}'s pre-alignment preflight (#537).
     *
     * @param graph the workspace graph
     * @param root  the workspace root directory
     * @throws MojoException if any working tree has uncommitted changes
     */
    void requireCleanTreesForPublish(WorkspaceGraph graph, File root) {
        Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, graph.topologicalSort()))
                .requirePassed(WsGoal.SCAFFOLD_PUBLISH);
    }

    /**
     * Commit the goal-owned edits the workspace reconcilers just produced — the
     * parent-version cascade and any alignment/field writes — under {@link
     * #RECONCILE_COMMIT_MESSAGE}, in the workspace root and each subproject
     * (IKE-Network/ike-issues#919). Without this, the reconcilers modify every
     * POM and the per-subproject {@code ike:scaffold-publish} fan-out rejects
     * the very changes this run produced (a #537-class self-trip). A no-op per
     * repo the reconcilers left unchanged; per-repo failures are warnings so
     * one bad subproject does not abort the walk (the fan-out's own preflight
     * still fails loudly on anything left uncommitted).
     *
     * <p>The preflight in {@link #requireCleanTreesForPublish} guaranteed each
     * tree carried no uncommitted changes before reconciliation, so staging
     * picks up only the reconcilers' output. The end-of-run ledger
     * ({@link #reportGoalChanges}, #954) reports these commits — repo,
     * subject, SHA, files — so this method writes no report section of its
     * own.
     *
     * @param graph the workspace graph
     * @param root  the workspace root directory
     */
    void commitReconcilerEdits(WorkspaceGraph graph, File root) {
        if (new File(root, ".git").exists()) {
            commitIfDirty(root, WORKSPACE_ROOT_LABEL);
        }
        for (String name : graph.topologicalSort()) {
            File dir = new File(root, name);
            if (new File(dir, ".git").exists()) {
                commitIfDirty(dir, name);
            }
        }
    }

    /**
     * Commit every change in {@code dir} under {@link #RECONCILE_COMMIT_MESSAGE}
     * when the tree is dirty; a no-op when clean.
     *
     * @param dir   the repo directory
     * @param label the repo label for logs
     * @return {@code true} if a commit was made
     */
    private boolean commitIfDirty(File dir, String label) {
        if (VcsOperations.isClean(dir)) {
            return false;
        }
        try {
            VcsOperations.addAll(dir, getLog());
            VcsOperations.commit(dir, getLog(), RECONCILE_COMMIT_MESSAGE);
            getLog().info("  Committed reconciliation edits in " + label);
            return true;
        } catch (MojoException e) {
            getLog().warn("Could not commit reconciliation edits in " + label
                    + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Run the workspace-level {@link Reconciler}s registered in
     * {@link ReconcilerRegistry}. In draft mode each reconciler's
     * {@link Reconciler#detect} result is printed; in publish mode
     * {@link Reconciler#apply} is invoked (honoring opt-out flags).
     *
     * <p>Workspace-level reconcilers act on {@code workspace.yaml}
     * and other cross-subproject state, complementing the
     * per-subproject {@code ike:scaffold-*} pass that follows.
     */
    private void runWorkspaceReconcilers(WorkspaceGraph graph, File root,
            GoalReportBuilder report) throws MojoException {
        WorkspaceContext ctx = new WorkspaceContext(
                root, resolveManifest(), graph,
                readReconcilerOptions(), getLog());
        for (Reconciler reconciler : ReconcilerRegistry.all()) {
            if (publish) {
                if (ctx.options().isOptedOut(reconciler.optOutFlag())) {
                    // apply() self-checks the opt-out and no-ops; the
                    // report says so rather than claiming a change.
                    reconciler.apply(ctx);
                    report.raw("- ⊘ **" + reconciler.dimension()
                            + "** — skipped (opted out via -D"
                            + reconciler.optOutFlag() + "=false)\n");
                } else {
                    // detect() is read-only and the workspace is
                    // unchanged until apply() runs, so the captured
                    // report describes exactly what apply() does —
                    // including the per-subproject before → after
                    // detail (IKE-Network/ike-issues#431).
                    DriftReport applied = reconciler.detect(ctx);
                    reconciler.apply(ctx);
                    report.raw(applied.toAppliedMarkdown());
                }
            } else {
                DriftReport drift = reconciler.detect(ctx);
                printDriftReport(drift);
                report.raw(drift.toMarkdown());
            }
        }
    }

    /**
     * Collect all Maven system properties into a {@link ReconcilerOptions}
     * bag so reconcilers can query their opt-out and pin flags
     * (e.g., {@code -DupdateFields=false}, {@code -DparentVersion=55}).
     */
    private static ReconcilerOptions readReconcilerOptions() {
        Map<String, String> flags = new HashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            flags.put(name, System.getProperty(name));
        }
        return new ReconcilerOptions(flags);
    }

    /**
     * Discover whether a newer parent than the workspace root POM's
     * {@code <parent>} version has been released, and — when one has —
     * print and report a deterministic copy-paste upgrade command
     * (IKE-Network/ike-issues#417).
     *
     * <p>The command pins {@code -DparentVersion=X}, the existing pin
     * flag of {@link network.ike.plugin.ws.reconcile.ParentVersionReconciler}:
     * the next {@code ws:scaffold-publish} updates the workspace root
     * POM to {@code X} and cascades it to every subproject. Discovery
     * is best-effort — an unreachable remote is logged at debug and
     * the draft proceeds.
     *
     * @param root   the workspace root directory
     * @param report the goal report being accumulated
     */
    private void reportLatestParent(File root, GoalReportBuilder report) {
        PomParentSupport.ParentInfo rootParent;
        try {
            rootParent = PomParentSupport.readParent(
                    root.toPath().resolve("pom.xml"));
        } catch (IOException e) {
            getLog().debug("Foundation currency: could not read the "
                    + "workspace root POM parent: " + e.getMessage());
            return;
        }
        if (rootParent == null) {
            return;
        }
        String latest = latestReleasedVersion(rootParent.groupId(),
                rootParent.artifactId(), rootParent.version());
        if (latest == null) {
            return;
        }
        String command = "mvn " + WsGoal.SCAFFOLD_PUBLISH.qualified()
                + " -DparentVersion=" + latest;
        getLog().info("");
        getLog().info("Foundation currency:");
        getLog().info("  Latest released " + rootParent.artifactId()
                + ": " + latest + " (workspace root pins "
                + rootParent.version() + ").");
        getLog().info("  To upgrade the whole workspace, run:");
        getLog().info("    " + command);
        report.section("Foundation upgrade available")
                .paragraph("A newer `" + rootParent.artifactId() + "` (`"
                        + latest + "`) is released; the workspace root "
                        + "pins `" + rootParent.version() + "`. To "
                        + "upgrade the whole workspace, run:")
                .codeBlock("", command);
    }

    /**
     * Resolve the highest released (non-SNAPSHOT) version of a Maven
     * coordinate strictly newer than {@code current}, via the Maven 4
     * {@link VersionRangeResolver}.
     *
     * @param groupId    the coordinate groupId
     * @param artifactId the coordinate artifactId
     * @param current    the currently-pinned version (exclusive lower
     *                    bound of the range)
     * @return the highest released version newer than {@code current},
     *         or {@code null} when none exists or resolution fails
     */
    private String latestReleasedVersion(String groupId, String artifactId,
                                         String current) {
        try {
            Session s = getSession();
            VersionRangeResolver resolver =
                    s.getService(VersionRangeResolver.class);
            ArtifactCoordinates coords = s.createArtifactCoordinates(
                    groupId + ":" + artifactId + ":(" + current + ",)");
            VersionRangeResolverResult result = resolver.resolve(s, coords);
            String best = null;
            for (Version v : result.getVersions()) {
                String str = v.toString();
                if (!str.endsWith("-SNAPSHOT")) {
                    best = str;  // resolver returns ascending — last wins
                }
            }
            return best;
        } catch (Exception e) {
            getLog().debug("Foundation currency: could not resolve the "
                    + "latest " + groupId + ":" + artifactId + " — "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * Render a {@link DriftReport} for {@code scaffold-draft} output
     * with the copy-paste opt-out command inline.
     */
    private void printDriftReport(DriftReport report) {
        getLog().info("");
        if (!report.hasDrift()) {
            getLog().info("  ✓ " + report.dimension());
            return;
        }
        getLog().info("  ⚠ " + report.dimension());
        if (!report.summary().isEmpty()) {
            getLog().info("     " + report.summary());
        }
        for (String line : report.detailLines()) {
            getLog().info("       " + line);
        }
        if (!report.defaultAction().isEmpty()) {
            getLog().info("     Default: " + report.defaultAction());
        }
        if (!report.optOutCommand().isEmpty()) {
            getLog().info("     Opt out: " + report.optOutCommand());
        }
    }
}
