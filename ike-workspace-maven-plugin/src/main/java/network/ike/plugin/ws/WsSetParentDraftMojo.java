package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Set the aggregator parent version (ike-parent) across the root POM and
 * all subproject POMs in one operation.
 *
 * <p>This goal cascades the parent version from the root POM to every
 * cloned subproject, including submodule POMs that reference the same
 * parent. It does <strong>not</strong> modify inter-subproject dependency
 * versions — use {@code ws:align-publish} for that.
 *
 * <p>The target version is specified via {@code -Dparent.version}. If
 * omitted, the goal reads the version already declared in the root POM
 * and cascades it to components (useful after a manual root POM edit).
 *
 * <pre>{@code
 * mvn ws:set-parent-draft -Dparent.version=92    # preview
 * mvn ws:set-parent-publish -Dparent.version=92   # apply
 * }</pre>
 *
 * @see WsSetParentPublishMojo
 * @see WsAlignDraftMojo
 */
@Mojo(name = "set-parent-draft", projectRequired = false, aggregator = true)
public class WsSetParentDraftMojo extends AbstractWorkspaceMojo {

    /**
     * When {@code true}, apply changes to POM files. When {@code false}
     * (default), report what would change without modifying anything.
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /**
     * Target parent version. If not supplied, reads the version from
     * the root POM's {@code <parent>} block and cascades it.
     */
    @Parameter(property = "parent.version")
    String parentVersion;

    /**
     * Resolved parent artifactId from the root POM, populated during
     * {@link #execute()}. Read by {@link WsSetParentPublishMojo} for
     * the auto-commit default message.
     */
    String resolvedParentArtifactId;

    /**
     * Number of POMs that were (or would be) updated, populated during
     * {@link #execute()}. Read by {@link WsSetParentPublishMojo} to
     * skip the auto-commit when there is nothing to commit.
     */
    int resolvedChangeCount;

    /** Creates this goal instance. */
    public WsSetParentDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        boolean draft = !publish;

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // --- Resolve target parent version ---
        Path rootPomPath = root.toPath().resolve("pom.xml");
        if (!Files.exists(rootPomPath)) {
            throw new MojoException(
                    "No pom.xml in workspace root: " + root);
        }

        PomParentSupport.ParentInfo rootParent;
        try {
            rootParent = PomParentSupport.readParent(rootPomPath);
        } catch (IOException e) {
            throw new MojoException(
                    "Cannot read root POM parent: " + e.getMessage(), e);
        }
        if (rootParent == null) {
            throw new MojoException(
                    "Root POM has no <parent> block — nothing to set.");
        }

        String targetVersion = requireParam(parentVersion,
                "parent.version", "Target parent version");
        parentVersion = targetVersion;

        String parentGroupId = rootParent.groupId();
        String parentArtifactId = rootParent.artifactId();
        this.resolvedParentArtifactId = parentArtifactId;

        // Preflight: all working trees must be clean (#132, #154)
        PreflightResult preflight = Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, graph.topologicalSort()));
        if (draft) {
            preflight.warnIfFailed(getLog(), WsGoal.SET_PARENT_PUBLISH);
        } else {
            preflight.requirePassed(WsGoal.SET_PARENT_PUBLISH);
        }

        // --- Header ---
        getLog().info("");
        getLog().info(header("Set Parent"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Target: " + rootParent.groupId() + ":"
                + parentArtifactId + ":" + targetVersion);
        getLog().info("  Mode:   " + (draft ? "DRAFT" : "PUBLISH"));
        getLog().info("");

        List<ParentChange> changes = new ArrayList<>();
        List<SkippedSubproject> skipped = new ArrayList<>();

        // --- Root POM ---
        String rootCurrentVersion = rootParent.version();
        if (!targetVersion.equals(rootCurrentVersion)) {
            changes.add(new ParentChange("(root)", "pom.xml",
                    rootCurrentVersion, targetVersion));
            if (!draft) {
                updateParentInPom(rootPomPath, parentGroupId,
                        parentArtifactId, targetVersion);
            }
        }

        // --- Subproject POMs ---
        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            File subprojectDir = new File(root, name);
            Path subprojectPom = subprojectDir.toPath().resolve("pom.xml");

            if (!Files.exists(subprojectPom)) {
                getLog().debug("  " + name + ": not cloned — skipping");
                continue;
            }

            // Read the subproject's root POM parent
            PomParentSupport.ParentInfo compParent;
            try {
                compParent = PomParentSupport.readParent(subprojectPom);
            } catch (IOException e) {
                getLog().warn("  " + name
                        + ": cannot read parent — " + e.getMessage());
                continue;
            }

            if (compParent == null) {
                getLog().debug("  " + name + ": no <parent> — skipping");
                continue;
            }

            // Only update POMs whose parent GAV fully matches the
            // workspace root's parent GAV. Both groupId AND artifactId
            // must match — see issue #241. A heterogeneous workspace
            // may bundle subprojects that inherit from unrelated
            // parents (e.g. network.ike.pipeline:ike-parent) that
            // happen to share an artifactId with the workspace parent.
            if (!parentGroupId.equals(compParent.groupId())
                    || !parentArtifactId.equals(compParent.artifactId())) {
                String actualGav = compParent.groupId() + ":"
                        + compParent.artifactId() + ":"
                        + compParent.version();
                getLog().info("  " + Ansi.yellow("⊘ ") + name
                        + ": parent " + actualGav
                        + " — skipped (workspace parent is "
                        + parentGroupId + ":" + parentArtifactId + ")");
                skipped.add(new SkippedSubproject(name, actualGav));
                continue;
            }

            String currentVersion = compParent.version();
            if (targetVersion.equals(currentVersion)) {
                continue;
            }

            changes.add(new ParentChange(name, "pom.xml",
                    currentVersion, targetVersion));

            if (!draft) {
                updateParentInPom(subprojectPom, parentGroupId,
                        parentArtifactId, targetVersion);

                // Also update submodule POMs referencing the same parent
                List<File> subPoms;
                try {
                    subPoms = ReleaseSupport.findPomFiles(subprojectDir);
                } catch (MojoException e) {
                    getLog().warn("  " + name
                            + ": cannot scan submodules — " + e.getMessage());
                    continue;
                }

                for (File subPom : subPoms) {
                    if (subPom.toPath().equals(subprojectPom)) {
                        continue;
                    }
                    try {
                        String subContent = Files.readString(
                                subPom.toPath(), StandardCharsets.UTF_8);
                        String subUpdated =
                                PomParentSupport.updateParentVersion(
                                        subContent, parentGroupId,
                                        parentArtifactId, targetVersion);
                        if (!subUpdated.equals(subContent)) {
                            Files.writeString(subPom.toPath(), subUpdated,
                                    StandardCharsets.UTF_8);
                            String relPath = subprojectDir.toPath()
                                    .relativize(subPom.toPath()).toString();
                            getLog().debug("  " + name + "/" + relPath
                                    + ": submodule parent updated");
                        }
                    } catch (IOException e) {
                        getLog().warn("  " + name + ": submodule update failed — "
                                + e.getMessage());
                    }
                }
            }
        }

        // --- Output ---
        for (ParentChange c : changes) {
            getLog().info("  " + padRight(c.subproject + "/" + c.pomRelPath, 40)
                    + c.fromVersion + " → " + c.toVersion);
        }
        getLog().info("");

        if (changes.isEmpty()) {
            getLog().info("  " + Ansi.GREEN + "✓ " + Ansi.RESET
                    + "All POMs already at " + parentArtifactId
                    + ":" + targetVersion);
        } else if (draft) {
            getLog().info("  " + changes.size()
                    + " POM(s) would be updated.");
            getLog().info("  Use ws:set-parent-publish"
                    + (parentVersion != null
                    ? " -Dparent.version=" + targetVersion : "")
                    + " to apply changes.");
        } else {
            getLog().info("  " + Ansi.GREEN + "✓ " + Ansi.RESET
                    + "Updated " + changes.size() + " POM(s) to "
                    + parentArtifactId + ":" + targetVersion);
        }
        if (!skipped.isEmpty()) {
            getLog().info("  " + skipped.size()
                    + " subproject(s) skipped due to parent GAV mismatch"
                    + " — see report for details.");
        }
        getLog().info("");

        this.resolvedChangeCount = changes.size();

        // --- Report ---
        writeReport(publish ? WsGoal.SET_PARENT_PUBLISH : WsGoal.SET_PARENT_DRAFT,
                buildMarkdownReport(parentArtifactId, targetVersion,
                        parentGroupId, changes, skipped, draft));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Update the parent version in a single POM file.
     *
     * @param pomPath          path to the POM file
     * @param parentGroupId    the parent groupId to match
     * @param parentArtifactId the parent artifactId to match
     * @param newVersion       the new parent version
     * @throws MojoException if the file cannot be read or written
     */
    private void updateParentInPom(Path pomPath, String parentGroupId,
                                    String parentArtifactId,
                                    String newVersion)
            throws MojoException {
        try {
            String content = Files.readString(pomPath, StandardCharsets.UTF_8);
            String updated = PomParentSupport.updateParentVersion(
                    content, parentGroupId, parentArtifactId, newVersion);
            if (!updated.equals(content)) {
                Files.writeString(pomPath, updated, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to update parent in " + pomPath + ": "
                            + e.getMessage(), e);
        }
    }

    /**
     * Right-pad a string to the given width.
     *
     * @param s     the string to pad
     * @param width the minimum width
     * @return the padded string
     */
    private static String padRight(String s, int width) {
        return s.length() >= width ? s + " "
                : s + " ".repeat(width - s.length());
    }

    /**
     * Build a structured markdown report.
     *
     * @param parentArtifactId parent artifact name
     * @param targetVersion    target version
     * @param parentGroupId    parent groupId (used for the skipped table)
     * @param changes          list of changes
     * @param skipped          subprojects whose parent GAV did not match
     * @param draft            whether this is a draft run
     * @return markdown content
     */
    private String buildMarkdownReport(String parentArtifactId,
                                        String targetVersion,
                                        String parentGroupId,
                                        List<ParentChange> changes,
                                        List<SkippedSubproject> skipped,
                                        boolean draft) {
        StringBuilder md = new StringBuilder();

        md.append("**Target:** `").append(parentGroupId)
          .append(':').append(parentArtifactId)
          .append(':').append(targetVersion).append("`\n\n");

        if (changes.isEmpty() && skipped.isEmpty()) {
            md.append("All POMs already at target version.\n");
            return md.toString();
        }

        if (!changes.isEmpty()) {
            if (draft) {
                md.append("**Dry run** — ").append(changes.size())
                  .append(" POM(s) would be updated.\n\n");
            } else {
                md.append("Updated ").append(changes.size())
                  .append(" POM(s).\n\n");
            }

            md.append("| Subproject | POM | From | To |\n");
            md.append("|-----------|-----|------|----|\n");
            for (ParentChange c : changes) {
                md.append("| ").append(c.subproject)
                  .append(" | ").append(c.pomRelPath)
                  .append(" | ").append(c.fromVersion)
                  .append(" | ").append(c.toVersion)
                  .append(" |\n");
            }
        }

        if (!skipped.isEmpty()) {
            md.append("\n## Skipped (parent GAV mismatch)\n\n");
            md.append("These subprojects inherit from a different parent GAV"
                    + " and were not touched. The workspace parent is `")
              .append(parentGroupId).append(':').append(parentArtifactId)
              .append("`; each subproject's actual parent is shown below.\n\n");
            md.append("| Subproject | Actual Parent |\n");
            md.append("|-----------|---------------|\n");
            for (SkippedSubproject s : skipped) {
                md.append("| ").append(s.subproject)
                  .append(" | `").append(s.actualParentGav)
                  .append("` |\n");
            }
        }

        return md.toString();
    }

    /**
     * A single parent-version change for reporting.
     *
     * @param subproject  subproject name or "(root)"
     * @param pomRelPath  relative POM path within subproject
     * @param fromVersion current parent version
     * @param toVersion   target parent version
     */
    private record ParentChange(String subproject, String pomRelPath,
                                 String fromVersion, String toVersion) {}

    /**
     * A subproject whose parent GAV did not match the workspace root's
     * parent and was therefore skipped. Recorded for the report so the
     * user can see exactly which subprojects were considered and why
     * they were left untouched.
     *
     * @param subproject       subproject name from workspace.yaml
     * @param actualParentGav  the subproject's parent {@code groupId:artifactId:version}
     */
    private record SkippedSubproject(String subproject,
                                      String actualParentGav) {}
}
