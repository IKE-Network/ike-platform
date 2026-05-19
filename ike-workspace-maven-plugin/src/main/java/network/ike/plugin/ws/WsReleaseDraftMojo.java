package network.ike.plugin.ws;

import network.ike.plugin.PomRewriter;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.ReleasePlan.ArtifactReleasePlan;
import network.ike.plugin.ws.ReleasePlan.GA;
import network.ike.plugin.ws.ReleasePlan.PropertyReleasePlan;
import network.ike.plugin.ws.ReleasePlan.ReferenceKind;
import network.ike.plugin.ws.ReleasePlan.ReferenceSite;
import network.ike.plugin.ws.ReleasePlanCompute.ArtifactReleaseIntent;
import network.ike.plugin.ws.ReleasePlanCompute.SubprojectRoot;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;
import network.ike.plugin.support.GoalReportBuilder;

import network.ike.workspace.ManifestWriter;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;


import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Workspace-level release — release all release-pending checked-out
 * components (those with unreleased commits since their last tag, or
 * cascaded as transitive downstream of one) in topological order.
 *
 * <p>Scans checked-out components for commits since their last release
 * tag. The release set is the union of:
 * <ul>
 *   <li><b>source-changed</b> — subprojects with unreleased commits
 *       since their last tag (or never released);</li>
 *   <li><b>transitive downstream</b> — every checked-out subproject
 *       that depends, directly or transitively, on a source-changed
 *       subproject. Catches workspaces where a mid-graph change forces
 *       downstream re-publish even though those downstream subprojects
 *       have no source changes of their own.</li>
 * </ul>
 * The release set is topologically sorted and released in dependency
 * order. Before each subproject's release, a single
 * <em>catch-up alignment commit</em> bumps every workspace-internal
 * upstream version reference (parent and {@code <X.version>} property)
 * to the upstream's current target version — this-cycle's new version
 * if the upstream is releasing this cycle, otherwise the upstream's
 * current published version on disk. All upstream bumps for a single
 * subproject batch into one commit (never two).</p>
 *
 * <p>Catch-up never expands the release set: a subproject with stale
 * upstream properties but no source changes <em>and</em> no upstream
 * releasing in this cycle is not pulled in.</p>
 *
 * <p>If catch-up alignment fails for any subproject (POM rewrite or
 * commit error), the release halts at that subproject with a
 * {@link MojoException} naming the failing subproject and property —
 * never silently continues.</p>
 *
 * <p><strong>What it does, per subproject:</strong></p>
 * <ol>
 *   <li>Detect latest release tag ({@code v*})</li>
 *   <li>Check for commits since that tag</li>
 *   <li>If source-changed or cascade-induced: catch-up upstream version
 *       references in this subproject's POM (single commit), then run
 *       {@code mvn ike:release-publish} in that subproject's directory</li>
 * </ol>
 *
 * <p><strong>Workspace-level preflight</strong> (applied before any
 * subproject is released):</p>
 * <ul>
 *   <li>{@link PreflightCondition#WORKING_TREE_CLEAN} — every
 *       checked-out subproject (and the workspace root) must have no
 *       uncommitted changes.</li>
 *   <li>{@link PreflightCondition#NO_SNAPSHOT_PROPERTIES} — no root
 *       POM may carry a {@code <properties>} value ending in
 *       {@code -SNAPSHOT}. Catches the {@code ike-parent-105.pom}
 *       leakage class of bug at its source (see issues #175, #177).</li>
 * </ul>
 *
 * <p>Per-subproject preflight (javadoc warnings, git push auth, SSH
 * proxy, gh CLI auth, Maven wrapper, post-mutation SNAPSHOT
 * <code>&lt;version&gt;</code> scan) runs inside each
 * {@code ike:release-publish} invocation — see {@code ReleaseDraftMojo}
 * in the {@code ike-maven-plugin} module. This ensures the same
 * gates apply whether a release is invoked workspace-level or
 * directly inside a single subproject.
 *
 * <p>The cascade is self-limiting: only checked-out components with
 * changes since their last release are candidates. Components not
 * present in the aggregator are not considered.</p>
 *
 * <pre>{@code
 * mvn ws:release-draft                       # preview what would be released
 * mvn ws:release-publish                     # release all release-pending components
 * }</pre>
 */
@Mojo(name = "release-draft", projectRequired = false, aggregator = true)
public class WsReleaseDraftMojo extends AbstractWorkspaceMojo {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);

    /** Preview what would be released without executing. */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Skip the pre-release checkpoint. */
    @Parameter(property = "skipCheckpoint", defaultValue = "false")
    boolean skipCheckpoint;

    /** Push releases to remote. Passed through to ike:release. */
    @Parameter(property = "push", defaultValue = "true")
    boolean push;

    /**
     * Release even when a subproject's release preflight reports
     * warnings (e.g. commits without an issue trailer). Forwarded to
     * each subproject's {@code ike:release-publish} invocation as
     * {@code -Dike.release.ignoreWarnings}. Errors remain fatal.
     */
    @Parameter(property = "ike.release.ignoreWarnings", defaultValue = "false")
    boolean ignoreWarnings;

    /**
     * GitHub repository for release creation (e.g., "IKE-Network/komet").
     * If set, creates a GitHub Release for each released subproject and
     * attaches any platform installers found in the subproject's
     * {@code target/installers/} directory.
     */
    @Parameter(property = "githubRepo")
    String githubRepo;

    /**
     * Glob pattern for installer artifacts to attach to the GitHub Release.
     * Matched relative to each subproject's {@code target/} directory.
     */
    @Parameter(property = "installerGlob", defaultValue = "installers/*.{pkg,dmg,msi,deb,rpm}")
    String installerGlob;

    /** Creates this goal instance. */
    public WsReleaseDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // ── 1. Determine candidate components ─────────────────────────
        List<String> candidates = graph.topologicalSort();

        boolean draft = !publish;

        // Refresh local main from origin/main before any release work —
        // the cascade picks up parent versions and property bumps off
        // local main, and Syncthing-paired workflows can leave local
        // main stale (ike-issues#284). Same invariant the feature
        // flows establish; releases need it for the same reason.
        if (publish) {
            RefreshMainSupport.refreshOrThrow(root, candidates, "main", getLog());
        }

        // ── 1.5. Pre-release upstream alignment ──────────────────────────
        // An ike-parent bump (or any foundation property bump) is a
        // release-worthy change even when no source has been edited.
        // Without this pass, ws:release-publish only saw per-subproject
        // source changes and skipped workspaces whose only diff vs.
        // last release was "absorb the new foundations".
        //
        // For each subproject (and the workspace root), scan its pom
        // for <parent> blocks and <X.version> properties referencing
        // an IKE foundation. If a reference is older than the
        // foundation's latest released version (resolved from the
        // sibling repo's tip v* tag in the canonical ~/ike-dev/
        // layout), bump via OpenRewrite and commit. The bump commit
        // becomes a meaningful commit, so the existing
        // meaningfulCommitsSinceTag detector includes the subproject
        // in the release set automatically.
        //
        // Also clean any on-disk gh-pages leak directories
        // (<pomDir>/<artifactId>/<artifactId>/index.html — the #358
        // signature). Those are produced by a stale ike-parent's
        // broken <site><url> inheritance; once alignment bumps the
        // parent to a fixed version (v45+), they stop being produced,
        // but the existing dirs remain on disk until somebody
        // explicitly removes them. Doing it here avoids the
        // chicken-and-egg where the NO_ON_DISK_GHPAGES_LEAK preflight
        // would block on a leak that alignment is about to fix at
        // the source — a stale workspace on its first cascade after
        // the fix shipped.
        //
        // Runs BEFORE preflight (rather than after) for the same
        // reason: alignment and the leak cleanup it triggers are
        // exactly what makes the preflight checks pass. WORKING_TREE_CLEAN
        // is unaffected — alignment commits its own changes, leaving
        // worktree clean from git's POV; gitignored leak dirs were
        // never counted by `git status --porcelain` anyway.
        //
        // Publish-only: drafts skip alignment because the goal is
        // preview, not mutation. A draft will under-report
        // workspaces with stale parents — accept that for now;
        // an alignment dry-run mode is a follow-up.
        if (publish) {
            preReleaseUpstreamAlignment(graph, root);
        }

        // ── Preflight: all working trees clean, no POM-shape gotchas ──
        // (Javadoc cleanliness is checked per-module by ike:release
        //  preflight — see ReleaseDraftMojo — so every entry point
        //  enforces it, not only workspace-level releases.)
        //
        // #346 expanded the preflight set so the dry-run is
        // authoritative: every cascade-time gotcha that has bitten a
        // release is checked at draft time, not discovered mid-flight
        // after some subprojects have already tagged:
        //   WORKING_TREE_CLEAN                   — #132 #154
        //   NO_SNAPSHOT_PROPERTIES               — Maven 4 consumer
        //                                          flattener leak
        //   SUBPROJECT_HAS_DISTRIBUTION_MANAGEMENT — site:stage gate
        //                                          (#343 surfaced)
        //   NO_FOUNDATION_PROPERTY_SHADOWING     — ike-tooling.version
        //                                          shadowing pinned the
        //                                          plugin to a stale
        //                                          version that lacked
        //                                          newer goals
        //   PARENT_COHERENCE                     — #324 release gate
        //                                          form
        PreflightResult releasePreflight = Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN,
                        PreflightCondition.NO_ON_DISK_GHPAGES_LEAK,
                        PreflightCondition.NO_SCPEXE_SITE_URLS,
                        PreflightCondition.NO_SNAPSHOT_PROPERTIES,
                        PreflightCondition.SUBPROJECT_HAS_DISTRIBUTION_MANAGEMENT,
                        PreflightCondition.NO_FOUNDATION_PROPERTY_SHADOWING,
                        PreflightCondition.PARENT_COHERENCE),
                PreflightContext.of(root, graph, candidates));
        if (draft) {
            releasePreflight.warnIfFailed(getLog(), WsGoal.RELEASE_PUBLISH);
        } else {
            releasePreflight.requirePassed(WsGoal.RELEASE_PUBLISH);
        }

        // ── 2a. Detect source-changed checked-out subprojects ────────────
        // First pass: gather the set of subprojects whose own commits
        // require a release. Cascade-only downstream is added in 2b.
        Map<String, ReleaseCandidate> releasable = new LinkedHashMap<>();
        Set<String> sourceChanged = new LinkedHashSet<>();
        for (String name : graph.topologicalSort()) {
            if (!candidates.contains(name)) continue;

            Subproject sub = graph.manifest().subprojects().get(name);
            if (sub == null) continue;

            File subDir = new File(root, name);
            if (!subDir.isDirectory() || !new File(subDir, "pom.xml").exists()) {
                getLog().debug("Skipping " + name + " — not checked out");
                continue;
            }

            String latestTag = latestReleaseTag(subDir);
            if (latestTag == null) {
                // No release tag exists — subproject has never been released
                releasable.put(name, new ReleaseCandidate(name, sub, subDir,
                        null, "never released"));
                sourceChanged.add(name);
                continue;
            }

            // #347: count only commits whose subjects don't match
            // the release-cadence pattern, so retries of a partial
            // cascade don't re-release subprojects whose only
            // post-tag commits are release/merge/post-release/site
            // bookkeeping from a previous successful attempt.
            int meaningfulCommits =
                    meaningfulCommitsSinceTag(subDir, latestTag);
            if (meaningfulCommits > 0) {
                releasable.put(name, new ReleaseCandidate(name, sub, subDir,
                        latestTag,
                        meaningfulCommits + " commits since " + latestTag));
                sourceChanged.add(name);
                continue;
            }

            getLog().debug("Skipping " + name + " — clean (at "
                    + latestTag + "; only cadence commits since)");
        }

        // ── 2b. Cascade — add transitive downstream of source-changed ───
        // Every checked-out subproject that depends (directly or
        // transitively) on a source-changed subproject must also release
        // so its parent/property references can pick up the new upstream
        // version. Catch-up never expands the release set: subprojects
        // with stale properties but no source change and no upstream in
        // this cycle stay out.
        Set<String> releaseSet = computeReleaseSet(graph, sourceChanged);
        for (String name : releaseSet) {
            if (releasable.containsKey(name)) continue;

            Subproject sub = graph.manifest().subprojects().get(name);
            if (sub == null) continue;

            File subDir = new File(root, name);
            if (!subDir.isDirectory() || !new File(subDir, "pom.xml").exists()) {
                getLog().info("  Skipping cascaded " + name
                        + " — not checked out (downstream version stays stale)");
                continue;
            }

            String latestTag = latestReleaseTag(subDir);
            String reason = "downstream of " + describeUpstreamCause(
                    name, graph, sourceChanged);
            releasable.put(name, new ReleaseCandidate(name, sub, subDir,
                    latestTag, reason));
        }

        if (releasable.isEmpty()) {
            getLog().info("No components need releasing. All are clean.");
            return new WorkspaceReportSpec(
                    publish ? WsGoal.RELEASE_PUBLISH : WsGoal.RELEASE_DRAFT,
                    "No components need releasing — all are clean.\n");
        }

        // ── 3. Topological sort of release-pending components ────────────
        List<String> releaseOrder = graph.topologicalSort().stream()
                .filter(releasable::containsKey)
                .toList();

        // ── 4. Report plan ────────────────────────────────────────────
        getLog().info("════════════════════════════════════════════════════");
        getLog().info(draft ? "  WORKSPACE RELEASE — DRAFT" : "  WORKSPACE RELEASE");
        getLog().info("════════════════════════════════════════════════════");
        getLog().info("");
        getLog().info("Components to release (" + releaseOrder.size() + "):");
        for (int i = 0; i < releaseOrder.size(); i++) {
            ReleaseCandidate rc = releasable.get(releaseOrder.get(i));
            String version = currentVersion(rc.dir);
            getLog().info("  " + (i + 1) + ". " + rc.name
                    + " (" + version + ") — " + rc.reason);
        }
        getLog().info("");

        // ── 4a. Compute release plan (single source of truth) ────────
        // One plan for the entire cascade, computed once up front. Every
        // pre-release alignment is a blind lookup in this plan — no
        // mid-flight heuristics. See dev-release-plan design topic.
        ReleasePlan plan;
        try {
            plan = buildReleasePlan(releaseOrder, releasable);
        } catch (IOException e) {
            throw new MojoException(
                    "Release plan compute failed: " + e.getMessage(), e);
        }
        logReleasePlan(plan);
        writeReleasePlan(root, plan);

        if (draft) {
            getLog().info("[DRAFT] No releases executed (draft mode).");
            return new WorkspaceReportSpec(WsGoal.RELEASE_DRAFT,
                    buildReleasePlanMarkdownReport(releaseOrder, releasable));
        }

        // ── 5. Pre-release checkpoint ─────────────────────────────────
        if (!skipCheckpoint) {
            String checkpointName = "pre-release-"
                    + Instant.now().atZone(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            getLog().info("Creating pre-release checkpoint: " + checkpointName);
            writeCheckpoint(root, graph, checkpointName);
        }

        // ── 6. Release each subproject in order ────────────────────────
        List<String> released = new ArrayList<>();
        Map<String, String> releasedVersions = new LinkedHashMap<>();

        // Capture (subproject name → post-release SNAPSHOT) so we can
        // sync workspace.yaml after the cascade completes (#371).
        Map<String, String> manifestVersionUpdates = new LinkedHashMap<>();
        for (String name : releaseOrder) {
            ReleaseCandidate rc = releasable.get(name);
            getLog().info("");
            getLog().info("────────────────────────────────────────────────");
            getLog().info("  Releasing: " + rc.name);
            getLog().info("────────────────────────────────────────────────");

            // Catch-up alignment: bump every workspace-internal upstream
            // version reference (this-cycle bumps + catch-up to current
            // published versions) into a single commit. Hard-stops the
            // release on failure (#192) — no silent stale POMs.
            updateParentVersions(plan, rc, releasedVersions, root);

            // Derive release version from current SNAPSHOT
            String currentVersion = currentVersion(rc.dir);
            String releaseVersion = currentVersion.replace("-SNAPSHOT", "");

            try {
                // Find mvnw or mvn
                String mvn = findMvn(rc.dir);

                ReleaseSupport.exec(rc.dir, getLog(),
                        releaseCommand(mvn));

                released.add(rc.name);
                releasedVersions.put(rc.name, releaseVersion);
                getLog().info(Ansi.green("  ✓ ") + "Released " + rc.name + " " + releaseVersion);

                // Capture the post-release SNAPSHOT for workspace.yaml
                // sync (#371). ike:release-publish ended on a post-
                // release bump, so reading the POM now yields the new
                // -SNAPSHOT. Tolerate read failures: the release
                // succeeded, so don't fail the cascade over a manifest
                // sync hiccup — the gap will surface as a #371 warning
                // on the next preflight.
                try {
                    String postReleaseVersion = currentVersion(rc.dir);
                    if (postReleaseVersion != null
                            && !postReleaseVersion.isBlank()) {
                        manifestVersionUpdates.put(rc.name, postReleaseVersion);
                    }
                } catch (Exception readFail) {
                    getLog().warn("  ⚠ Could not read post-release version "
                            + "for " + rc.name + " — workspace.yaml "
                            + "version: field will stay stale until the "
                            + "next ws:scaffold-publish. "
                            + readFail.getMessage());
                }
            } catch (Exception e) {
                getLog().error(Ansi.red("  ✗ ") + "Failed to release " + rc.name + ": " + e.getMessage());
                getLog().error("");
                getLog().error("Released so far: " + released);
                getLog().error("Failed at: " + rc.name);
                getLog().error("Remaining: " + releaseOrder.subList(
                        releaseOrder.indexOf(name) + 1, releaseOrder.size()));
                throw new MojoException(
                        "Workspace release failed at " + rc.name, e);
            }
        }

        // ── 6a. Sync workspace.yaml version: fields (#371) ────────────
        // Each ike:release-publish bumped its subproject's POM but had
        // no visibility into the workspace manifest. Now that the
        // cascade is complete, fold the new SNAPSHOTs back into
        // workspace.yaml in one commit so the manifest stops drifting.
        // Filter to changes only — idempotent re-run of an already-
        // synced workspace writes nothing.
        if (!manifestVersionUpdates.isEmpty()) {
            syncWorkspaceVersions(root, manifestVersionUpdates);
        }

        // ── 6b. Release the workspace root last (#326, #328) ─────────
        // After all subprojects release, the workspace.yaml has been
        // updated (per-subproject version: pin) by the post-release
        // bumps inside ike:release-publish, AND the workspace pom may
        // have been touched earlier in the cycle (parent bump from
        // ws:scaffold-publish's ParentVersionReconciler,
        // .mvn/maven.config from ws:ide-sync).
        // The workspace itself is therefore source-changed and should
        // tag + deploy + refresh its site so the published cycle has
        // a single anchor: "the workspace was at this commit when
        // these subprojects released v_n".
        //
        // Skipped when nothing released (released.isEmpty()) since
        // there's no cycle to anchor.
        if (!released.isEmpty() && hasUnreleasedWorkspaceChanges(root)) {
            getLog().info("");
            getLog().info("────────────────────────────────────────────────");
            getLog().info("  Releasing: workspace root");
            getLog().info("────────────────────────────────────────────────");
            String workspaceCurrent = currentVersion(root);
            String workspaceVersion = workspaceCurrent.replace("-SNAPSHOT", "");
            try {
                String mvn = findMvn(root);
                // -DnonRecursiveSite=true on the workspace root release:
                // the workspace pom is an aggregator and every subproject
                // inherits a per-artifactId <site> URL with no common
                // ancestor, so running site:stage with the full reactor
                // active causes sibling modules to overwrite each other
                // at the same target/staging/ root. The last-built
                // subproject wins and the workspace's own staged content
                // is lost — publishProjectSiteToGhPages then ships
                // whichever subproject's content was last to land.
                // -N restricts the workspace's site build to its own
                // pom only; subprojects already published their own
                // sites in step 6 above. ike-issues#356.
                ReleaseSupport.exec(root, getLog(),
                        releaseCommand(mvn, "-DnonRecursiveSite=true"));
                released.add("(workspace root)");
                releasedVersions.put("(workspace root)", workspaceVersion);
                getLog().info(Ansi.green("  ✓ ") + "Released workspace root "
                        + workspaceVersion);
            } catch (Exception e) {
                getLog().error(Ansi.red("  ✗ ") + "Failed to release "
                        + "workspace root: " + e.getMessage());
                getLog().error("");
                getLog().error("Subprojects released so far: " + released);
                throw new MojoException(
                        "Workspace root release failed", e);
            }
        }

        // ── 7. Summary ───────────────────────────────────────────────
        getLog().info("");
        getLog().info("════════════════════════════════════════════════════");
        getLog().info("  WORKSPACE RELEASE COMPLETE");
        getLog().info("════════════════════════════════════════════════════");
        for (var entry : releasedVersions.entrySet()) {
            getLog().info("  " + entry.getKey() + " → " + entry.getValue());
        }
        getLog().info("");

        // ── 8. GitHub Release (optional) ──────────────────────────────
        if (githubRepo != null && !githubRepo.isBlank()) {
            createGitHubReleases(root, releasedVersions);
        }

        // Structured markdown report
        return new WorkspaceReportSpec(
                publish ? WsGoal.RELEASE_PUBLISH : WsGoal.RELEASE_DRAFT,
                buildReleaseMarkdownReport(releasedVersions));
    }

    /**
     * Compute the release set: source-changed subprojects union the
     * transitive downstream cascade of each.
     *
     * <p>This is a pure function over the workspace graph and the set
     * of source-changed subprojects. Cascade is computed via
     * {@link WorkspaceGraph#cascade(String)} (BFS on reverse edges).
     * Order in the returned set follows the graph's topological sort.
     *
     * <p>By construction, the release set contains every member of
     * {@code sourceChanged} plus everything that depends on any of
     * them (directly or transitively). It never contains a subproject
     * that has neither a source change nor a release-set upstream —
     * stale properties alone cannot expand the release set (see #192).
     *
     * @param graph         the workspace dependency graph
     * @param sourceChanged subproject names whose own commits warrant
     *                      a release this cycle
     * @return release set in topological order (dependencies first)
     */
    public static Set<String> computeReleaseSet(WorkspaceGraph graph,
                                                Set<String> sourceChanged) {
        Set<String> set = new LinkedHashSet<>(sourceChanged);
        for (String name : sourceChanged) {
            if (!graph.manifest().subprojects().containsKey(name)) continue;
            set.addAll(graph.cascade(name));
        }
        // Reorder by topo sort so the result is deterministic and matches
        // the dependency order callers expect.
        Set<String> ordered = new LinkedHashSet<>();
        for (String name : graph.topologicalSort()) {
            if (set.contains(name)) ordered.add(name);
        }
        return ordered;
    }

    /**
     * Describe which source-changed subproject(s) caused a downstream
     * subproject to be cascaded into the release set. Used purely for
     * the human-readable "downstream of X" reason in the release plan.
     *
     * <p>If multiple source-changed subprojects are upstream, returns
     * the topologically-nearest set joined by {@code ", "}.
     */
    private static String describeUpstreamCause(String downstream,
                                                 WorkspaceGraph graph,
                                                 Set<String> sourceChanged) {
        // Walk the forward edges to find which source-changed subprojects
        // this one transitively depends on. Use BFS from downstream.
        List<String> causes = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(downstream);
        visited.add(downstream);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            Subproject sub = graph.manifest().subprojects().get(current);
            if (sub == null) continue;
            for (network.ike.workspace.Dependency dep : sub.dependsOn()) {
                String up = dep.subproject();
                if (!visited.add(up)) continue;
                if (sourceChanged.contains(up)) {
                    causes.add(up);
                } else {
                    queue.add(up);
                }
            }
        }
        if (causes.isEmpty()) return "(unknown upstream)";
        return String.join(", ", causes);
    }

    /**
     * Create GitHub Releases for released components and attach
     * platform installers. Uses {@code gh} CLI. Each subproject gets
     * a release tagged {@code v<version>}. If the release already
     * exists, uploads are appended with {@code --clobber}.
     */
    private void createGitHubReleases(File root,
                                        Map<String, String> releasedVersions)
            throws MojoException {
        for (var entry : releasedVersions.entrySet()) {
            String name = entry.getKey();
            String version = entry.getValue();
            String tag = "v" + version;
            File subDir = new File(root, name);

            // Collect installer artifacts
            java.nio.file.Path targetDir = subDir.toPath().resolve("target");
            List<String> artifacts = new ArrayList<>();
            if (java.nio.file.Files.exists(targetDir)) {
                try {
                    java.nio.file.PathMatcher matcher =
                            targetDir.getFileSystem().getPathMatcher(
                                    "glob:" + installerGlob);
                    try (var walk = java.nio.file.Files.walk(targetDir, 3)) {
                        walk.filter(java.nio.file.Files::isRegularFile)
                            .filter(p -> matcher.matches(
                                    targetDir.relativize(p)))
                            .forEach(p -> artifacts.add(p.toString()));
                    }
                } catch (java.io.IOException e) {
                    getLog().debug("Could not scan installers for " + name
                            + ": " + e.getMessage());
                }
            }

            getLog().info("  Creating GitHub Release: " + tag
                    + (artifacts.isEmpty() ? ""
                        : " (" + artifacts.size() + " installer"
                          + (artifacts.size() == 1 ? "" : "s") + ")"));

            try {
                // Try create first; fall back to upload if release exists
                List<String> cmd = new ArrayList<>(List.of(
                        "gh", "release", "create", tag,
                        "--repo", githubRepo,
                        "--title", name + " " + version,
                        "--generate-notes"));
                cmd.addAll(artifacts);

                ReleaseSupport.exec(subDir, getLog(),
                        cmd.toArray(String[]::new));
            } catch (MojoException e) {
                // Release may already exist — append assets
                if (!artifacts.isEmpty()) {
                    try {
                        List<String> uploadCmd = new ArrayList<>(List.of(
                                "gh", "release", "upload", tag,
                                "--repo", githubRepo, "--clobber"));
                        uploadCmd.addAll(artifacts);
                        ReleaseSupport.exec(subDir, getLog(),
                                uploadCmd.toArray(String[]::new));
                    } catch (MojoException uploadErr) {
                        getLog().warn("  Could not upload to release " + tag
                                + ": " + uploadErr.getMessage());
                    }
                } else {
                    getLog().warn("  GitHub Release creation failed for "
                            + tag + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Build the draft-mode report: the planned release order and the
     * reason each subproject is included. No subproject is released in
     * draft mode, so this records intent rather than outcomes.
     *
     * @param releaseOrder topologically sorted names of the release plan
     * @param releasable   release candidates keyed by subproject name
     * @return the Markdown report body
     */
    private String buildReleasePlanMarkdownReport(
            List<String> releaseOrder,
            Map<String, ReleaseCandidate> releasable) {
        List<String[]> rows = new ArrayList<>();
        for (String name : releaseOrder) {
            ReleaseCandidate rc = releasable.get(name);
            rows.add(new String[] {
                    rc.name, currentVersion(rc.dir), rc.reason });
        }
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph(releaseOrder.size()
                        + " subproject(s) would be released (draft).")
                .table(List.of("Subproject", "Version", "Reason"), rows);
        return report.build();
    }

    private String buildReleaseMarkdownReport(
            Map<String, String> releasedVersions) {
        List<String[]> rows = new ArrayList<>();
        for (var entry : releasedVersions.entrySet()) {
            rows.add(new String[] {
                    entry.getKey(), entry.getValue(), "✓" });
        }
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph(releasedVersions.size()
                        + " subproject(s) released.")
                .table(List.of("Subproject", "Version", "Status"), rows);
        return report.build();
    }

    // ── Helper: find latest release tag ──────────────────────────────

    private String latestReleaseTag(File subDir) {
        try {
            String tags = ReleaseSupport.execCapture(subDir,
                    "git", "tag", "-l", "v*", "--sort=-version:refname");
            if (tags == null || tags.isBlank()) return null;
            return tags.lines().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helper: count commits since tag ──────────────────────────────

    private int commitsSinceTag(File subDir, String tag) {
        try {
            String count = ReleaseSupport.execCapture(subDir,
                    "git", "rev-list", tag + "..HEAD", "--count");
            return Integer.parseInt(count.strip());
        } catch (Exception e) {
            return -1;
        }
    }

    // ── Helper: count meaningful (non-release-cadence) commits ────────
    //
    // ike-issues#347: when a partial cascade fails after some
    // subprojects have already released, retries kept finding "new"
    // commits since each subproject's tag — the post-release-bump
    // commit, the merge commit, and the release-set-version commit
    // produced by the prior attempt's ike:release-publish. Each retry
    // saw commitsSinceTag > 0 and re-released, ratcheting subprojects
    // forward by one version per retry.
    //
    // Filter out commits whose subject matches the well-known
    // release-cadence patterns produced by ReleaseSupport:
    //   - "release: set version to N"
    //   - "release: restore ${project.version} references"
    //   - "merge: release N"
    //   - "post-release: bump to <next>-SNAPSHOT"
    //   - "site: publish <project> N"
    //
    // If every commit since the tag matches one of those patterns,
    // the subproject has no real source changes — return 0 so the
    // outer logic treats it as already-released.

    private static final java.util.regex.Pattern RELEASE_CADENCE_PATTERN =
            java.util.regex.Pattern.compile(
                    "^(release: set version to .+"
                            + "|release: restore .+"
                            + "|merge: release .+"
                            + "|post-release: bump to .+"
                            + "|site: publish .+)$");

    /**
     * Count commits between {@code tag} and HEAD whose subjects do
     * NOT match a release-cadence pattern.
     *
     * <p>Used in place of {@link #commitsSinceTag(File, String)} for
     * "do we need to release this again?" decisions, so that retries
     * after a partial cascade failure don't re-release subprojects
     * whose only post-tag commits are cadence-emitted ones.
     *
     * @param subDir the subproject directory
     * @param tag    the latest release tag
     * @return number of non-cadence commits since {@code tag}, or
     *         {@code -1} on error
     */
    int meaningfulCommitsSinceTag(File subDir, String tag) {
        try {
            String log = ReleaseSupport.execCapture(subDir,
                    "git", "log", tag + "..HEAD",
                    "--pretty=format:%s", "--no-merges");
            if (log == null) return 0;
            String trimmed = log.strip();
            if (trimmed.isEmpty()) return 0;
            int count = 0;
            for (String subject : trimmed.split("\n")) {
                if (!RELEASE_CADENCE_PATTERN.matcher(subject.strip()).matches()) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Test whether a commit subject matches a release-cadence
     * pattern (would be filtered out by
     * {@link #meaningfulCommitsSinceTag}). Public for unit testing.
     *
     * @param subject the commit subject line
     * @return {@code true} when the subject is cadence-emitted
     */
    public static boolean isReleaseCadenceCommit(String subject) {
        if (subject == null) return false;
        return RELEASE_CADENCE_PATTERN.matcher(subject.strip()).matches();
    }

    // ── Pre-release upstream alignment (#377) ─────────────────────────
    // Foundation-tracking map used by preReleaseUpstreamAlignment.
    // groupId → foundation name in ~/ike-dev/<name>/. Each foundation
    // releases as a single Maven reactor whose tip v* tag is the
    // version we'll align downstream consumers to.
    static final Map<String, String> FOUNDATION_GROUP_TO_DIR = Map.of(
            "network.ike.tooling", "ike-tooling",
            "network.ike.docs", "ike-docs",
            "network.ike.platform", "ike-platform");

    // Property-name → groupId. Properties shaped <X.version> conventionally
    // pin coordinates whose groupId starts with "network.ike.X" (with
    // ike-platform handling both "platform" and "parent" because
    // ike-parent ships from the ike-platform reactor).
    static final Map<String, String> PROPERTY_TO_GROUP = Map.of(
            "ike-tooling.version", "network.ike.tooling",
            "ike-docs.version", "network.ike.docs",
            "ike-platform.version", "network.ike.platform");

    /**
     * Before release detection runs, walk each subproject (and the
     * workspace root) and bump any stale upstream-foundation references
     * to the latest released version. An upstream is "stale" when the
     * pom declares a {@code <parent>} or {@code <X.version>} property
     * pinned older than the foundation's tip {@code v*} tag in the
     * canonical {@code ~/ike-dev/<foundation>/} layout. Bumps land as
     * "chore: align upstream versions before release" commits per
     * subproject — those commits register as meaningful, so the
     * {@link #meaningfulCommitsSinceTag} detector includes the
     * subproject in the release set automatically.
     *
     * <p>This is what makes "ike-parent was released, so absorb it"
     * a release-worthy change. Without it, ws:release-publish only
     * saw per-subproject source edits and missed transitive-dependency
     * upgrades entirely (ike-issues#377).
     *
     * <p>Non-fatal: failures (unreadable poms, git commit errors)
     * log a warning and continue. Worst case the alignment doesn't
     * commit and the release subsequently treats the subproject as
     * "no meaningful commits" — same outcome as before this method
     * existed, no regression.
     *
     * @param graph the loaded workspace graph
     * @param root  the workspace root directory
     */
    private void preReleaseUpstreamAlignment(WorkspaceGraph graph, File root) {
        File foundationsDir = root.getParentFile();
        if (foundationsDir == null || !foundationsDir.isDirectory()) {
            getLog().debug("  No siblings directory available for foundation lookup; "
                    + "skipping pre-release alignment.");
            return;
        }

        // Build groupId → latest released version once.
        Map<String, String> groupIdToLatest = new LinkedHashMap<>();
        for (var entry : FOUNDATION_GROUP_TO_DIR.entrySet()) {
            File siblingDir = new File(foundationsDir, entry.getValue());
            if (!siblingDir.isDirectory()) continue;
            String tag = latestReleaseTag(siblingDir);
            if (tag == null) continue;
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            groupIdToLatest.put(entry.getKey(), version);
        }
        if (groupIdToLatest.isEmpty()) {
            getLog().debug("  No foundation tags found in " + foundationsDir
                    + "; skipping pre-release alignment.");
            return;
        }

        // Walk: workspace root + each subproject.
        List<File> poms = new ArrayList<>();
        poms.add(new File(root, "pom.xml"));
        for (String name : graph.manifest().subprojects().keySet()) {
            File sub = new File(new File(root, name), "pom.xml");
            if (sub.isFile()) poms.add(sub);
        }

        int aligned = 0;
        int leaksCleaned = 0;
        for (File pom : poms) {
            if (alignPom(pom, groupIdToLatest)) aligned++;
            if (cleanGhPagesLeak(pom)) leaksCleaned++;
        }
        if (aligned > 0) {
            getLog().info("  Pre-release alignment: bumped upstream references in "
                    + aligned + " pom(s) (#377).");
        }
        if (leaksCleaned > 0) {
            getLog().info("  Pre-release alignment: removed gh-pages leak from "
                    + leaksCleaned + " pom dir(s) (#358).");
        }
    }

    /**
     * Auto-clean any on-disk gh-pages leak directory under the given
     * pom's project directory. The leak signature is exactly the one
     * {@code PreflightCondition.NO_ON_DISK_GHPAGES_LEAK} detects:
     * {@code <pomDir>/<artifactId>/<artifactId>/index.html} — produced
     * by maven-site-plugin's site:stage when {@code ike-parent}'s
     * site URL inheritance was broken (ike-issues#358; root cause
     * fixed in ike-parent v45+). The directory always escapes
     * {@code target/} and is gitignored, so {@code git status} doesn't
     * see it — operators discover it only when the preflight blocks
     * their release.
     *
     * <p>This pre-release step cleans the leak BEFORE the preflight
     * runs, so a workspace inheriting a still-stale ike-parent (and
     * therefore still producing leaks) can bootstrap to a newer
     * ike-parent in the same cascade without the operator having to
     * {@code rm -rf} by hand. After that one bootstrap cascade,
     * future builds don't leak.
     *
     * <p>Per {@code feedback_workspace_ops_completion}: recoverable
     * side effects default on.
     *
     * @param pomFile the pom whose project directory to inspect
     * @return {@code true} when a leak was cleaned, {@code false} otherwise
     */
    private boolean cleanGhPagesLeak(File pomFile) {
        File pomDir = pomFile.getParentFile();
        if (pomDir == null) return false;
        String artifactId;
        try {
            String content = Files.readString(pomFile.toPath(),
                    StandardCharsets.UTF_8);
            artifactId = extractArtifactId(content);
        } catch (IOException e) {
            return false;
        }
        if (artifactId == null || artifactId.isBlank()) return false;
        java.nio.file.Path leakDir = pomDir.toPath()
                .resolve(artifactId).resolve(artifactId);
        java.nio.file.Path leakIndex = leakDir.resolve("index.html");
        if (!Files.isRegularFile(leakIndex)) return false;
        // The OUTER doubled dir is the one to remove; .resolve(artifactId)
        // once gives us <pomDir>/<artifactId>/ which is what
        // NO_ON_DISK_GHPAGES_LEAK's report prints as the rm-rf path.
        java.nio.file.Path outerLeak = pomDir.toPath().resolve(artifactId);
        try {
            deleteRecursively(outerLeak);
            getLog().info("    Cleaned gh-pages leak: " + pomDir.getName()
                    + "/" + artifactId + "/ (#358)");
            return true;
        } catch (IOException e) {
            getLog().warn("    Could not clean leak dir " + outerLeak
                    + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Extract the project's own {@code <artifactId>}. Skips a
     * preceding {@code <parent>} block so we don't return the parent's
     * artifactId. Same shape as the helper in
     * {@code RegisterSiteDraftMojo} — repeated here to keep the
     * dependency direction (this mojo doesn't depend on it).
     */
    private static String extractArtifactId(String pomContent) {
        if (pomContent == null) return null;
        int searchFrom = 0;
        int parentOpen = pomContent.indexOf("<parent>");
        if (parentOpen >= 0) {
            int parentClose = pomContent.indexOf("</parent>", parentOpen);
            if (parentClose > parentOpen) {
                searchFrom = parentClose + "</parent>".length();
            }
        }
        int open = pomContent.indexOf("<artifactId>", searchFrom);
        if (open < 0) return null;
        int valueStart = open + "<artifactId>".length();
        int close = pomContent.indexOf("</artifactId>", valueStart);
        if (close < 0) return null;
        return pomContent.substring(valueStart, close).trim();
    }

    /**
     * Delete a directory tree recursively. Mirrors common helpers in
     * the codebase but inlined to avoid coupling to a specific util.
     */
    private static void deleteRecursively(java.nio.file.Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch (IOException ignore) { /* best effort */ }
                    });
        }
    }

    /**
     * Apply alignment to one pom. Returns {@code true} when the pom
     * was changed + committed; {@code false} when no change was
     * needed (idempotent re-run).
     *
     * @param pomFile         the pom to align
     * @param groupIdToLatest groupId → latest released version
     * @return whether the pom was bumped
     */
    private boolean alignPom(File pomFile, Map<String, String> groupIdToLatest) {
        String content;
        try {
            content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLog().warn("  Could not read " + pomFile + " for alignment: "
                    + e.getMessage());
            return false;
        }
        String original = content;
        List<String> bumps = new ArrayList<>();

        // Align <parent> block.
        try {
            PomParentSupport.ParentInfo parent =
                    PomParentSupport.readParent(pomFile.toPath());
            if (parent != null) {
                String target = groupIdToLatest.get(parent.groupId());
                if (target != null && !target.equals(parent.version())) {
                    content = PomParentSupport.updateParentVersion(content,
                            parent.groupId(), parent.artifactId(), target);
                    bumps.add("<parent>" + parent.groupId() + ":"
                            + parent.artifactId() + ">: "
                            + parent.version() + " → " + target);
                }
            }
        } catch (IOException e) {
            getLog().warn("  Could not read parent block of " + pomFile
                    + ": " + e.getMessage());
        }

        // Align <X.version> properties.
        for (var entry : PROPERTY_TO_GROUP.entrySet()) {
            String propertyName = entry.getKey();
            String groupId = entry.getValue();
            String target = groupIdToLatest.get(groupId);
            if (target == null) continue;
            String current = extractPropertyValue(content, propertyName);
            if (current == null) continue;
            if (target.equals(current)) continue;
            content = PomRewriter.updateProperty(content, propertyName, target);
            bumps.add("<" + propertyName + ">: " + current + " → " + target);
        }

        if (content.equals(original)) {
            return false;
        }

        try {
            Files.writeString(pomFile.toPath(), content,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLog().warn("  Could not write aligned " + pomFile
                    + ": " + e.getMessage());
            return false;
        }

        File pomDir = pomFile.getParentFile();
        if (!new File(pomDir, ".git").isDirectory()) {
            // No git repo here — leave the worktree edit for ws:commit-publish
            // (or a sibling tool) to pick up later. Same fail-soft
            // pattern as #371 manifest sync.
            getLog().info("  Pre-release alignment: " + pomDir.getName()
                    + " (no .git — bumped on disk, not committed):");
            for (String b : bumps) getLog().info("    " + b);
            return false;
        }
        try {
            ReleaseSupport.exec(pomDir, getLog(), "git", "add", "pom.xml");
            ReleaseSupport.exec(pomDir, getLog(), "git", "commit", "-m",
                    "chore: align upstream versions before release");
        } catch (Exception e) {
            getLog().warn("  Pre-release alignment commit failed for "
                    + pomDir.getName() + ": " + e.getMessage());
            return false;
        }
        getLog().info("  Pre-release alignment: " + pomDir.getName());
        for (String b : bumps) getLog().info("    " + b);
        return true;
    }

    /**
     * Pure-string extract of a {@code <properties>}-block value by
     * name. Returns {@code null} when absent.
     */
    static String extractPropertyValue(String pomContent,
                                                 String propertyName) {
        if (pomContent == null) return null;
        String openTag = "<" + propertyName + ">";
        int open = pomContent.indexOf(openTag);
        if (open < 0) return null;
        int valueStart = open + openTag.length();
        int close = pomContent.indexOf("</" + propertyName + ">",
                valueStart);
        if (close < 0) return null;
        return pomContent.substring(valueStart, close).trim();
    }

    // ── Helper: workspace root has unreleased changes? ───────────────
    // ike-issues#328: the workspace itself participates in the
    // release set when source-changed. Returns true when the
    // workspace has never been tagged or has commits since its last
    // release tag.

    private boolean hasUnreleasedWorkspaceChanges(File root) {
        // Treat the workspace as a git repo only if .git is present.
        // Some workspace setups (e.g., a Syncthing-only checkout
        // without a per-machine git init) won't have one and there's
        // nothing to release.
        if (!new File(root, ".git").exists()) {
            return false;
        }
        String latestTag = latestReleaseTag(root);
        if (latestTag == null) {
            // Never released — the first cycle that touches the
            // workspace anchors it.
            return true;
        }
        // #347: filter out cadence commits so a previous successful
        // workspace release isn't seen as "still needs releasing"
        // on a retry triggered by a downstream subproject failure.
        return meaningfulCommitsSinceTag(root, latestTag) > 0;
    }

    /**
     * Write the post-cascade {@code version:} updates into
     * {@code workspace.yaml} and commit. Filters out no-op entries
     * (where the manifest already matches the new SNAPSHOT) so an
     * idempotent re-run writes nothing — important because
     * {@code WORKING_TREE_CLEAN} on the workspace root would otherwise
     * fail on a re-run that "succeeded" but left a manifest dirty
     * with no actual changes.
     *
     * <p>Failures are logged but do not abort the cascade: the
     * subproject release tags + Nexus deploys have already shipped,
     * so a manifest-sync hiccup is recoverable via
     * {@code ws:scaffold-publish} or the next release cycle.
     * ike-issues#371.
     *
     * @param root              workspace root
     * @param versionUpdates    subprojectName → new SNAPSHOT (full
     *                          post-release pom version)
     */
    private void syncWorkspaceVersions(File root,
                                        Map<String, String> versionUpdates) {
        Path manifestPath = root.toPath().resolve("workspace.yaml");
        if (!Files.isRegularFile(manifestPath)) {
            getLog().debug("  No workspace.yaml at " + manifestPath
                    + " — skipping manifest sync (#371)");
            return;
        }

        String before;
        try {
            before = Files.readString(manifestPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLog().warn("  ⚠ Could not read workspace.yaml for "
                    + "manifest sync (#371): " + e.getMessage());
            return;
        }

        String after = before;
        for (Map.Entry<String, String> entry : versionUpdates.entrySet()) {
            after = ManifestWriter.updateSubprojectField(
                    after, entry.getKey(), "version", entry.getValue());
        }

        if (after.equals(before)) {
            getLog().debug("  workspace.yaml already in sync — "
                    + "no manifest write needed (#371)");
            return;
        }

        try {
            Files.writeString(manifestPath, after, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLog().warn("  ⚠ Could not write workspace.yaml for "
                    + "manifest sync (#371): " + e.getMessage());
            return;
        }

        getLog().info("");
        getLog().info("  Synced workspace.yaml version: fields for "
                + versionUpdates.size() + " subproject(s) (#371)");

        // Stage and commit on the workspace root only if it's a git
        // repo. If staging or commit fails, the file write already
        // happened — leave it for ws:commit-publish to pick up rather
        // than wedging the cascade.
        if (!new File(root, ".git").exists()) {
            return;
        }
        try {
            ReleaseSupport.exec(root, getLog(),
                    "git", "add", "workspace.yaml");
            ReleaseSupport.exec(root, getLog(),
                    "git", "commit", "-m",
                    "post-release: sync workspace.yaml versions (#371)");
        } catch (Exception e) {
            getLog().warn("  ⚠ workspace.yaml updated on disk but "
                    + "could not commit (#371): " + e.getMessage()
                    + ". Pick it up with ws:commit-publish.");
        }
    }

    // ── Helper: read current POM version ─────────────────────────────

    private String currentVersion(File subDir) {
        try {
            Path pom = subDir.toPath().resolve("pom.xml");
            String content = Files.readString(pom, StandardCharsets.UTF_8);
            return extractVersionFromPom(content);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Extract the project's own {@code <version>} value from POM XML
     * content.
     *
     * <p>Strips any {@code <parent>...</parent>} block before scanning
     * so we don't accidentally return the inherited parent's version
     * for projects that declare a parent (like the workspace root pom
     * inheriting {@code ike-parent}). Then takes the first remaining
     * {@code <version>}, which is the project's own.
     *
     * @param pomContent raw POM XML as a string
     * @return the version string, or {@code "unknown"} if not found
     */
    public static String extractVersionFromPom(String pomContent) {
        if (pomContent == null || pomContent.isBlank()) return "unknown";
        // Strip <parent>...</parent> so its <version> doesn't match.
        String stripped = pomContent.replaceAll(
                "(?s)<parent>.*?</parent>", "");
        var matcher = java.util.regex.Pattern.compile(
                "<version>([^<]+)</version>").matcher(stripped);
        if (matcher.find()) return matcher.group(1);
        return "unknown";
    }

    // ── Helper: catch-up alignment for a single subproject ──────────

    /**
     * Catch-up alignment for a single subproject, driven by the
     * pre-computed {@link ReleasePlan}.
     *
     * <p>Two passes:
     * <ol>
     *   <li><b>Plan-driven, in-cascade:</b> walk the plan's artifact
     *       and property entries; apply updates to any POM under
     *       {@code rc.dir}. Property names, target values, and POM paths
     *       are all pre-computed — no heuristics, no reinterpretation.
     *       Covers child-override properties and parent references in
     *       submodules that the old manifest-only logic missed.</li>
     *   <li><b>Out-of-cascade catch-up:</b> for each manifest dependency
     *       whose upstream is <em>not</em> in the cascade, align the
     *       root POM's parent ref and manifest-declared property to the
     *       upstream's current on-disk version. This rescues stale
     *       properties without expanding the release set.</li>
     * </ol>
     *
     * <p>All bumps for a single subproject batch into one git commit.
     * If any POM rewrite, write, or git command fails, this method
     * throws {@link MojoException} so the release loop halts — silent
     * partial alignment is never acceptable (#192).
     *
     * @param plan             the pre-computed release plan
     * @param rc               the subproject being prepared for release
     * @param releasedVersions this-cycle release map (for catch-up logging)
     * @param root             workspace root (for reading upstream POMs
     *                         that aren't in the plan)
     * @throws MojoException if POM I/O, git add, or git commit fails
     */
    private void updateParentVersions(ReleasePlan plan,
                                       ReleaseCandidate rc,
                                       Map<String, String> releasedVersions,
                                       File root) throws MojoException {
        Path rcDir = rc.dir.toPath().toAbsolutePath().normalize();
        Map<Path, String> pomContent = new LinkedHashMap<>();

        try {
            // ── 1. Plan-driven: in-cascade property updates ─────────
            for (PropertyReleasePlan pp : plan.properties()) {
                Path decl = pp.declaringPomPath();
                if (!decl.startsWith(rcDir)) continue;
                String before = pomContent.computeIfAbsent(decl, this::readPomContent);
                String after = PomRewriter.updateProperty(
                        before, pp.propertyName(), pp.releaseValue());
                if (!after.equals(before)) {
                    pomContent.put(decl, after);
                    getLog().info("    " + rc.name + " ("
                            + rcDir.relativize(decl) + "): "
                            + pp.propertyName() + " → " + pp.releaseValue()
                            + " (plan)");
                }
            }

            // ── 2. Plan-driven: in-cascade parent updates ───────────
            for (ArtifactReleasePlan ap : plan.artifacts().values()) {
                for (ReferenceSite site : ap.referenceSites()) {
                    if (site.kind() != ReferenceKind.PARENT) continue;
                    Path pomPath = site.pomPath();
                    if (!pomPath.startsWith(rcDir)) continue;
                    String before = pomContent.computeIfAbsent(pomPath, this::readPomContent);
                    String after = PomRewriter.updateParentVersion(
                            before, ap.ga().groupId(), ap.ga().artifactId(),
                            ap.releaseValue());
                    if (!after.equals(before)) {
                        pomContent.put(pomPath, after);
                        getLog().info("    " + rc.name + " ("
                                + rcDir.relativize(pomPath) + "): "
                                + "parent " + ap.ga().artifactId()
                                + " → " + ap.releaseValue() + " (plan)");
                    }
                }
            }

            // ── 3. Out-of-cascade catch-up ──────────────────────────
            // Read the RC's root <parent> block once so the catch-up
            // match can require BOTH groupId and artifactId (#241).
            Set<String> inPlan = plan.artifacts().values().stream()
                    .map(ArtifactReleasePlan::producingSubproject)
                    .collect(Collectors.toSet());
            Path rootPom = rcDir.resolve("pom.xml");
            PomParentSupport.ParentInfo rootPomParent;
            try {
                rootPomParent = PomParentSupport.readParent(rootPom);
            } catch (IOException e) {
                getLog().warn("    " + rc.name + ": cannot read root"
                        + " <parent> for catch-up — " + e.getMessage());
                rootPomParent = null;
            }
            for (network.ike.workspace.Dependency dep : rc.subproject.dependsOn()) {
                if (inPlan.contains(dep.subproject())) continue;
                String target = upstreamTargetVersion(
                        dep.subproject(), releasedVersions, root);
                if (target == null) {
                    getLog().debug("    " + rc.name + ": no target for "
                            + dep.subproject() + " (not in plan, not on disk)");
                    continue;
                }
                String before = pomContent.computeIfAbsent(rootPom, this::readPomContent);
                // Only update the root <parent> when the dep's
                // subproject name matches the root parent's artifactId.
                // The full GA is then used for the rewrite so unrelated
                // groupIds with the same artifactId aren't touched (#241).
                if (rootPomParent != null
                        && dep.subproject().equals(rootPomParent.artifactId())) {
                    String after = PomRewriter.updateParentVersion(
                            before, rootPomParent.groupId(),
                            rootPomParent.artifactId(), target);
                    if (!after.equals(before)) {
                        pomContent.put(rootPom, after);
                        getLog().info("    " + rc.name + ": parent "
                                + rootPomParent.groupId() + ":"
                                + rootPomParent.artifactId() + " → "
                                + target + " (out-of-cascade catch-up)");
                        before = after;
                    }
                }
                if (dep.versionProperty() != null) {
                    String after = PomRewriter.updateProperty(
                            before, dep.versionProperty(), target);
                    if (!after.equals(before)) {
                        pomContent.put(rootPom, after);
                        getLog().info("    " + rc.name + ": "
                                + dep.versionProperty() + " → " + target
                                + " (out-of-cascade catch-up)");
                    }
                }
            }
        } catch (UncheckedIOException e) {
            throw new MojoException(
                    "Catch-up alignment for " + rc.name
                            + " failed reading POM: " + e.getMessage(),
                    e.getCause());
        }

        // ── 4. Write modified POMs ──────────────────────────────────
        List<Path> changedPoms = new ArrayList<>();
        for (Map.Entry<Path, String> entry : pomContent.entrySet()) {
            Path path = entry.getKey();
            String content = entry.getValue();
            String original;
            try {
                original = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new MojoException(
                        "Catch-up alignment for " + rc.name
                                + " failed: cannot read " + path + ": "
                                + e.getMessage(), e);
            }
            if (!content.equals(original)) {
                try {
                    Files.writeString(path, content, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new MojoException(
                            "Catch-up alignment for " + rc.name
                                    + " failed: cannot write " + path
                                    + ": " + e.getMessage(), e);
                }
                changedPoms.add(path);
            }
        }
        if (!changedPoms.isEmpty()) {
            getLog().info("  Updated " + changedPoms.size()
                    + " POM(s) in " + rc.name);
        }

        if (changedPoms.isEmpty()) return;

        // ── 5. Stage + commit in one batch ──────────────────────────
        try {
            for (Path p : changedPoms) {
                ReleaseSupport.exec(rc.dir, getLog(),
                        "git", "add", rcDir.relativize(p).toString());
            }
            ReleaseSupport.exec(rc.dir, getLog(),
                    "git", "commit", "-m",
                    "chore: align upstream versions before release");
        } catch (MojoException e) {
            throw new MojoException(
                    "Catch-up alignment for " + rc.name
                            + " failed at git commit: " + e.getMessage(), e);
        }
    }

    /**
     * Read POM content as UTF-8. Used inside
     * {@link Map#computeIfAbsent}; wraps {@link IOException} as
     * {@link UncheckedIOException} which the caller re-throws as
     * {@link MojoException}.
     */
    private String readPomContent(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Build the full cascade's release plan from the release order and
     * the set of releasable candidates. Each subproject becomes an
     * {@link ArtifactReleaseIntent} whose pre/release/post values are
     * derived from the subproject's current {@code <version>} (must end
     * in {@code -SNAPSHOT}).
     *
     * <p>The reactor scan walks each released subproject's root POM to
     * collect every property declaration and reference site across the
     * cascade. Out-of-cascade subprojects do not participate; their
     * POMs are not scanned and their properties do not appear in the
     * plan.
     *
     * @param releaseOrder the topologically-sorted subproject names
     * @param releasable   the candidates indexed by name
     * @return the immutable release plan
     * @throws IOException if any POM cannot be read
     */
    private ReleasePlan buildReleasePlan(
            List<String> releaseOrder,
            Map<String, ReleaseCandidate> releasable) throws IOException {
        List<ArtifactReleaseIntent> intents = new ArrayList<>();
        List<SubprojectRoot> subprojectRoots = new ArrayList<>();
        List<Path> reactorRoots = new ArrayList<>();

        for (String name : releaseOrder) {
            ReleaseCandidate rc = releasable.get(name);
            Path rootPom = rc.dir.toPath().resolve("pom.xml")
                    .toAbsolutePath().normalize();
            PomModel pom = PomModel.parse(rootPom);

            String groupId = pom.groupId();
            String artifactId = pom.artifactId();
            String preReleaseValue = pom.version();
            if (preReleaseValue == null
                    || !preReleaseValue.endsWith("-SNAPSHOT")) {
                throw new IOException(
                        "Subproject " + name + " (" + rootPom + ") version "
                                + "must end in -SNAPSHOT; got "
                                + preReleaseValue);
            }
            String releaseValue = preReleaseValue.substring(
                    0, preReleaseValue.length() - "-SNAPSHOT".length());
            String postReleaseValue = nextSnapshotVersion(releaseValue);

            intents.add(new ArtifactReleaseIntent(
                    new GA(groupId, artifactId),
                    name,
                    rootPom,
                    preReleaseValue,
                    releaseValue,
                    postReleaseValue));
            subprojectRoots.add(new SubprojectRoot(name, rootPom));
            reactorRoots.add(rootPom);
        }

        ReactorWalker.ReactorScan scan = ReactorWalker.walkAll(reactorRoots);
        return ReleasePlanCompute.compute(scan, subprojectRoots, intents);
    }

    /**
     * Derive the post-release next-snapshot from a release value by
     * incrementing the trailing numeric segment. Matches the IKE
     * single-segment convention: {@code 110} → {@code 111-SNAPSHOT}.
     * If the release value has no trailing digits, appends
     * {@code .1-SNAPSHOT}.
     *
     * @param releaseValue release version (must not end in -SNAPSHOT)
     * @return next-snapshot version
     */
    static String nextSnapshotVersion(String releaseValue) {
        int i = releaseValue.length();
        while (i > 0 && Character.isDigit(releaseValue.charAt(i - 1))) i--;
        if (i == releaseValue.length()) {
            return releaseValue + ".1-SNAPSHOT";
        }
        String prefix = releaseValue.substring(0, i);
        long n = Long.parseLong(releaseValue.substring(i));
        return prefix + (n + 1) + "-SNAPSHOT";
    }

    /**
     * Log the release plan at INFO: one line per artifact and per
     * property. This is the pre-mutation audit view; the same data is
     * also persisted to {@code plan.yaml} at the workspace root for
     * later inspection (#212).
     */
    private void logReleasePlan(ReleasePlan plan) {
        getLog().info("Release plan:");
        for (ArtifactReleasePlan ap : plan.artifacts().values()) {
            getLog().info("  artifact " + ap.ga() + ": "
                    + ap.preReleaseValue() + " → "
                    + ap.releaseValue() + " → "
                    + ap.postReleaseValue()
                    + " (" + ap.referenceSites().size() + " reference"
                    + (ap.referenceSites().size() == 1 ? "" : "s") + ")");
        }
        for (PropertyReleasePlan pp : plan.properties()) {
            getLog().info("  property " + pp.propertyName()
                    + " in " + pp.declaringSubproject() + ": "
                    + pp.preReleaseValue() + " → " + pp.releaseValue()
                    + " (" + pp.referenceSites().size() + " reference"
                    + (pp.referenceSites().size() == 1 ? "" : "s") + ")");
        }
    }

    /**
     * Persist the release plan to {@code plan.yaml} at the workspace
     * root. Written before any mutation so the audit artifact reflects
     * the plan that will drive the cascade. In draft mode, the file is
     * still emitted — it's the point of draft mode.
     *
     * <p>Write failures are logged as warnings and do not abort the
     * release; the plan.yaml is an audit artifact, not a gate.
     *
     * @param root the workspace root directory
     * @param plan the release plan to serialize
     */
    private void writeReleasePlan(File root, ReleasePlan plan) {
        Path file = root.toPath().resolve("plan.yaml");
        String timestamp = ISO_UTC.format(Instant.now());
        String yaml = buildReleasePlanYaml(timestamp, root.toPath(), plan);
        try {
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
            getLog().info("Release plan written: " + file);
        } catch (IOException e) {
            getLog().warn("Could not write plan.yaml: " + e.getMessage());
        }
    }

    /**
     * Build the {@code plan.yaml} audit content from a release plan.
     *
     * <p>Pure function: no I/O, no git — suitable for unit testing.
     * Paths are emitted relative to {@code workspaceRoot} when possible;
     * absolute otherwise.
     *
     * @param timestamp     ISO-8601 UTC timestamp
     * @param workspaceRoot workspace root, used to relativize POM paths
     * @param plan          the release plan
     * @return YAML content
     */
    static String buildReleasePlanYaml(
            String timestamp, Path workspaceRoot, ReleasePlan plan) {
        Path rootAbs = workspaceRoot.toAbsolutePath().normalize();
        StringBuilder y = new StringBuilder();
        y.append("# Workspace release plan (pre-mutation audit)\n");
        y.append("# Generated: ").append(timestamp).append("\n");
        y.append("timestamp: ").append(timestamp).append("\n");

        y.append("artifacts:\n");
        if (plan.artifacts().isEmpty()) {
            y.append("  []\n");
        }
        for (ArtifactReleasePlan ap : plan.artifacts().values()) {
            y.append("  - groupId: ").append(ap.ga().groupId()).append("\n");
            y.append("    artifactId: ").append(ap.ga().artifactId()).append("\n");
            y.append("    producingSubproject: ")
                    .append(ap.producingSubproject()).append("\n");
            y.append("    rootPomPath: ")
                    .append(relPath(rootAbs, ap.rootPomPath())).append("\n");
            y.append("    preReleaseValue: ").append(ap.preReleaseValue()).append("\n");
            y.append("    releaseValue: ").append(ap.releaseValue()).append("\n");
            y.append("    postReleaseValue: ").append(ap.postReleaseValue()).append("\n");
            appendSites(y, "    ", rootAbs, ap.referenceSites());
        }

        y.append("properties:\n");
        if (plan.properties().isEmpty()) {
            y.append("  []\n");
        }
        for (PropertyReleasePlan pp : plan.properties()) {
            y.append("  - propertyName: ").append(pp.propertyName()).append("\n");
            y.append("    declaringPomPath: ")
                    .append(relPath(rootAbs, pp.declaringPomPath())).append("\n");
            y.append("    declaringSubproject: ")
                    .append(pp.declaringSubproject().isEmpty()
                            ? "\"\"" : pp.declaringSubproject()).append("\n");
            y.append("    preReleaseValue: ").append(pp.preReleaseValue()).append("\n");
            y.append("    releaseValue: ").append(pp.releaseValue()).append("\n");
            y.append("    postReleaseValue: ").append(pp.postReleaseValue()).append("\n");
            appendSites(y, "    ", rootAbs, pp.referenceSites());
        }

        return y.toString();
    }

    private static void appendSites(
            StringBuilder y, String indent, Path rootAbs,
            List<ReferenceSite> sites) {
        if (sites.isEmpty()) {
            y.append(indent).append("referenceSites: []\n");
            return;
        }
        y.append(indent).append("referenceSites:\n");
        for (ReferenceSite s : sites) {
            y.append(indent).append("  - pomPath: ")
                    .append(relPath(rootAbs, s.pomPath())).append("\n");
            y.append(indent).append("    kind: ").append(s.kind()).append("\n");
            y.append(indent).append("    targetGa: ").append(s.targetGa()).append("\n");
            y.append(indent).append("    textAtSite: ")
                    .append(s.textAtSite() == null
                            ? "null"
                            : "\"" + s.textAtSite().replace("\"", "\\\"") + "\"")
                    .append("\n");
        }
    }

    private static String relPath(Path rootAbs, Path p) {
        Path abs = p.toAbsolutePath().normalize();
        if (abs.startsWith(rootAbs)) {
            Path rel = rootAbs.relativize(abs);
            String s = rel.toString();
            return s.isEmpty() ? "." : s;
        }
        return abs.toString();
    }

    /**
     * Resolve the catch-up target version for a single upstream.
     *
     * <p>If the upstream released earlier in this cycle, returns the
     * <em>released</em> version (e.g., release 105 → downstream
     * references become {@code 105}). Downstream POMs must reference
     * artifacts that actually exist in the remote repository; the
     * post-release next-snapshot bump (e.g., {@code 106-SNAPSHOT})
     * sits on the upstream's main branch but is not yet deployed and
     * would produce an unresolvable reference.
     *
     * <p>Otherwise reads the upstream's current pom.xml version from
     * disk. If the upstream is neither in this cycle nor checked out,
     * returns {@code null} — there's no value to align to.
     */
    String upstreamTargetVersion(String upstreamName,
                                  Map<String, String> releasedVersions,
                                  File root) {
        if (releasedVersions.containsKey(upstreamName)) {
            return releasedVersions.get(upstreamName);
        }
        File upstreamDir = new File(root, upstreamName);
        if (!upstreamDir.isDirectory()
                || !new File(upstreamDir, "pom.xml").exists()) {
            return null;
        }
        String version = currentVersion(upstreamDir);
        return "unknown".equals(version) ? null : version;
    }

    // ── Helper: write checkpoint YAML ────────────────────────────────

    private void writeCheckpoint(File root, WorkspaceGraph graph, String name)
            throws MojoException {
        Path checkpointsDir = root.toPath().resolve("checkpoints");
        try {
            Files.createDirectories(checkpointsDir);
            Path file = checkpointsDir.resolve("checkpoint-" + name + ".yaml");

            // Gather subproject data for the pure function
            String timestamp = ISO_UTC.format(Instant.now());
            List<String[]> componentData = new ArrayList<>();
            for (String subName : graph.topologicalSort()) {
                File subDir = new File(root, subName);
                if (!subDir.isDirectory()) continue;
                componentData.add(new String[]{
                        subName, gitBranch(subDir), gitShortSha(subDir),
                        currentVersion(subDir),
                        String.valueOf(!gitStatus(subDir).isEmpty())
                });
            }

            String yaml = buildPreReleaseCheckpointYaml(name, timestamp, componentData);
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
            getLog().info("Checkpoint written: " + file);
        } catch (IOException e) {
            getLog().warn("Could not write checkpoint: " + e.getMessage());
        }
    }

    /**
     * Build pre-release checkpoint YAML content from pre-gathered
     * subproject data.
     *
     * <p>This is a pure function with no git or I/O dependencies,
     * suitable for direct unit testing.
     *
     * @param name          checkpoint name
     * @param timestamp     ISO-8601 UTC timestamp
     * @param componentData list of {@code [name, branch, sha, version, modified]}
     *                      arrays for each present subproject
     * @return YAML checkpoint content
     */
    public static String buildPreReleaseCheckpointYaml(
            String name, String timestamp, List<String[]> componentData) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Workspace checkpoint: ").append(name).append("\n");
        yaml.append("# Generated: ").append(timestamp).append("\n");
        yaml.append("checkpoint: ").append(name).append("\n");
        yaml.append("timestamp: ").append(timestamp).append("\n");
        yaml.append("subprojects:\n");

        for (String[] sub : componentData) {
            yaml.append("  ").append(sub[0]).append(":\n");
            yaml.append("    branch: ").append(sub[1]).append("\n");
            yaml.append("    sha: ").append(sub[2]).append("\n");
            yaml.append("    version: ").append(sub[3]).append("\n");
            yaml.append("    modified: ").append(sub[4]).append("\n");
        }

        return yaml.toString();
    }

    // ── Helper: find mvn or mvnw ─────────────────────────────────────

    private String findMvn(File subDir) {
        return resolveMvnCommand(subDir);
    }

    /**
     * Build the {@code ike:release-publish} command line for a
     * subproject, threading the workspace-level {@code push} and
     * {@code ignoreWarnings} flags through to the subprocess. Without
     * the {@code ignoreWarnings} pass-through, a workspace release
     * could never clear a subproject whose history predates the
     * issue-trailer convention.
     *
     * @param mvn the resolved Maven executable
     * @param extra any goal-specific arguments to append
     * @return the full command, ready for {@link ReleaseSupport#exec}
     */
    private String[] releaseCommand(String mvn, String... extra) {
        List<String> cmd = new ArrayList<>();
        cmd.add(mvn);
        cmd.add("ike:release-publish");
        cmd.add("-DpushRelease=" + push);
        if (ignoreWarnings) {
            cmd.add("-Dike.release.ignoreWarnings=true");
        }
        for (String arg : extra) {
            cmd.add(arg);
        }
        cmd.add("-B");
        return cmd.toArray(new String[0]);
    }

    /**
     * Resolve the Maven executable for a subproject directory.
     *
     * <p>Checks for {@code mvnw} (executable) and {@code mvnw.cmd} in
     * the given directory. Falls back to {@code "mvn"} from the system
     * PATH if no wrapper is found.
     *
     * @param subDir the subproject directory to check
     * @return absolute path to mvnw/mvnw.cmd, or {@code "mvn"}
     */
    public static String resolveMvnCommand(File subDir) {
        File mvnw = new File(subDir, "mvnw");
        if (mvnw.exists() && mvnw.canExecute()) {
            return mvnw.getAbsolutePath();
        }
        File mvnwCmd = new File(subDir, "mvnw.cmd");
        if (mvnwCmd.exists()) {
            return mvnwCmd.getAbsolutePath();
        }
        return "mvn";
    }

    // ── Record for candidate tracking ────────────────────────────────

    private record ReleaseCandidate(
            String name,
            Subproject subproject,
            File dir,
            String lastTag,
            String reason) {}
}
