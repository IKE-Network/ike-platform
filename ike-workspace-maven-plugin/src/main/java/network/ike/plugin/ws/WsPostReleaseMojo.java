package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;

import network.ike.workspace.Subproject;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.WorkingSet;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Post-release version bump across workspace subprojects.
 *
 * <p>After a release, this goal bumps every checked-out subproject's
 * POM version to the specified {@code nextVersion}, commits the
 * change, pushes if a remote exists, then updates workspace.yaml
 * to reflect the new development versions.
 *
 * <p>Components are processed in topological order so that upstream
 * dependencies are bumped before downstream consumers.
 *
 * <pre>{@code
 * mvn ike:post-release -DnextVersion=4-SNAPSHOT
 * }</pre>
 */
@Mojo(name = "post-release", projectRequired = false, aggregator = true)
public class WsPostReleaseMojo extends AbstractWorkspaceMojo {

    /**
     * The next development version to set across all subprojects,
     * e.g., {@code "4-SNAPSHOT"}.
     */
    @Parameter(property = "nextVersion")
    String nextVersion;

    /** Creates this goal instance. */
    public WsPostReleaseMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        nextVersion = requireParam(nextVersion, "nextVersion",
                "Next development version (e.g., 4-SNAPSHOT)");
        validateMavenVersion(nextVersion);

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        // VCS bridge: catch-up before modifying
        VcsOperations.catchUp(root, getLog());

        List<String> sorted = graph.topologicalSort(
                new LinkedHashSet<>(graph.manifest().subprojects().keySet()));

        getLog().info("");
        getLog().info("IKE Workspace \u2014 Post-Release");
        getLog().info("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        getLog().info("  Next version: " + nextVersion);
        getLog().info("  Components:   " + sorted.size());
        getLog().info("");

        Map<String, String> versionUpdates = new LinkedHashMap<>();
        // Per-subproject Effect for the working-set report (#763/#767): keyed
        // by subproject name, valued by what post-release did to that member
        // (bumped X \u2192 Y, skipped (already at Y), skipped (not cloned), \u2026). The
        // aggregator's effect is derived separately, after the workspace.yaml
        // write, so the report can carry one row per working-set member.
        Map<String, String> effects = new LinkedHashMap<>();
        int bumped = 0;
        int skipped = 0;

        for (String name : sorted) {
            Subproject subproject = graph.manifest().subprojects().get(name);
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");
            File pomFile = new File(dir, "pom.xml");

            if (!gitDir.exists()) {
                getLog().info("  \u26A0 " + name + " \u2014 not cloned, skipping");
                effects.put(name, "skipped (not cloned)");
                skipped++;
                continue;
            }

            if (!pomFile.exists()) {
                getLog().info("  \u26A0 " + name + " \u2014 no pom.xml, skipping");
                effects.put(name, "skipped (no pom.xml)");
                skipped++;
                continue;
            }

            // Read current version from root POM
            String currentVersion;
            try {
                currentVersion = ReleaseSupport.readPomVersion(pomFile);
            } catch (MojoException e) {
                getLog().warn("  \u26A0 " + name + " \u2014 could not read version: "
                        + e.getMessage());
                effects.put(name, "skipped (could not read version)");
                skipped++;
                continue;
            }

            // Idempotency guard (#294): re-running with the same
            // -DnextVersion on a subproject already at that version is a
            // no-op \u2014 log and skip rather than running the rewrite +
            // commit machinery only to discover there's nothing to do.
            if (nextVersion.equals(currentVersion)) {
                getLog().info("  \u2713 " + name + " \u2014 already at "
                        + nextVersion);
                effects.put(name, "skipped (already at " + nextVersion + ")");
                skipped++;
                continue;
            }

            getLog().info("  \u2192 " + name + " \u2014 " + currentVersion
                    + " \u2192 " + nextVersion);

            // Set version to nextVersion in POM
            ReleaseSupport.setPomVersion(pomFile, currentVersion, nextVersion);

            // Also update submodule POMs that reference the old version
            try {
                List<File> allPoms = ReleaseSupport.findPomFiles(dir);
                for (File subPom : allPoms) {
                    if (subPom.equals(pomFile)) continue;
                    try {
                        String content = java.nio.file.Files.readString(
                                subPom.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                        if (content.contains("<version>" + currentVersion + "</version>")) {
                            String updated = content.replace(
                                    "<version>" + currentVersion + "</version>",
                                    "<version>" + nextVersion + "</version>");
                            java.nio.file.Files.writeString(
                                    subPom.toPath(), updated,
                                    java.nio.charset.StandardCharsets.UTF_8);
                            String rel = dir.toPath().relativize(subPom.toPath()).toString();
                            getLog().info("    updated: " + rel);
                        }
                    } catch (java.io.IOException e) {
                        getLog().warn("    Could not update " + subPom + ": "
                                + e.getMessage());
                    }
                }
            } catch (MojoException e) {
                getLog().warn("    Could not scan submodule POMs: " + e.getMessage());
            }

            // Commit: git add pom.xml && git commit
            ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
            // Stage any submodule POMs that were updated
            try {
                List<File> allPoms = ReleaseSupport.findPomFiles(dir);
                for (File subPom : allPoms) {
                    if (!subPom.equals(pomFile)) {
                        String rel = dir.toPath().relativize(subPom.toPath()).toString();
                        ReleaseSupport.exec(dir, getLog(), "git", "add", rel);
                    }
                }
            } catch (MojoException e) {
                getLog().debug("Could not stage submodule POMs: " + e.getMessage());
            }
            VcsOperations.commitStaged(dir, getLog(),
                    "post-release: bump to " + nextVersion);

            // Push if remote exists (safe — ignores failure)
            VcsOperations.pushIfRemoteExists(dir, getLog(), "origin",
                    gitBranch(dir));

            versionUpdates.put(name, nextVersion);
            effects.put(name, "bumped " + currentVersion + " → " + nextVersion);
            bumped++;
        }

        // Update workspace.yaml versions. The aggregator's effect for the
        // working-set report is whatever this block does to the workspace
        // root — committing the rewritten workspace.yaml. The aggregator's
        // own POM version is intentionally *not* bumped here (that is the
        // aggregator-bump lifecycle, child D / #768); the report states so.
        String aggregatorEffect = "no-op (workspace.yaml unchanged)";
        if (!versionUpdates.isEmpty()) {
            try {
                ManifestWriter.updateMavenVersions(manifestPath, versionUpdates);
                getLog().info("");
                getLog().info("  Updated workspace.yaml versions for "
                        + versionUpdates.size() + " components");
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to update workspace.yaml: " + e.getMessage(), e);
            }

            // Commit workspace.yaml on aggregator
            File wsRoot = manifestPath.getParent().toFile();
            File wsGit = new File(wsRoot, ".git");
            if (wsGit.exists()) {
                ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                VcsOperations.commitStaged(wsRoot, getLog(),
                        "post-release: bump workspace versions to " + nextVersion);
                VcsOperations.pushIfRemoteExists(wsRoot, getLog(), "origin",
                        gitBranch(wsRoot));
                aggregatorEffect = "workspace.yaml bumped for "
                        + versionUpdates.size() + " component"
                        + (versionUpdates.size() == 1 ? "" : "s")
                        + " + committed";
            } else {
                aggregatorEffect = "workspace.yaml bumped for "
                        + versionUpdates.size() + " component"
                        + (versionUpdates.size() == 1 ? "" : "s")
                        + " (not committed — no .git)";
            }
        }

        getLog().info("");
        getLog().info("  Bumped: " + bumped + " | Skipped: " + skipped);
        getLog().info("");

        return new WorkspaceReportSpec(WsGoal.POST_RELEASE,
                buildReport(bumped, skipped, effects, aggregatorEffect));
    }

    /**
     * Build the post-release markdown report — the shared working-set table
     * (#766/#767), one row per {@link WorkingSet.Member} including the
     * aggregator. The aggregator row is now present (the #763 fix): its
     * version, branch, and short SHA are gathered the same way as a
     * subproject's, so a workspace root left stale is visible rather than
     * silently absent from a subprojects-only table. The {@code Effect}
     * column states what post-release applied to each member.
     *
     * @param bumped           count of subprojects whose version was bumped
     * @param skipped          count of subprojects skipped (no-op / not cloned)
     * @param effects          per-subproject Effect text, keyed by name
     * @param aggregatorEffect what post-release applied to the workspace root
     * @return the rendered markdown report body
     */
    private String buildReport(int bumped, int skipped,
                               Map<String, String> effects,
                               String aggregatorEffect) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**" + bumped + "** bumped, **" + skipped
                + "** skipped.");

        List<WorkingSetReportTable.Row> rows = new ArrayList<>();
        for (WorkingSet.Member member : resolveWorkingSet().members()) {
            File dir = member.directory().toFile();
            String version = readMemberVersion(dir);
            String branch = new File(dir, ".git").exists()
                    ? gitBranch(dir) : null;
            String sha = new File(dir, ".git").exists()
                    ? gitShortSha(dir) : null;
            String effect = member.isAggregator()
                    ? aggregatorEffect
                    : effects.getOrDefault(member.name(), "—");
            rows.add(new WorkingSetReportTable.Row(
                    member, version, branch, sha, effect));
        }
        WorkingSetReportTable.render(report, "Working set", rows);

        return report.build();
    }

    /**
     * Read a working-set member's POM version, gathered uniformly for
     * subprojects and the aggregator (the workspace root) alike — gathering
     * the root's version is the #763 fix, surfacing the staleness a
     * subprojects-only report hid. A missing or unreadable POM yields
     * {@code null}, rendered as the report's blank-cell placeholder.
     *
     * @param dir the member's directory
     * @return the POM version, or {@code null} when no readable POM exists
     */
    private String readMemberVersion(File dir) {
        File pomFile = new File(dir, "pom.xml");
        if (!pomFile.exists()) {
            return null;
        }
        try {
            return ReleaseSupport.readPomVersion(pomFile);
        } catch (MojoException e) {
            getLog().debug("Could not read POM version for " + dir + ": "
                    + e.getMessage());
            return null;
        }
    }
}
