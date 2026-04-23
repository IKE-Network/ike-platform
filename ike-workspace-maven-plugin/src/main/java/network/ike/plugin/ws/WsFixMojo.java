package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.Subproject;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synchronize denormalized workspace.yaml fields with the actual POM
 * values on disk.
 *
 * <p>This is the {@code ws:fix} goal — an alias for
 * {@code ws:verify --update}. It reads each cloned subproject's root
 * POM and updates the workspace manifest's {@code version} and
 * {@code groupId} fields when they drift from the POM truth.
 *
 * <p>Only <em>denormalized</em> fields are touched (version, groupId).
 * Novel fields (repo, branch, type, content edges, groups, notes) are
 * never modified.
 *
 * <pre>{@code mvn ws:fix}</pre>
 */
@Mojo(name = "fix", projectRequired = false)
public class WsFixMojo extends AbstractWorkspaceMojo {

    private static final Pattern GROUP_ID_PATTERN =
            Pattern.compile("<groupId>([^<]+)</groupId>");

    /** Creates this goal instance. */
    public WsFixMojo() {}

    @Override
    public void execute() throws MojoException {
        getLog().info("");
        getLog().info("IKE Workspace Fix — sync YAML from POM truth");
        getLog().info("══════════════════════════════════════════════════════════════");

        WorkspaceGraph graph = loadGraph();
        Path manifestPath = resolveManifest();
        File root = workspaceRoot();

        Map<String, String> versionUpdates = new LinkedHashMap<>();
        Map<String, String> groupIdUpdates = new LinkedHashMap<>();
        int skipped = 0;

        for (Map.Entry<String, Subproject> entry : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File subprojectDir = new File(root, name);
            File pomFile = new File(subprojectDir, "pom.xml");

            if (!pomFile.exists()) {
                getLog().debug("  " + name + ": not cloned — skipping");
                skipped++;
                continue;
            }

            // --- version ---
            try {
                String pomVersion = ReleaseSupport.readPomVersion(pomFile);
                String yamlVersion = subproject.version();

                if (yamlVersion != null && !yamlVersion.equals(pomVersion)) {
                    getLog().info("  " + name + ": version "
                            + yamlVersion + " → " + pomVersion);
                    versionUpdates.put(name, pomVersion);
                } else if (yamlVersion == null && pomVersion != null) {
                    getLog().info("  " + name + ": version (null) → " + pomVersion);
                    versionUpdates.put(name, pomVersion);
                }
            } catch (MojoException e) {
                getLog().warn("  " + name + ": could not read POM version — "
                        + e.getMessage());
            }

            // --- groupId ---
            try {
                String pomGroupId = readPomGroupId(pomFile);
                String yamlGroupId = subproject.groupId();

                if (pomGroupId != null && !pomGroupId.isEmpty()) {
                    if (yamlGroupId == null || yamlGroupId.isEmpty()
                            || !yamlGroupId.equals(pomGroupId)) {
                        getLog().info("  " + name + ": groupId "
                                + (yamlGroupId == null || yamlGroupId.isEmpty()
                                        ? "(empty)" : yamlGroupId)
                                + " → " + pomGroupId);
                        groupIdUpdates.put(name, pomGroupId);
                    }
                }
            } catch (MojoException e) {
                getLog().warn("  " + name + ": could not read POM groupId — "
                        + e.getMessage());
            }
        }

        // --- Write updates ---
        int totalChanges = versionUpdates.size() + groupIdUpdates.size();

        if (totalChanges == 0) {
            getLog().info("");
            getLog().info("  All denormalized fields are in sync  ✓");
            if (skipped > 0) {
                getLog().info("  (" + skipped + " subproject(s) not cloned)");
            }
            getLog().info("");
            writeReport(WsGoal.FIX, "All denormalized fields are in sync. "
                    + skipped + " subproject(s) not cloned.\n");
            return;
        }

        try {
            if (!versionUpdates.isEmpty()) {
                updateVersionFields(manifestPath, versionUpdates);
            }
            if (!groupIdUpdates.isEmpty()) {
                updateGroupIdFields(manifestPath, groupIdUpdates);
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to update workspace.yaml: " + e.getMessage(), e);
        }

        getLog().info("");
        getLog().info("  Updated " + totalChanges + " field(s) in workspace.yaml");
        if (skipped > 0) {
            getLog().info("  (" + skipped + " subproject(s) not cloned)");
        }
        getLog().info("");

        writeReport(WsGoal.FIX, "Updated **" + totalChanges + "** field(s): "
                + versionUpdates.size() + " version, "
                + groupIdUpdates.size() + " groupId. "
                + skipped + " not cloned.\n");
    }

    // ── POM groupId reader ──────────────────────────────────────────

    /**
     * Read the project's own {@code <groupId>} from a POM file,
     * skipping any {@code <groupId>} inside the {@code <parent>} block.
     *
     * <p>If the project-level POM has no explicit {@code <groupId>},
     * it inherits from the parent — in that case we fall back to the
     * parent's groupId.
     *
     * @param pomFile the POM file to read
     * @return the groupId, or null if not found
     * @throws MojoException if the file cannot be read
     */
    static String readPomGroupId(File pomFile) throws MojoException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);

            // First try: project-level groupId (outside <parent> block)
            String stripped = content.replaceFirst(
                    "(?s)<parent>.*?</parent>", "");
            Matcher matcher = GROUP_ID_PATTERN.matcher(stripped);
            if (matcher.find()) {
                return matcher.group(1);
            }

            // Fallback: inherited from parent
            Matcher parentMatcher = Pattern.compile(
                    "(?s)<parent>.*?<groupId>([^<]+)</groupId>.*?</parent>"
            ).matcher(content);
            if (parentMatcher.find()) {
                return parentMatcher.group(1);
            }

            return null;
        } catch (IOException e) {
            throw new MojoException("Failed to read " + pomFile, e);
        }
    }

    // ── Manifest field updaters (regex-based, preserving formatting) ─

    /**
     * Update the {@code version} field for each subproject in the YAML.
     * Uses {@link ManifestWriter#updateSubprojectField} to preserve
     * comments and formatting.
     */
    private void updateVersionFields(Path manifestPath,
                                     Map<String, String> updates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            content = ManifestWriter.updateSubprojectField(
                    content, entry.getKey(), "version", entry.getValue());
        }
        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }

    /**
     * Update the {@code groupId} field for each subproject in the YAML.
     * Uses {@link ManifestWriter#updateSubprojectField} to preserve
     * comments and formatting.
     */
    private void updateGroupIdFields(Path manifestPath,
                                     Map<String, String> updates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            content = ManifestWriter.updateSubprojectField(
                    content, entry.getKey(), "groupId", entry.getValue());
        }
        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }
}
