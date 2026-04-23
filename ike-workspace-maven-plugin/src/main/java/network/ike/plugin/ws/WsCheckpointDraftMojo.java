package network.ike.plugin.ws;

import network.ike.plugin.ReleaseNotesSupport;
import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;

import network.ike.workspace.Subproject;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.SubprojectType;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

/**
 * Create a workspace checkpoint — tag every subproject at its current HEAD
 * and record the snapshot in a YAML manifest.
 *
 * <p>A checkpoint records the current state of the workspace for reproduction.
 * It is not a build or a release — no POM version changes, no compilation,
 * no deployment. TeamCity watches for checkpoint tags on the workspace repo
 * and handles CI.
 *
 * <p>Each subproject is tagged in topological order (dependencies before
 * dependents). After all subprojects are tagged, a YAML file recording
 * the SHAs, versions, and branches is committed and tagged in the
 * workspace aggregator repo.
 *
 * <pre>{@code
 * mvn ws:checkpoint                          # auto-derived name
 * mvn ws:checkpoint -Dname=sprint-42         # explicit name
 * mvn ws:checkpoint                          # draft (default)
 * mvn ws:checkpoint-publish                  # execute
 * }</pre>
 *
 * @see CheckpointSupport the per-subproject tagging engine
 */
@Mojo(name = "checkpoint-draft", projectRequired = false)
public class WsCheckpointDraftMojo extends AbstractWorkspaceMojo {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter COMPACT_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC);

    /**
     * Checkpoint name. Used in the YAML filename and tag names.
     * If omitted, auto-derived from the workspace branch and a compact
     * UTC timestamp ({@code <branch>-<yyyyMMdd>-<HHmmss>}).
     */
    @Parameter(property = "name")
    String name;

    /**
     * Show what the checkpoint would do without creating tags or writing
     * files. Set automatically by {@code ws:checkpoint} (bare goal is
     * draft; use {@code ws:checkpoint-publish} to execute).
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /**
     * GitHub repository for issue tracking, used to snapshot active
     * issues into the checkpoint's testing context.
     */
    @Parameter(property = "issueRepo", defaultValue = "IKE-Network/ike-issues")
    String issueRepo;

    /**
     * Milestone name to snapshot for testing context. If omitted,
     * looks for an open milestone matching the workspace's primary
     * subproject (first subproject in manifest) in the form
     * {@code <artifactId> v<version>} where version is the current
     * SNAPSHOT stripped of the suffix.
     */
    @Parameter(property = "milestone")
    String milestone;

    /** Creates this goal instance. */
    public WsCheckpointDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        boolean draft = !publish;

        // Preflight: all working trees must be clean (#132, #154)
        PreflightResult preflight = Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, graph, graph.topologicalSort()));
        if (draft) {
            preflight.warnIfFailed(getLog(), WsGoal.CHECKPOINT_PUBLISH);
        } else {
            preflight.requirePassed(WsGoal.CHECKPOINT_PUBLISH);
        }

        if (name == null || name.isBlank()) {
            name = deriveCheckpointName(root);
        }

        String wsTagName = "checkpoint/" + name;
        String timestamp = ISO_UTC.format(Instant.now());
        String author = resolveAuthor(root);

        getLog().info("");
        getLog().info(header("Checkpoint"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Name:   " + name);
        getLog().info("  Tag:    " + wsTagName);
        getLog().info("  Time:   " + timestamp);
        getLog().info("  Author: " + author);
        if (draft) {
            getLog().info("  Mode:   DRAFT — no tags, no files written");
        }
        getLog().info("");

        // ── Tag each subproject in dependency order ────────────────────
        List<SubprojectSnapshot> snapshots = new ArrayList<>();
        List<String> absentComponents = new ArrayList<>();

        List<String> ordered = graph.topologicalSort(
                new LinkedHashSet<>(graph.manifest().subprojects().keySet()));

        for (String subName : ordered) {
            Subproject subproject = graph.manifest().subprojects().get(subName);
            File dir = new File(root, subName);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                absentComponents.add(subName);
                getLog().info("  - " + subName + " [absent — skipped]");
                continue;
            }

            String branch   = gitBranch(dir);
            String sha      = gitFullSha(dir);
            String shortSha = gitShortSha(dir);
            String version  = readVersion(dir);

            boolean composite = subproject.type().checkpointMechanism()
                    == SubprojectType.CheckpointMechanism.COMPOSITE;

            if (draft) {
                getLog().info(Ansi.green("  ✓ ") + subName
                        + " [" + shortSha + "] " + branch
                        + " (" + version + ")");
                CheckpointSupport.preview(dir, wsTagName, getLog());
                snapshots.add(new SubprojectSnapshot(
                        subName, sha, shortSha, branch,
                        version, false, subproject.type().yamlName(), composite));
            } else {
                CheckpointSupport.checkpoint(dir, wsTagName, getLog());
                getLog().info(Ansi.green("  ✓ ") + subName
                        + " [" + shortSha + "] → " + wsTagName);
                snapshots.add(new SubprojectSnapshot(
                        subName, sha, shortSha, branch,
                        version, false, subproject.type().yamlName(), composite));
            }
        }

        // ── Build checkpoint YAML ──────────────────────────────────────
        String yamlContent = buildCheckpointYaml(
                name, timestamp, author,
                graph.manifest().schemaVersion(),
                snapshots, absentComponents);

        // ── Append testing context from milestone ─────────────────────
        String testingContextYaml = snapshotTestingContext(graph);
        if (testingContextYaml != null) {
            yamlContent = yamlContent + "\n" + testingContextYaml;
        }

        if (draft) {
            getLog().info("");
            getLog().info("[DRAFT] Checkpoint file would be written to:");
            getLog().info("[DRAFT]   checkpoints/" + checkpointFileName(name));
            getLog().info("");
            getLog().info("[DRAFT] Contents:");
            yamlContent.lines().forEach(line ->
                    getLog().info("[DRAFT]   " + line));
            getLog().info("");
            return;
        }

        // ── Write subproject SHAs into workspace.yaml ────────────────
        try {
            java.util.Map<String, String> shaUpdates = new java.util.LinkedHashMap<>();
            for (SubprojectSnapshot snap : snapshots) {
                shaUpdates.put(snap.name(), snap.sha());
            }
            ManifestWriter.updateShas(resolveManifest(), shaUpdates);
            getLog().info("  Updated workspace.yaml with subproject SHAs");
        } catch (IOException e) {
            getLog().warn("  Could not update workspace.yaml SHAs: " + e.getMessage());
        }

        // ── Write checkpoint file ──────────────────────────────────────
        Path checkpointsDir = root.toPath().resolve("checkpoints");
        try {
            Files.createDirectories(checkpointsDir);
        } catch (IOException e) {
            throw new MojoException(
                    "Cannot create checkpoints directory", e);
        }
        Path checkpointFile = checkpointsDir.resolve(checkpointFileName(name));
        try {
            Files.writeString(checkpointFile, yamlContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to write " + checkpointFile, e);
        }

        // ── Tag and push workspace aggregator repo ──────────────────
        File wsGitDir = new File(root, ".git");
        if (wsGitDir.exists()) {
            ReleaseSupport.exec(root, getLog(),
                    "git", "add", "workspace.yaml",
                    "checkpoints/" + checkpointFileName(name));
            ReleaseSupport.exec(root, getLog(),
                    "git", "commit", "-m",
                    "checkpoint: " + name);
            ReleaseSupport.exec(root, getLog(),
                    "git", "tag", "-a", wsTagName,
                    "-m", "Workspace checkpoint " + name);

            boolean hasOrigin = ReleaseSupport.hasRemote(root, "origin");
            if (hasOrigin) {
                ReleaseSupport.exec(root, getLog(),
                        "git", "push", "origin", wsTagName);
                ReleaseSupport.exec(root, getLog(),
                        "git", "push", "origin",
                        ReleaseSupport.currentBranch(root));
                getLog().info("  Workspace tag pushed: " + wsTagName);
            }
        }

        // VCS bridge: write state file after checkpoint
        for (var entry : graph.manifest().subprojects().entrySet()) {
            File subDir = new File(root, entry.getKey());
            if (new File(subDir, ".git").exists()
                    && VcsState.isIkeManaged(subDir.toPath())) {
                VcsOperations.writeVcsState(subDir, VcsState.Action.CHECKPOINT);
            }
        }
        if (VcsState.isIkeManaged(root.toPath())) {
            VcsOperations.writeVcsState(root, VcsState.Action.CHECKPOINT);
        }

        getLog().info("");
        getLog().info("  Checkpoint: " + checkpointFile);
        getLog().info("  Components: " + snapshots.size()
                + " | Absent: " + absentComponents.size());
        getLog().info("");

        writeReport(publish ? WsGoal.CHECKPOINT_PUBLISH : WsGoal.CHECKPOINT_DRAFT,
                buildCheckpointMarkdownReport(snapshots, absentComponents));
    }

    // ── Per-subproject checkpoint (overridable for tests) ──────────────

    /**
     * Tag a single subproject at its current HEAD. Override in tests
     * to substitute a lighter-weight simulation.
     *
     * @param dir     the subproject directory to checkpoint
     * @param tagName the tag name to apply
     * @throws MojoException if the tagging operation fails
     */
    protected void checkpointComponent(File dir, String tagName)
            throws MojoException {
        CheckpointSupport.checkpoint(dir, tagName, getLog());
    }

    // ── Report ────────────────────────────────────────────────────────

    private String buildCheckpointMarkdownReport(
            List<SubprojectSnapshot> snapshots, List<String> absent) {
        var sb = new StringBuilder();
        sb.append(snapshots.size()).append(" subproject(s) checkpointed");
        if (!absent.isEmpty()) {
            sb.append(", ").append(absent.size()).append(" absent");
        }
        sb.append(!publish ? " (draft)" : "").append(".\n\n");
        sb.append("| Subproject | Version | SHA | Branch | Status |\n");
        sb.append("|-----------|---------|-----|--------|--------|\n");
        for (var snap : snapshots) {
            sb.append("| ").append(snap.name())
                    .append(" | ").append(snap.version())
                    .append(" | ").append(snap.shortSha())
                    .append(" | ").append(snap.branch())
                    .append(" | ✓ |\n");
        }
        for (String name : absent) {
            sb.append("| ").append(name)
                    .append(" | — | — | — | not cloned |\n");
        }
        return sb.toString();
    }

    // ── YAML generation (pure, static, testable) ──────────────────────

    /**
     * Build checkpoint YAML content from pre-gathered subproject data.
     *
     * @param name          the checkpoint name
     * @param timestamp     the ISO-UTC creation timestamp
     * @param author        the author who created the checkpoint
     * @param schemaVersion the workspace manifest schema version
     * @param snapshots     the per-subproject snapshot records
     * @param absentNames   names of components not present on disk
     * @return the checkpoint YAML content as a string
     */
    public static String buildCheckpointYaml(String name, String timestamp,
                                              String author, String schemaVersion,
                                              List<SubprojectSnapshot> snapshots,
                                              List<String> absentNames) {
        List<String> yaml = new ArrayList<>();
        yaml.add("# IKE Workspace Checkpoint");
        yaml.add("# Generated by: mvn ws:checkpoint-publish");
        yaml.add("#");
        yaml.add("checkpoint:");
        yaml.add("  name: \"" + name + "\"");
        yaml.add("  created: \"" + timestamp + "\"");
        yaml.add("  author: \"" + author + "\"");
        yaml.add("  schema-version: \"" + schemaVersion + "\"");
        yaml.add("");
        yaml.add("  subprojects:");

        for (String absent : absentNames) {
            yaml.add("    " + absent + ":");
            yaml.add("      status: absent");
        }

        for (SubprojectSnapshot snap : snapshots) {
            yaml.add("    " + snap.name() + ":");
            if (snap.version() != null) {
                yaml.add("      version: \"" + snap.version() + "\"");
            }
            yaml.add("      sha: \"" + snap.sha() + "\"");
            yaml.add("      short-sha: \"" + snap.shortSha() + "\"");
            yaml.add("      branch: \"" + snap.branch() + "\"");
            yaml.add("      type: " + snap.type());
            if (snap.compositeCheckpoint()) {
                yaml.add("      # TODO: add view-coordinate from Tinkar runtime");
            }
        }

        return String.join("\n", yaml) + "\n";
    }

    /**
     * Derive the checkpoint file name from the checkpoint name.
     *
     * @param checkpointName the checkpoint name
     * @return the filename in the form {@code checkpoint-<name>.yaml}
     */
    public static String checkpointFileName(String checkpointName) {
        return "checkpoint-" + checkpointName + ".yaml";
    }

    // ── Private helpers ────────────────────────────────────────────────

    private String gitFullSha(File dir) {
        try {
            return ReleaseSupport.execCapture(dir, "git", "rev-parse", "HEAD");
        } catch (MojoException e) {
            return "unknown";
        }
    }

    private String readVersion(File dir) throws MojoException {
        return ReleaseSupport.readPomVersion(new File(dir, "pom.xml"));
    }

    private String resolveAuthor(File root) {
        try {
            return ReleaseSupport.execCapture(root, "git", "config", "user.name");
        } catch (MojoException e) {
            return System.getProperty("user.name", "unknown");
        }
    }

    private String snapshotTestingContext(WorkspaceGraph graph)
            throws MojoException {
        if (issueRepo == null || issueRepo.isBlank()) return null;

        String milestoneName = milestone;

        if (milestoneName == null || milestoneName.isBlank()) {
            var components = graph.manifest().subprojects();
            if (!components.isEmpty()) {
                var first = components.entrySet().iterator().next();
                String subName = first.getKey();
                String version = first.getValue().version();
                if (version != null) {
                    String releaseVersion = version.replace("-SNAPSHOT", "");
                    milestoneName = subName + " v" + releaseVersion;
                }
            }
        }

        if (milestoneName == null || milestoneName.isBlank()) return null;

        getLog().info("  Querying milestone: " + milestoneName);
        var context = ReleaseNotesSupport.snapshotMilestone(
                issueRepo, milestoneName, getLog());

        if (context == null) {
            getLog().info("  No milestone found — skipping testing context");
            return null;
        }

        getLog().info("  Testing context: "
                + context.readyToTest().size() + " ready, "
                + context.inProgress().size() + " in progress");

        return context.toYaml("  ");
    }

    private String deriveCheckpointName(File root) throws MojoException {
        String branch = gitBranch(root);
        String safeBranch = branch.replace('/', '-');
        String compactTime = COMPACT_UTC.format(Instant.now());
        return safeBranch + "-" + compactTime;
    }
}
