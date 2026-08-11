package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.PomModel;
import network.ike.plugin.ws.SubprojectResolver;
import network.ike.plugin.ws.WsGoal;
import network.ike.workspace.Dependency;
import network.ike.workspace.ManifestException;
import network.ike.workspace.PublishedArtifactSet;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reconciler that aligns inter-subproject dependency, plugin, and
 * parent versions across every cloned subproject's POMs to the
 * authoritative versions declared by the workspace.
 *
 * <p>Subsumes the {@code ws:align-{draft,publish}} business logic
 * (IKE-Network/ike-issues#393). Unlike most other reconciler
 * migrations, the standalone {@code ws:align-draft} and
 * {@code ws:align-publish} Maven goals are <em>retained</em> as thin
 * wrappers over this reconciler: alignment drift arises from many
 * distinct moments (post-release cascade, manual POM edits, feature
 * work, scaffold drift), so the reconciler is also invoked from
 * {@code ws:scaffold-publish} as part of the convergence pass. The
 * standalone goals remain as the explicit "I detected misalignment,
 * run only that" entry point.
 *
 * <h2>What this reconciler aligns</h2>
 * <ol>
 *   <li><b>Dependency versions</b> — for each POM, when a
 *       {@code <dependency>} references another workspace subproject's
 *       artifact ({@code groupId:artifactId} matches the artifact set
 *       published by that subproject), the declared version is updated
 *       to match the subproject's current POM version. Property-based
 *       versions ({@code ${ike-bom.version}}) update the property;
 *       direct {@code <version>} tags rewrite in place.</li>
 *   <li><b>Plugin versions</b> — same matching for {@code <plugin>}
 *       declarations. Property-based plugin versions are deferred to
 *       the dependency/property alignment pass above.</li>
 *   <li><b>Parent versions</b> — when a subproject declares another
 *       subproject as its parent (via the manifest {@code parent:}
 *       field), and the parent subproject's version differs from the
 *       child's {@code <parent><version>}, the child POM (and any
 *       submodule POMs referencing the same parent GAV) is updated.
 *       This is distinct from {@link ParentVersionReconciler}, which
 *       cascades the <em>workspace root POM's</em> parent across
 *       subprojects.</li>
 * </ol>
 *
 * <p>Reads use Maven 4's {@link PomModel} (Model API, no regex).
 * Writes go through {@code PomModel}'s OpenRewrite-LST-backed
 * updaters — see {@code feedback_no_sed_on_poms}.
 *
 * <p>Opt out with {@code -DupdateAlignment=false}.
 */
public class AlignmentReconciler implements Reconciler {

    @Override
    public String dimension() {
        return "Inter-subproject version alignment";
    }

    @Override
    public String optOutFlag() {
        return "updateAlignment";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        Plan plan = computePlan(ctx);
        if (plan.totalChanges() == 0) {
            return DriftReport.noDrift(dimension());
        }
        List<String> detail = new ArrayList<>();
        for (AlignChange c : plan.changes) {
            detail.add(c.subproject + " (" + c.pomRelPath + "): "
                    + c.artifact + " " + c.fromVersion + " → " + c.toVersion
                    + (c.pinnedTag != null
                            ? " (pinned " + c.pinnedTag + ")" : ""));
        }
        String summary = plan.totalChanges()
                + " version(s) drift across "
                + plan.changedSubprojectCount() + " subproject(s)";
        String action = "rewrite POM versions to match workspace truth"
                + " on scaffold-publish";
        String optOut = "mvn " + WsGoal.SCAFFOLD_PUBLISH.qualified()
                + " -D" + optOutFlag() + "=false";
        return new DriftReport(dimension(), true, summary, detail,
                action, optOut);
    }

    @Override
    public void apply(WorkspaceContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension() + ": skipped (opted out via -D"
                    + optOutFlag() + "=false)");
            return;
        }
        Plan plan = computePlan(ctx);
        if (plan.totalChanges() == 0) {
            return;
        }
        applyPlan(ctx, plan);
        ctx.log().info("  " + dimension() + ": updated "
                + plan.totalChanges() + " version(s) across "
                + plan.changedSubprojectCount() + " subproject(s)");
    }

    /**
     * The POMs this reconciler would rewrite on {@link #apply}, grouped by
     * owning subproject and listed by path relative to that subproject's root
     * (IKE-Network/ike-issues#780). Call this BEFORE {@code apply} — afterwards
     * the drift is resolved and the plan is empty. Lets a caller commit exactly
     * the aligned POMs in isolation. Read-only (recomputes the plan, no writes).
     *
     * @param ctx the workspace context
     * @return subproject name → its changed POM relative paths (deduplicated),
     *         empty when there is no drift
     */
    public Map<String, List<String>> changedPomsBySubproject(
            WorkspaceContext ctx) {
        Map<String, List<String>> byRepo = new LinkedHashMap<>();
        for (AlignChange c : computePlan(ctx).changes) {
            List<String> poms = byRepo.computeIfAbsent(
                    c.subproject, k -> new ArrayList<>());
            if (!poms.contains(c.pomRelPath)) {
                poms.add(c.pomRelPath);
            }
        }
        return byRepo;
    }

    // ── Plan computation (read-only, shared by detect and apply) ────

    /**
     * A single intra-workspace version change applied to one POM.
     *
     * @param subproject the owning subproject name
     * @param pomRelPath path of the POM relative to the subproject root
     * @param artifact   coordinate label, e.g.
     *                   {@code groupId:artifactId},
     *                   {@code property:ike-bom.version},
     *                   {@code plugin:groupId:artifactId},
     *                   {@code parent:artifactId}
     * @param fromVersion the version currently declared
     * @param toVersion   the version this reconciler will set
     * @param pinnedTag   the target member's release tag when the target
     *                    version is a tag-aligned pin (#972); null when
     *                    the target is snapshot-aligned
     */
    private record AlignChange(String subproject, String pomRelPath,
                                String artifact, String fromVersion,
                                String toVersion, String pinnedTag) {}

    /**
     * The full alignment plan for one reconciliation pass.
     * Plans are computed by {@link #computePlan} (no I/O writes) and
     * consumed by either {@link #detect} or {@link #applyPlan}.
     */
    private record Plan(List<AlignChange> changes) {
        int totalChanges() {
            return changes.size();
        }
        int changedSubprojectCount() {
            return (int) changes.stream()
                    .map(c -> c.subproject)
                    .distinct()
                    .count();
        }
    }

    /**
     * Associates a subproject name with the version consumers must
     * reference: the on-disk POM version for snapshot-aligned members,
     * or the manifest-pinned released version for tag-aligned members
     * (IKE-Network/ike-issues#972 — state-aware alignment).
     *
     * @param name      the subproject name
     * @param version   the alignment target version
     * @param pinnedTag the release tag when the target is a tag-aligned
     *                  pin; null for snapshot-aligned members
     */
    private record ComponentVersion(String name, String version,
                                    String pinnedTag) {}

    /**
     * Enforce the tag-aligned manifest invariant: a member in
     * {@code state: tag-aligned} must carry both the pinned
     * {@code version:} and the {@code tag:} it is pinned at
     * ({@link Subproject} schema, ike-issues#233). A violation means the
     * manifest is corrupt and no alignment target is trustworthy, so
     * this fails the whole pass rather than aligning to a wrong version.
     *
     * @param name       the subproject name for the error message
     * @param subproject the manifest entry to validate
     * @throws ManifestException when version or tag is null or blank
     */
    private static void requirePinComplete(String name, Subproject subproject) {
        if (subproject.version() == null || subproject.version().isBlank()
                || subproject.tag() == null || subproject.tag().isBlank()) {
            throw new ManifestException("Subproject '" + name
                    + "' is tag-aligned but missing its version: and/or tag:"
                    + " pin — re-run mvn " + WsGoal.RECORD_RELEASE_PUBLISH.qualified()
                    + " -Dmember=" + name
                    + " or restore the fields in workspace.yaml");
        }
    }

    /**
     * Compute the alignment plan without mutating any POMs. Used by
     * {@link #detect} and {@link #apply}.
     */
    private Plan computePlan(WorkspaceContext ctx) {
        WorkspaceGraph graph = ctx.graph();
        File root = ctx.workspaceRoot();
        Log log = ctx.log();

        Map<String, ComponentVersion> artifactIndex =
                buildArtifactIndex(graph, root, log);

        List<AlignChange> changes = new ArrayList<>();

        // Dependency / plugin / property alignment per subproject.
        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File subprojectDir = new File(root, name);
            if (!new File(subprojectDir, "pom.xml").exists()) {
                continue;
            }

            List<File> pomFiles;
            try {
                pomFiles = ReleaseSupport.findPomFiles(subprojectDir);
            } catch (MojoException e) {
                log.warn("  " + name + ": could not scan POM files — "
                        + e.getMessage());
                continue;
            }

            // depends-on entries can declare a version-property hint
            // that should track the target subproject's version.
            Map<String, String> versionPropertyMap = new LinkedHashMap<>();
            for (Dependency dep : subproject.dependsOn()) {
                if (dep.versionProperty() != null
                        && !dep.versionProperty().isEmpty()) {
                    Subproject target = graph.manifest().subprojects()
                            .get(dep.subproject());
                    if (target != null && target.groupId() != null
                            && !target.groupId().isEmpty()) {
                        versionPropertyMap.put(
                                dep.subproject(), dep.versionProperty());
                    }
                }
            }

            for (File pomFile : pomFiles) {
                collectDependencyChanges(name, pomFile, artifactIndex,
                        versionPropertyMap, subprojectDir, root, changes,
                        log);
                collectPluginChanges(name, pomFile, artifactIndex,
                        subprojectDir, changes, log);
            }
        }

        // Parent alignment: a subproject whose manifest declares
        // parent: <other-subproject> tracks that subproject's version —
        // but ONLY when the POM's actual <parent> coordinate is in fact
        // produced by that subproject. A stale or mis-derived parent:
        // edge (e.g. an external parent recorded as a same-groupId
        // sibling, ike-issues#565) must never drive a version rewrite:
        // sourcing the version by manifest name alone, without checking
        // the POM's real <parent> groupId:artifactId, would rewrite an
        // unrelated parent to the wrong version and break the build
        // (#566). Validate every edge through the shared resolver and
        // skip + warn on any mismatch.
        SubprojectResolver parentResolver = null;
        try {
            parentResolver = SubprojectResolver.scan(
                    root.toPath(), graph.manifest());
        } catch (IOException e) {
            log.warn("  Parent alignment skipped: could not scan published "
                    + "artifacts — " + e.getMessage());
        }
        if (parentResolver != null) {
            collectParentChanges(graph, root, parentResolver, changes, log);
        }

        return new Plan(changes);
    }

    /**
     * Collect parent-version alignment changes for subprojects whose
     * manifest declares {@code parent: <other-subproject>}.
     *
     * <p>The manifest {@code parent:} edge is honoured only when the
     * named subproject actually publishes the POM's real {@code <parent>}
     * {@code groupId:artifactId} (full-GA match via {@code resolver}). On
     * any mismatch — an external parent, or a different subproject that
     * merely shares a groupId — the edge is stale or mis-derived
     * (ike-issues#565); the change is skipped with a warning rather than
     * rewriting an unrelated parent to the wrong version (#566).
     *
     * @param graph    workspace graph
     * @param root     workspace root directory
     * @param resolver full-GA coordinate → subproject resolver
     * @param changes  accumulator for discovered alignment changes
     * @param log      reconciler log
     */
    private static void collectParentChanges(WorkspaceGraph graph, File root,
                                             SubprojectResolver resolver,
                                             List<AlignChange> changes, Log log) {
        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            String parentSubprojectName = subproject.parent();
            if (parentSubprojectName == null) continue;

            Subproject parentSubproject = graph.manifest().subprojects()
                    .get(parentSubprojectName);
            if (parentSubproject == null
                    || parentSubproject.version() == null) {
                continue;
            }

            File subprojectDir = new File(root, name);
            Path pomPath = subprojectDir.toPath().resolve("pom.xml");
            if (!Files.exists(pomPath)) continue;

            try {
                PomModel pom = PomModel.parse(pomPath);
                Parent parentInfo = pom.parent();
                if (parentInfo == null) continue;

                // Full-GA guard (#566): the manifest parent: edge must
                // name the subproject that actually publishes the POM's
                // real <parent>. Otherwise the edge is stale/mis-derived
                // (external parent, or a different subproject) — skip the
                // rewrite rather than corrupt the <parent> version.
                Optional<String> producer = resolver.subprojectForCoordinate(
                        parentInfo.getGroupId(), parentInfo.getArtifactId());
                if (producer.isEmpty()
                        || !producer.get().equals(parentSubprojectName)) {
                    log.warn("  " + name + ": manifest parent: '"
                            + parentSubprojectName + "' does not match the POM's"
                            + " <parent> " + parentInfo.getGroupId() + ":"
                            + parentInfo.getArtifactId()
                            + producer.map(p -> " (produced by " + p + ")")
                                    .orElse(" (external)")
                            + " — skipping parent alignment (ike-issues#566)");
                    continue;
                }

                // State-aware (#972): for a tag-aligned parent member the
                // manifest version field IS the pin (written together with
                // state/tag by ws:record-release), so the existing
                // manifest-sourced expectedVersion is already correct —
                // validate the pin and annotate the change.
                String parentPinnedTag = null;
                if (parentSubproject.isTagAligned()) {
                    requirePinComplete(parentSubprojectName, parentSubproject);
                    parentPinnedTag = parentSubproject.tag();
                }
                String expectedVersion = parentSubproject.version();
                String currentVersion = parentInfo.getVersion();
                if (currentVersion == null
                        || expectedVersion.equals(currentVersion)) {
                    continue;
                }
                changes.add(new AlignChange(
                        name, "pom.xml",
                        "parent:" + parentInfo.getArtifactId(),
                        currentVersion, expectedVersion, parentPinnedTag));
            } catch (IOException e) {
                log.warn("  " + name + ": could not read parent version — "
                        + e.getMessage());
            }
        }
    }

    // ── Plan application (mutates POMs) ─────────────────────────────

    private void applyPlan(WorkspaceContext ctx, Plan plan) {
        File root = ctx.workspaceRoot();
        Log log = ctx.log();

        // Group changes by (subproject, pomRelPath) so we re-read each
        // file once, apply all of its changes, then write back.
        Map<String, List<AlignChange>> byPom = new LinkedHashMap<>();
        for (AlignChange c : plan.changes) {
            String key = c.subproject + "::" + c.pomRelPath;
            byPom.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        for (Map.Entry<String, List<AlignChange>> e : byPom.entrySet()) {
            List<AlignChange> group = e.getValue();
            AlignChange first = group.get(0);
            File subprojectDir = new File(root, first.subproject);
            Path pomPath = subprojectDir.toPath().resolve(first.pomRelPath);

            String content;
            try {
                content = Files.readString(pomPath, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                log.warn("  " + first.subproject + ": could not read "
                        + first.pomRelPath + " — " + ex.getMessage());
                continue;
            }

            for (AlignChange c : group) {
                content = applyOne(content, c, subprojectDir, log);
                log.info("  " + c.subproject + " (" + c.pomRelPath
                        + "): " + c.artifact + " " + c.fromVersion
                        + " → " + c.toVersion);
            }

            try {
                Files.writeString(pomPath, content, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new MojoException(
                        "Failed to write " + pomPath + ": "
                                + ex.getMessage(), ex);
            }
        }
    }

    /**
     * Apply a single {@link AlignChange} to in-memory POM text, and
     * (for parent updates) cascade the same edit into any submodule
     * POMs that share the parent GAV. Returns the updated text for
     * the primary POM; the submodule cascade writes directly.
     */
    private String applyOne(String content, AlignChange c,
                             File subprojectDir, Log log) {
        String artifact = c.artifact;
        if (artifact.startsWith("property:")) {
            String propName = artifact.substring("property:".length());
            return PomModel.updateProperty(content, propName, c.toVersion);
        }
        if (artifact.startsWith("plugin:")) {
            String coord = artifact.substring("plugin:".length());
            int colon = coord.indexOf(':');
            if (colon < 0) return content;
            String gid = coord.substring(0, colon);
            String aid = coord.substring(colon + 1);
            return PomModel.updatePluginVersion(content, gid, aid,
                    c.toVersion);
        }
        if (artifact.startsWith("parent:")) {
            // The parent change was recorded with artifact=parent:<aid>;
            // recover parent GA from the parsed POM.
            Path pomPath = subprojectDir.toPath().resolve(c.pomRelPath);
            try {
                PomModel pom = PomModel.parse(pomPath);
                Parent p = pom.parent();
                if (p == null) return content;
                String updated = PomModel.updateParentVersion(content,
                        p.getGroupId(), p.getArtifactId(), c.toVersion);
                cascadeParentIntoSubmodules(subprojectDir, pomPath,
                        p.getGroupId(), p.getArtifactId(), c.toVersion, log);
                return updated;
            } catch (IOException e) {
                log.warn("  " + c.subproject + ": could not re-parse parent — "
                        + e.getMessage());
                return content;
            }
        }
        // Default: artifact is groupId:artifactId for a dependency.
        int colon = artifact.indexOf(':');
        if (colon < 0) return content;
        String gid = artifact.substring(0, colon);
        String aid = artifact.substring(colon + 1);
        return PomModel.updateDependencyVersion(content, gid, aid,
                c.toVersion);
    }

    /**
     * Cascade a parent version edit into every submodule POM under a
     * subproject that references the same parent {@code groupId:artifactId}.
     */
    private static void cascadeParentIntoSubmodules(File subprojectDir,
                                                     Path primaryPom,
                                                     String parentGid,
                                                     String parentAid,
                                                     String newVersion,
                                                     Log log) {
        List<File> subPoms;
        try {
            subPoms = ReleaseSupport.findPomFiles(subprojectDir);
        } catch (MojoException e) {
            // Non-fatal: the primary update still landed.
            return;
        }
        for (File subPom : subPoms) {
            if (subPom.toPath().equals(primaryPom)) continue;
            try {
                String subContent = Files.readString(subPom.toPath(),
                        StandardCharsets.UTF_8);
                String updated = PomModel.updateParentVersion(
                        subContent, parentGid, parentAid, newVersion);
                if (!updated.equals(subContent)) {
                    Files.writeString(subPom.toPath(), updated,
                            StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                log.warn("  could not cascade parent into "
                        + subPom + " — " + e.getMessage());
            }
        }
    }

    // ── POM scanning helpers ────────────────────────────────────────

    /**
     * Build the {@code groupId:artifactId} → (subproject, version)
     * lookup index covering every cloned subproject's published
     * artifacts.
     */
    private static Map<String, ComponentVersion> buildArtifactIndex(
            WorkspaceGraph graph, File root, Log log) {
        Map<String, ComponentVersion> index = new LinkedHashMap<>();
        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File subprojectDir = new File(root, name);
            if (!new File(subprojectDir, "pom.xml").exists()) {
                continue;
            }
            // State-aware target resolution (#972): a tag-aligned member's
            // consumers reference its manifest-pinned released version, not
            // the member's on-disk POM (which has post-bumped to the next
            // SNAPSHOT). Snapshot-aligned members keep POM truth.
            String targetVersion;
            String pinnedTag;
            if (subproject.isTagAligned()) {
                requirePinComplete(name, subproject);
                targetVersion = subproject.version();
                pinnedTag = subproject.tag();
            } else {
                try {
                    targetVersion = ReleaseSupport.readPomVersion(
                            new File(subprojectDir, "pom.xml"));
                } catch (MojoException e) {
                    log.warn("  " + name + ": could not read POM version — "
                            + e.getMessage());
                    continue;
                }
                pinnedTag = null;
            }
            Set<PublishedArtifactSet.Artifact> published;
            try {
                published = PublishedArtifactSet.scan(subprojectDir.toPath());
            } catch (IOException e) {
                log.warn("  " + name + ": could not scan published artifacts — "
                        + e.getMessage());
                continue;
            }
            ComponentVersion cv = new ComponentVersion(
                    name, targetVersion, pinnedTag);
            for (PublishedArtifactSet.Artifact artifact : published) {
                String key = artifact.groupId() + ":" + artifact.artifactId();
                index.put(key, cv);
            }
        }
        return index;
    }

    /**
     * Collect dependency and property-driven version changes from one
     * POM file. Mirrors {@code alignPomDependencies} from the retired
     * {@code WsAlignDraftMojo}.
     */
    private static void collectDependencyChanges(String ownerName,
                                                  File pomFile,
                                                  Map<String, ComponentVersion> artifactIndex,
                                                  Map<String, String> versionPropertyMap,
                                                  File subprojectDir,
                                                  File workspaceRoot,
                                                  List<AlignChange> changes,
                                                  Log log) {
        PomModel pom;
        try {
            pom = PomModel.parse(pomFile.toPath());
        } catch (IOException e) {
            log.debug("  " + ownerName + ": skipping " + pomFile.getName()
                    + " (empty or unparseable)");
            return;
        }

        for (org.apache.maven.api.model.Dependency dep : pom.allDependencies()) {
            String depGroupId = dep.getGroupId();
            String depArtifactId = dep.getArtifactId();
            String currentVersion = dep.getVersion();
            if (depGroupId == null || depArtifactId == null
                    || currentVersion == null) {
                continue;
            }

            String key = depGroupId + ":" + depArtifactId;
            ComponentVersion target = artifactIndex.get(key);
            if (target == null || target.name.equals(ownerName)) {
                continue;
            }

            String relPath = subprojectDir.toPath().relativize(
                    pomFile.toPath()).toString();

            if (currentVersion.startsWith("${")
                    && currentVersion.endsWith("}")) {
                String propName = currentVersion.substring(2,
                        currentVersion.length() - 1);
                String propValue = pom.properties().get(propName);
                if (propValue != null && !propValue.equals(target.version)) {
                    changes.add(new AlignChange(ownerName, relPath,
                            "property:" + propName,
                            propValue, target.version, target.pinnedTag));
                }
            } else if (!currentVersion.equals(target.version)) {
                changes.add(new AlignChange(ownerName, relPath, key,
                        currentVersion, target.version, target.pinnedTag));
            }
        }

        // depends-on declared version-property hints.
        for (Map.Entry<String, String> vpEntry
                : versionPropertyMap.entrySet()) {
            String targetComponent = vpEntry.getKey();
            String versionProperty = vpEntry.getValue();
            ComponentVersion cv = findComponentVersion(
                    targetComponent, artifactIndex, workspaceRoot);
            if (cv == null) continue;
            String currentValue = pom.properties().get(versionProperty);
            if (currentValue != null && !currentValue.equals(cv.version)) {
                String relPath = subprojectDir.toPath().relativize(
                        pomFile.toPath()).toString();
                changes.add(new AlignChange(ownerName, relPath,
                        "property:" + versionProperty,
                        currentValue, cv.version, cv.pinnedTag));
            }
        }
    }

    /**
     * Collect plugin literal version changes from one POM file.
     * Mirrors {@code alignPomPlugins} from the retired
     * {@code WsAlignDraftMojo}. Property-based plugin versions
     * ({@code ${ike-tooling.version}}) are skipped here — they flow
     * through dependency/property alignment instead.
     */
    private static void collectPluginChanges(String ownerName,
                                              File pomFile,
                                              Map<String, ComponentVersion> artifactIndex,
                                              File subprojectDir,
                                              List<AlignChange> changes,
                                              Log log) {
        PomModel pom;
        try {
            pom = PomModel.parse(pomFile.toPath());
        } catch (IOException e) {
            return;
        }
        for (Plugin plugin : pom.allPlugins()) {
            String pluginGroupId = plugin.getGroupId();
            String pluginArtifactId = plugin.getArtifactId();
            String currentVersion = plugin.getVersion();
            if (pluginGroupId == null || pluginArtifactId == null
                    || currentVersion == null) {
                continue;
            }
            if (currentVersion.startsWith("${")) {
                continue;
            }
            String key = pluginGroupId + ":" + pluginArtifactId;
            ComponentVersion target = artifactIndex.get(key);
            if (target == null || target.name().equals(ownerName)) {
                continue;
            }
            if (!currentVersion.equals(target.version())) {
                String relPath = subprojectDir.toPath().relativize(
                        pomFile.toPath()).toString();
                changes.add(new AlignChange(ownerName, relPath,
                        "plugin:" + key,
                        currentVersion, target.version(), target.pinnedTag()));
            }
        }
    }

    /**
     * Find a subproject's version by scanning its published artifacts
     * and looking them up in the artifact index. Matches by subproject
     * name (not groupId) so it handles groupId collisions.
     */
    private static ComponentVersion findComponentVersion(
            String subprojectName,
            Map<String, ComponentVersion> artifactIndex,
            File root) {
        File subprojectDir = new File(root, subprojectName);
        if (!new File(subprojectDir, "pom.xml").exists()) {
            return null;
        }
        try {
            Set<PublishedArtifactSet.Artifact> published =
                    PublishedArtifactSet.scan(subprojectDir.toPath());
            for (PublishedArtifactSet.Artifact artifact : published) {
                String key = artifact.groupId() + ":" + artifact.artifactId();
                ComponentVersion cv = artifactIndex.get(key);
                if (cv != null && cv.name.equals(subprojectName)) {
                    return cv;
                }
            }
        } catch (IOException e) {
            // Fall through
        }
        return null;
    }
}
