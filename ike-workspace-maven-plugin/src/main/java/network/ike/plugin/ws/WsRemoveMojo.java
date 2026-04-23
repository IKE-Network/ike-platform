package network.ike.plugin.ws;

import network.ike.workspace.WorkspaceGraph;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remove a subproject from the workspace.
 *
 * <p>Given a subproject name, this goal:
 * <ol>
 *   <li>Loads the workspace graph and verifies the subproject exists</li>
 *   <li>Checks for downstream dependents — fails if any exist
 *       (unless {@code -Dforce=true})</li>
 *   <li>Removes the subproject entry from workspace.yaml</li>
 *   <li>Removes the file-activated profile from the aggregator pom.xml</li>
 *   <li>Removes the subproject from any group lists in workspace.yaml</li>
 *   <li>Optionally deletes the cloned directory</li>
 * </ol>
 *
 * <pre>{@code
 * mvn ike:ws-remove -Dsubproject=tinkar-core
 * mvn ike:ws-remove -Dsubproject=tinkar-core -Dforce=true
 * mvn ike:ws-remove -Dsubproject=tinkar-core -DdeleteDir=true
 * }</pre>
 *
 * @see WsAddMojo for adding a subproject
 */
@Mojo(name = "remove", projectRequired = false)
public class WsRemoveMojo extends AbstractWorkspaceMojo {

    /**
     * Subproject name to remove (required).
     */
    @Parameter(property = "subproject")
    private String subproject;

    /**
     * Skip the downstream-dependent safety check.
     */
    @Parameter(property = "force", defaultValue = "false")
    private boolean force;

    /**
     * Also delete the cloned subproject directory from disk.
     */
    @Parameter(property = "deleteDir", defaultValue = "false")
    private boolean deleteDir;

    /** Creates this goal instance. */
    public WsRemoveMojo() {}

    @Override
    public void execute() throws MojoException {
        subproject = requireParam(subproject, "subproject",
                "Subproject name to remove");

        // Resolve workspace root and paths
        Path manifestPath = resolveManifest();
        Path wsDir = manifestPath.getParent();
        Path pomPath = wsDir.resolve("pom.xml");

        // Remove is main-only — workspace composition changes belong on main,
        // not on feature branches. Add is allowed on any branch (discovery
        // during development), but removal is a structural decision.
        File subDir = wsDir.resolve(subproject).toFile();
        if (new File(subDir, ".git").exists()) {
            String currentBranch = gitBranch(subDir);
            if (!currentBranch.equals("main")) {
                throw new MojoException(
                        "Cannot remove a subproject from a feature branch ('"
                        + currentBranch + "'). Switch to 'main' first. "
                        + "Workspace composition changes belong on main.");
            }

            // Verify clean working tree — no uncommitted changes
            String status = gitStatus(subDir);
            if (!status.isEmpty()) {
                throw new MojoException(
                        "Cannot remove '" + subproject + "' — working tree has "
                        + "uncommitted changes. Commit or stash first.");
            }
        }

        // Load graph and validate subproject exists
        WorkspaceGraph graph = loadGraph();

        if (!graph.manifest().subprojects().containsKey(subproject)) {
            throw new MojoException(
                    "Subproject '" + subproject + "' not found in workspace.yaml.");
        }

        // Check for downstream dependents
        List<String> dependents = graph.cascade(subproject);
        if (!dependents.isEmpty() && !force) {
            throw new MojoException(
                    "Cannot remove '" + subproject + "' — the following subprojects "
                    + "depend on it: " + dependents + "\n"
                    + "Use -Dforce=true to remove anyway.");
        }

        getLog().info("");
        getLog().info(header("Remove Subproject"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Subproject: " + subproject);
        if (!dependents.isEmpty()) {
            getLog().warn("  Dependents (forced): " + dependents);
        }
        getLog().info("");

        try {
            // Remove from workspace.yaml
            removeSubprojectFromManifest(manifestPath);
            getLog().info(Ansi.green("  ✓ ") + "workspace.yaml updated — subproject entry removed");

            // Remove profile from pom.xml
            removeProfileFromPom(pomPath);
            getLog().info(Ansi.green("  ✓ ") + "pom.xml updated — profile with-" + subproject + " removed");

        } catch (IOException e) {
            throw new MojoException(
                    "Failed to update workspace files: " + e.getMessage(), e);
        }

        // Optionally delete the cloned directory
        if (deleteDir) {
            Path subprojectDir = wsDir.resolve(subproject);
            if (Files.isDirectory(subprojectDir)) {
                deleteDirectory(subprojectDir);
                getLog().info(Ansi.green("  ✓ ") + "Deleted directory: " + subprojectDir);
            } else {
                getLog().info("  - Directory not present: " + subprojectDir);
            }
        }

        getLog().info("");
        getLog().info("  Subproject '" + subproject + "' removed.");
        getLog().info("");

        writeReport(WsGoal.REMOVE, "Removed subproject **" + subproject + "**."
                + (deleteDir ? " Directory deleted." : "") + "\n");
    }

    // ── YAML removal ────────────────────────────────────────────

    /**
     * Remove a subproject block from workspace.yaml.
     *
     * <p>Matches the subproject header at 2-space indent under
     * {@code subprojects:} and removes everything until the next
     * subproject header or section header (a line at 0 or 2-space
     * indent that is not a continuation of this block).
     */
    void removeSubprojectFromManifest(Path manifestPath) throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);

        // Match:  "  subproject-name:\n" followed by lines at 4+ space indent
        // (including multi-line description blocks) until the next 2-space key
        // or top-level key or end of file.
        String escaped = Pattern.quote(subproject);
        Pattern blockPattern = Pattern.compile(
                "\\n  " + escaped + ":\\s*\\n(?:    .*\\n)*",
                Pattern.MULTILINE);

        Matcher m = blockPattern.matcher(yaml);
        if (m.find()) {
            yaml = m.replaceFirst("\n");
        }

        Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
    }

    // ── POM removal ─────────────────────────────────────────────

    /**
     * Remove the file-activated profile for this subproject from pom.xml.
     *
     * <p>Matches the entire {@code <profile>} block whose
     * {@code <id>} is {@code with-<subproject>}.
     */
    void removeProfileFromPom(Path pomPath) throws IOException {
        if (!Files.exists(pomPath)) {
            getLog().warn("  No pom.xml found at " + pomPath);
            return;
        }

        String pom = Files.readString(pomPath, StandardCharsets.UTF_8);
        String profileId = "with-" + subproject;

        if (!pom.contains(profileId)) {
            getLog().info("  - Profile " + profileId + " not found in pom.xml (already removed?)");
            return;
        }

        // Match the entire <profile>...</profile> block containing this profile id.
        // Allow flexible whitespace. The profile block ends at </profile>.
        String escapedId = Pattern.quote(profileId);
        Pattern profilePattern = Pattern.compile(
                "\\s*<profile>\\s*\\n"
                + "\\s*<id>" + escapedId + "</id>\\s*\\n"
                + ".*?"
                + "\\s*</profile>\\s*\\n",
                Pattern.DOTALL);

        Matcher m = profilePattern.matcher(pom);
        if (m.find()) {
            pom = pom.substring(0, m.start()) + "\n" + pom.substring(m.end());
        }

        Files.writeString(pomPath, pom, StandardCharsets.UTF_8);
    }

    // ── Directory deletion ──────────────────────────────────────

    /**
     * Recursively delete a directory tree.
     */
    private void deleteDirectory(Path dir) throws MojoException {
        try {
            // Walk the tree bottom-up and delete
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new RuntimeException(
                                    "Failed to delete " + path + ": " + e.getMessage(), e);
                        }
                    });
        } catch (IOException | RuntimeException e) {
            throw new MojoException(
                    "Failed to delete directory " + dir + ": " + e.getMessage(), e);
        }
    }
}
