package network.ike.plugin.ws;

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
@Mojo(name = "release-draft", projectRequired = false)
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
    public void execute() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // ── 1. Determine candidate components ─────────────────────────
        List<String> candidates = graph.topologicalSort();

        boolean draft = !publish;

        // ── Preflight: all working trees must be clean (#132, #154) ───
        // (Javadoc cleanliness is checked per-module by ike:release
        //  preflight — see ReleaseDraftMojo — so every entry point
        //  enforces it, not only workspace-level releases.)
        PreflightResult releasePreflight = Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN,
                        PreflightCondition.NO_SNAPSHOT_PROPERTIES),
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

            int commitsSinceTag = commitsSinceTag(subDir, latestTag);
            if (commitsSinceTag > 0) {
                releasable.put(name, new ReleaseCandidate(name, sub, subDir,
                        latestTag, commitsSinceTag + " commits since " + latestTag));
                sourceChanged.add(name);
                continue;
            }

            getLog().debug("Skipping " + name + " — clean (at " + latestTag + ")");
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
            return;
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
            return;
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
                        mvn, "ike:release-publish",
                        "-DpushRelease=" + push,
                        "-B");

                released.add(rc.name);
                releasedVersions.put(rc.name, releaseVersion);
                getLog().info(Ansi.green("  ✓ ") + "Released " + rc.name + " " + releaseVersion);
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
        writeReport(publish ? WsGoal.RELEASE_PUBLISH : WsGoal.RELEASE_DRAFT, buildReleaseMarkdownReport(releasedVersions));
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

    private String buildReleaseMarkdownReport(
            Map<String, String> releasedVersions) {
        var sb = new StringBuilder();
        sb.append(releasedVersions.size())
                .append(" subproject(s) released.\n\n");
        sb.append("| Subproject | Version | Status |\n");
        sb.append("|-----------|---------|--------|\n");
        for (var entry : releasedVersions.entrySet()) {
            sb.append("| ").append(entry.getKey())
                    .append(" | ").append(entry.getValue())
                    .append(" | ✓ |\n");
        }
        return sb.toString();
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
     * Extract the first {@code <version>} value from POM XML content.
     *
     * <p>This is a simple regex extraction — finds the first
     * {@code <version>...</version>} in the content. Good enough for
     * workspace POMs where the project version appears early.
     *
     * @param pomContent raw POM XML as a string
     * @return the version string, or {@code "unknown"} if not found
     */
    public static String extractVersionFromPom(String pomContent) {
        if (pomContent == null || pomContent.isBlank()) return "unknown";
        var matcher = java.util.regex.Pattern.compile(
                "<version>([^<]+)</version>").matcher(pomContent);
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
                            before, ap.ga().artifactId(), ap.releaseValue());
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
            Set<String> inPlan = plan.artifacts().values().stream()
                    .map(ArtifactReleasePlan::producingSubproject)
                    .collect(Collectors.toSet());
            Path rootPom = rcDir.resolve("pom.xml");
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
                String after = PomRewriter.updateParentVersion(
                        before, dep.subproject(), target);
                if (!after.equals(before)) {
                    pomContent.put(rootPom, after);
                    getLog().info("    " + rc.name + ": parent "
                            + dep.subproject() + " → " + target
                            + " (out-of-cascade catch-up)");
                    before = after;
                }
                if (dep.versionProperty() != null) {
                    after = PomRewriter.updateProperty(
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
