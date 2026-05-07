package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceRoot;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Migrate an existing workspace from the pre-#183 placeholder
 * {@code local.aggregate:<name>:1.0.0-SNAPSHOT} coordinates to real
 * Maven coordinates (ike-issues#184).
 *
 * <p>The placeholder GAV signals "throwaway" and prevents
 * {@code ws:release} (#185) from releasing the workspace root,
 * {@code ws:align} from aligning to it, and site deploy (#186) from
 * publishing under a stable address. {@code ws:adopt-root} performs
 * the one-time rewrite:
 *
 * <ol>
 *   <li>Rewrites the workspace root POM's own
 *       {@code <groupId>}/{@code <artifactId>}/{@code <version>}
 *       via {@link PomRewriter#updateProjectCoordinates} (semantic
 *       LST, never regex — per
 *       {@code feedback_no_sed_on_poms}).</li>
 *   <li>Inserts a {@code workspace-root:} block at the top of
 *       {@code workspace.yaml} with the new coordinates; bumps
 *       {@code schema-version} to 1.1 if still on 1.0.</li>
 *   <li>Auto-commits both files in one workspace-root commit.</li>
 * </ol>
 *
 * <p>Idempotent: re-running on a workspace that already has a
 * {@code workspace-root:} block is a no-op with an info message.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * mvn ws:adopt-root -Dgroup=network.ike.workspace
 * mvn ws:adopt-root -Dgroup=org.example -Dversion=2-SNAPSHOT
 * mvn ws:adopt-root -Dgroup=org.example -DartifactId=alt-name
 * }</pre>
 *
 * @see WsCreateMojo for new workspaces (which carry real coordinates
 *      from the start).
 */
@Mojo(name = "adopt-root", projectRequired = false, aggregator = true)
public class WsAdoptRootMojo extends AbstractWorkspaceMojo {

    /**
     * Maven groupId for the workspace root POM. Required (no default).
     * Persisted to {@code workspace-root.groupId} in workspace.yaml
     * and written into the root pom's project-level
     * {@code <groupId>}.
     */
    @Parameter(property = "group")
    private String group;

    /**
     * Initial workspace root version. Single-segment monotonic
     * (per {@code feedback_no_semver_assumption}); defaults to
     * {@code 1-SNAPSHOT}. The version increments after each release
     * of the workspace root, NOT per software-meaningful change.
     */
    @Parameter(property = "version", defaultValue = "1-SNAPSHOT")
    private String version;

    /**
     * Maven artifactId for the workspace root POM. Defaults to the
     * artifactId currently in the root pom.xml when omitted. Useful
     * when adopting under a different artifact name than the legacy
     * placeholder used.
     */
    @Parameter(property = "artifactId")
    private String artifactId;

    /** Creates this goal instance. */
    public WsAdoptRootMojo() {}

    @Override
    public void execute() throws MojoException {
        if (group == null || group.isBlank()) {
            throw new MojoException(
                    "ws:adopt-root requires -Dgroup=<groupId> (e.g. "
                            + "network.ike.workspace). See ike-issues#184.");
        }

        File root = workspaceRoot();
        Path manifestPath = root.toPath().resolve("workspace.yaml");
        Path pomPath = root.toPath().resolve("pom.xml");

        if (!Files.exists(manifestPath)) {
            throw new MojoException(
                    "No workspace.yaml found in " + root
                            + ". ws:adopt-root migrates an existing "
                            + "workspace; for new workspaces use ws:create.");
        }
        if (!Files.exists(pomPath)) {
            throw new MojoException(
                    "No pom.xml found in " + root + ". The workspace root POM "
                            + "is required.");
        }

        // ── Idempotency guard ──────────────────────────────────────
        try {
            Manifest existing = ManifestReader.read(manifestPath);
            if (existing.workspaceRoot() != null) {
                WorkspaceRoot wr = existing.workspaceRoot();
                getLog().info("");
                getLog().info("  workspace.yaml already declares workspace-root:");
                getLog().info("    " + wr.groupId() + ":" + wr.artifactId()
                        + ":" + wr.version());
                getLog().info("  ws:adopt-root is idempotent — nothing to do.");
                getLog().info("");
                writeReport(WsGoal.ADOPT_ROOT,
                        "Idempotent skip — `workspace-root:` already declared:\n\n"
                                + "* groupId: `" + wr.groupId() + "`\n"
                                + "* artifactId: `" + wr.artifactId() + "`\n"
                                + "* version: `" + wr.version() + "`\n");
                return;
            }
        } catch (ManifestException e) {
            // Empty / malformed manifest — fall through and migrate.
        }

        // ── Resolve effective artifactId ───────────────────────────
        // Default to the current pom.xml artifactId so existing
        // consumers' parent references stay resolvable across the
        // adopt boundary.
        String resolvedArtifactId = artifactId;
        if (resolvedArtifactId == null || resolvedArtifactId.isBlank()) {
            try {
                resolvedArtifactId = ReleaseSupport.readPomArtifactId(
                        pomPath.toFile());
            } catch (MojoException e) {
                throw new MojoException(
                        "Cannot read root pom.xml artifactId — supply "
                                + "-DartifactId=<id> explicitly. ("
                                + e.getMessage() + ")");
            }
        }

        // Validate artifactId via SubprojectName, version via
        // MavenVersion (#295) — single typed boundary for the
        // strings flowing into the rewritten POM and YAML.
        validateSubprojectName(resolvedArtifactId);
        validateMavenVersion(version);

        getLog().info("");
        getLog().info("Adopt Root — workspace coordinates");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  GAV: " + group + ":" + resolvedArtifactId + ":" + version);
        getLog().info("");

        // ── Rewrite root pom.xml ───────────────────────────────────
        try {
            String pomContent = Files.readString(pomPath, StandardCharsets.UTF_8);
            String updated = PomRewriter.updateProjectCoordinates(
                    pomContent, group, resolvedArtifactId, version);
            if (updated.equals(pomContent)) {
                getLog().info("  pom.xml: already at target coordinates  ✓");
            } else {
                Files.writeString(pomPath, updated, StandardCharsets.UTF_8);
                getLog().info(Ansi.green("  ✓ ") + "pom.xml: groupId/artifactId/version rewritten");
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to update root pom.xml: " + e.getMessage(), e);
        }

        // ── Insert workspace-root: block + bump schema-version ─────
        try {
            String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);
            String migrated = insertWorkspaceRootBlock(
                    yaml, group, resolvedArtifactId, version);
            Files.writeString(manifestPath, migrated, StandardCharsets.UTF_8);
            getLog().info(Ansi.green("  ✓ ") + "workspace.yaml: workspace-root block inserted, schema → 1.1");
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to update workspace.yaml: " + e.getMessage(), e);
        }

        // ── Commit both files in one workspace-root commit ─────────
        if (new File(root, ".git").exists()) {
            try {
                ReleaseSupport.exec(root, getLog(),
                        "git", "add", "workspace.yaml", "pom.xml");
                if (VcsOperations.hasStagedChanges(root)) {
                    VcsOperations.commit(root, getLog(),
                            "workspace: adopt root coordinates "
                                    + group + ":" + resolvedArtifactId
                                    + ":" + version);
                    getLog().info(Ansi.green("  ✓ ") + "committed");
                }
            } catch (MojoException e) {
                getLog().warn("  Auto-commit failed (non-fatal): "
                        + e.getMessage());
            }
        }

        getLog().info("");
        getLog().info("  Recommend running 'mvn ws:align-publish' next in case any");
        getLog().info("  subproject pinned the placeholder root coordinates.");
        getLog().info("");

        writeReport(WsGoal.ADOPT_ROOT,
                "Adopted workspace root coordinates:\n\n"
                        + "* groupId: `" + group + "`\n"
                        + "* artifactId: `" + resolvedArtifactId + "`\n"
                        + "* version: `" + version + "`\n");
    }

    /**
     * Insert a {@code workspace-root:} block into existing
     * workspace.yaml content and bump {@code schema-version} from 1.0
     * to 1.1 if present. Idempotent on schema-version (already-1.1
     * stays 1.1).
     *
     * <p>Insertion anchor: the block is placed immediately after the
     * {@code generated:} line (or, if absent, after
     * {@code schema-version:}). Comments and other content are
     * preserved verbatim.
     *
     * <p>Package-private for test access.
     *
     * @param yaml          current workspace.yaml content
     * @param groupId       the resolved groupId
     * @param artifactId    the resolved artifactId
     * @param version       the resolved version
     * @return migrated YAML content
     */
    static String insertWorkspaceRootBlock(String yaml, String groupId,
                                           String artifactId, String version) {
        // Schema bump 1.0 → 1.1 (the workspace-root block is a 1.1
        // addition; leave higher schema versions as-is).
        String migrated = yaml.replaceFirst(
                "(?m)^schema-version:\\s*\"1\\.0\"\\s*$",
                "schema-version: \"1.1\"");

        StringBuilder block = new StringBuilder();
        block.append("\n# workspace-root: typed Maven coordinates for the\n");
        block.append("# aggregator artifact (ike-issues#183, schema 1.1).\n");
        block.append("workspace-root:\n");
        block.append("  groupId: ").append(groupId).append("\n");
        block.append("  artifactId: ").append(artifactId).append("\n");
        block.append("  version: ").append(version).append("\n");

        // Anchor: prefer to insert after the generated: line; fall
        // back to after schema-version: if generated: is absent.
        java.util.regex.Pattern generatedLine = java.util.regex.Pattern.compile(
                "(?m)^generated:.*$");
        java.util.regex.Matcher gm = generatedLine.matcher(migrated);
        if (gm.find()) {
            int after = gm.end();
            return migrated.substring(0, after) + "\n" + block
                    + migrated.substring(after);
        }
        java.util.regex.Pattern schemaLine = java.util.regex.Pattern.compile(
                "(?m)^schema-version:.*$");
        java.util.regex.Matcher sm = schemaLine.matcher(migrated);
        if (sm.find()) {
            int after = sm.end();
            return migrated.substring(0, after) + "\n" + block
                    + migrated.substring(after);
        }
        // Defensive: prepend if neither anchor exists.
        return block + migrated;
    }
}
