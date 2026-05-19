package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.reconcile.DriftReport;
import network.ike.plugin.ws.reconcile.Reconciler;
import network.ike.plugin.ws.reconcile.ReconcilerOptions;
import network.ike.plugin.ws.reconcile.ReconcilerRegistry;
import network.ike.plugin.ws.reconcile.WorkspaceContext;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        String goal = publish ? "ike:scaffold-publish" : "ike:scaffold-draft";
        String goalLabel = publish ? "ws:scaffold-publish"
                                   : "ws:scaffold-draft";

        getLog().info("");
        getLog().info(goalLabel);
        getLog().info("══════════════════════════════════════════════════════════════");

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
        var verifier = new WorkspaceVerifier(getLog(), graph, root,
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

        // ws:scaffold-publish edits POMs and scaffold files in place
        // without committing — surface the resulting uncommitted state
        // so the operator can see what to review (#431).
        if (publish) {
            reportUncommittedState(root, targets, walkRoot, report);
        }

        if (failed > 0) {
            throw new MojoException(
                    "ws:" + (publish ? "scaffold-publish" : "scaffold-draft")
                    + " saw " + failed + " per-subproject failure(s); "
                    + "see logs above.");
        }

        return new WorkspaceReportSpec(
                publish ? WsGoal.SCAFFOLD_PUBLISH : WsGoal.SCAFFOLD_DRAFT,
                report.build());
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
        List<String> args = new ArrayList<>();
        args.add(mvn);
        // Run the validate phase ahead of the scaffold goal so
        // ike-parent's unpack-scaffold-templates execution (bound to
        // validate) populates target/scaffold. Without it the scaffold
        // goal fails with "scaffoldDir is not a directory" on any
        // checkout that has not been built yet (#449).
        args.add("validate");
        args.add(goal);
        args.add("-B");
        if (publish && applyFoundation) {
            args.add("-Dike.scaffold.apply-foundation=true");
            // The workspace ParentVersionReconciler owns <parent>; the
            // per-subproject foundation-apply must not overwrite its
            // cascade — apply the property pins only (#418).
            args.add("-Dike.scaffold.skip-parent=true");
            if (resolveFoundation) {
                args.add("-Dike.scaffold.resolve-foundation=true");
            }
        }
        ReleaseSupport.exec(subDir, getLog(), args.toArray(new String[0]));
    }

    /**
     * Append an "Uncommitted changes" section listing the files
     * {@code ws:scaffold-publish} left modified and uncommitted, per
     * repo (IKE-Network/ike-issues#431).
     *
     * <p>The goal edits POMs and scaffold files in place and does not
     * commit, so this section is the operator's checklist of what to
     * review and commit, and in which repo.
     *
     * @param root     the workspace root directory
     * @param targets  the subproject names that were walked
     * @param walkRoot whether the workspace root itself was walked
     * @param report   the goal report being accumulated
     */
    private void reportUncommittedState(File root, List<String> targets,
            boolean walkRoot, GoalReportBuilder report) {
        report.section("Uncommitted changes");
        StringBuilder body = new StringBuilder();
        boolean any = false;
        for (String name : targets) {
            any |= appendRepoStatus(name, new File(root, name), body);
        }
        if (walkRoot) {
            any |= appendRepoStatus("(workspace root)", root, body);
        }
        if (any) {
            report.paragraph("`ws:scaffold-publish` edits files in place "
                    + "and does not commit. Review and commit per repo:");
            report.raw(body.toString());
        } else {
            report.paragraph("No files were modified.");
        }
    }

    /**
     * Append one repo's {@code git status --porcelain} to {@code body}
     * as a Markdown bullet listing its changed files.
     *
     * @param label the repo label shown in the report
     * @param dir   the repo directory
     * @param body  the Markdown buffer to append to
     * @return {@code true} if the repo had uncommitted changes
     */
    private boolean appendRepoStatus(String label, File dir,
            StringBuilder body) {
        if (!new File(dir, ".git").isDirectory()) {
            return false;
        }
        String status;
        try {
            status = ReleaseSupport.execCapture(dir,
                    "git", "status", "--porcelain");
        } catch (RuntimeException e) {
            getLog().debug("  " + label + ": git status failed — "
                    + e.getMessage());
            return false;
        }
        if (status == null || status.isBlank()) {
            return false;
        }
        List<String> lines = status.strip().lines().toList();
        body.append("- **").append(label).append("** — ")
                .append(lines.size()).append(" file(s)\n");
        for (String line : lines) {
            body.append("  - `").append(line.strip()).append("`\n");
        }
        return true;
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
        String command = "mvn ws:scaffold-publish -DparentVersion=" + latest;
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
