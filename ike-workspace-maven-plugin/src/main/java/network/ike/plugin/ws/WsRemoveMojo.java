package network.ike.plugin.ws;

import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;

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
 * <p>Removal is branch-scoped (IKE-Network/ike-issues#575): it edits the
 * <em>current branch's</em> {@code workspace.yaml} / {@code pom.xml} and works
 * on any branch, mirroring {@code ws:add}. With {@code -DdeleteDir=true} the
 * clone is <b>parked</b> (its branch pushed to origin, then removed), not
 * {@code rm}-ed — uncommitted work is stashed first and the push is a
 * precondition for removal, so no work is lost.
 *
 * <pre>{@code
 * mvn ws:remove -Dsubproject=tinkar-core
 * mvn ws:remove -Dsubproject=tinkar-core -Dforce=true
 * mvn ws:remove -Dsubproject=tinkar-core -DdeleteDir=true
 * }</pre>
 *
 * @see WsAddMojo for adding a subproject
 */
@Mojo(name = "remove", projectRequired = false, aggregator = true)
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
     * Also remove the cloned subproject directory from disk. When set, the
     * clone is <b>parked</b> (its branch pushed to origin, then the clone
     * removed), not {@code rm}-ed — see {@link ParkSupport#parkSubproject}.
     */
    @Parameter(property = "deleteDir", defaultValue = "false")
    private boolean deleteDir;

    /**
     * Opt out of auto-stash when parking a clone with uncommitted work
     * ({@code -DdeleteDir=true}). When {@code false} (default), uncommitted
     * changes are stashed to {@code refs/ws-stash/<user-slug>/<branch>} on
     * origin before the clone is parked so the work follows the developer;
     * when {@code true}, no stash is taken (the work still survives on the
     * pushed branch if committed, but uncommitted changes are discarded with
     * the clone). Mirrors {@code ws:switch}'s {@code -DnoStash}.
     */
    @Parameter(property = "noStash", defaultValue = "false")
    private boolean noStash;

    /** Creates this goal instance. */
    public WsRemoveMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        subproject = requireParam(subproject, "subproject",
                "Subproject name to remove");
        validateSubprojectName(subproject);

        // Resolve workspace root and paths
        Path manifestPath = resolveManifest();
        Path wsDir = manifestPath.getParent();
        Path pomPath = wsDir.resolve("pom.xml");

        // Removal is branch-scoped (#575): it operates on the current branch's
        // workspace.yaml / pom.xml and works on any branch, mirroring ws:add —
        // no main-only guard. Uncommitted work is not refused either: in the
        // default deleteDir=false path the clone is left untouched (its dirty
        // state preserved), and under -DdeleteDir=true the park path below
        // stashes WIP and pushes the branch before removing the clone, so
        // nothing is lost.

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

        // Optionally remove the cloned directory — work-preserving (#575).
        // A git clone is PARKED, not rm-ed: its branch is pushed to origin
        // (and any uncommitted work stashed) first, and the push is a hard
        // precondition for removal, so nothing is lost. A non-clone directory
        // (nothing to preserve) is plain-deleted.
        boolean parked = false;
        if (deleteDir) {
            Path subprojectDir = wsDir.resolve(subproject);
            File subDir = subprojectDir.toFile();
            if (new File(subDir, ".git").exists()) {
                String compBranch = gitBranch(subDir);
                // Preserve uncommitted work before parking: a dirty clone is
                // auto-stashed to refs/ws-stash/<slug>/<branch> on origin. The
                // slug is derived exactly as ws:switch does — fail-loud on a
                // missing git user.email — so WIP is never silently dropped;
                // set user.email, or pass -DnoStash to discard it, then re-run.
                if (!noStash && !gitStatus(subDir).isEmpty()) {
                    String slug = VcsOperations.userSlug(
                            VcsOperations.userEmail(wsDir.toFile()));
                    ParkSupport.stashLeave(subDir, getLog(), slug, compBranch);
                }
                ParkSupport.parkSubproject(subDir, getLog(), subproject, compBranch);
                getLog().info(Ansi.green("  ✓ ") + "Parked clone: "
                        + subprojectDir + " (" + compBranch + " → origin, "
                        + "clone removed)");
                parked = true;
            } else if (Files.isDirectory(subprojectDir)) {
                // Not a clone — nothing to preserve, plain delete.
                ParkSupport.deleteDirectory(subprojectDir);
                getLog().info(Ansi.green("  ✓ ") + "Deleted directory: " + subprojectDir);
            } else {
                getLog().info("  - Directory not present: " + subprojectDir);
            }
        }

        getLog().info("");
        getLog().info("  Subproject '" + subproject + "' removed.");
        getLog().info("");

        WorkspaceReportSpec spec = new WorkspaceReportSpec(WsGoal.REMOVE,
                "Removed subproject **" + subproject + "**."
                + (parked ? " Clone parked (branch pushed to origin, clone "
                        + "removed)." : "") + "\n");

        PostMutationSync.refresh(workspaceRoot(), getLog());
        return spec;
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

}
