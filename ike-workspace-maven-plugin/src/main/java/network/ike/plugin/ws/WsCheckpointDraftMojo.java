package network.ike.plugin.ws;

import network.ike.plugin.ReleaseNotesSupport;
import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;

import network.ike.workspace.ManifestWriter;
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
@Mojo(name = "checkpoint-draft", projectRequired = false, aggregator = true)
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

            if (draft) {
                getLog().info(Ansi.green("  ✓ ") + subName
                        + " [" + shortSha + "] " + branch
                        + " (" + version + ")");
                CheckpointSupport.preview(dir, wsTagName, getLog());
                snapshots.add(new SubprojectSnapshot(
                        subName, sha, shortSha, branch, version, false));
            } else {
                CheckpointSupport.checkpoint(dir, wsTagName, getLog());
                getLog().info(Ansi.green("  ✓ ") + subName
                        + " [" + shortSha + "] → " + wsTagName);
                snapshots.add(new SubprojectSnapshot(
                        subName, sha, shortSha, branch, version, false));
            }
        }

        // ── Build checkpoint YAML ──────────────────────────────────────
        String yamlContent = buildCheckpointYaml(
                name, timestamp, author,
                graph.manifest().schemaVersion(),
                snapshots, absentComponents);

        // ── Append testing context from milestone ─────────────────────
        ReleaseNotesSupport.TestingContext testingContext = snapshotTestingContext(graph);
        if (testingContext != null) {
            yamlContent = yamlContent + "\n" + testingContext.toYaml("  ");
        }

        File wsGitDir = new File(root, ".git");
        boolean workspaceHasGit = wsGitDir.exists();

        Path checkpointFile = null;
        boolean manifestUpdated = false;
        boolean tagPushed = false;

        if (draft) {
            getLog().info("");
            getLog().info("[DRAFT] Checkpoint file would be written to:");
            getLog().info("[DRAFT]   checkpoints/" + checkpointFileName(name));
            getLog().info("");
            getLog().info("[DRAFT] Contents:");
            yamlContent.lines().forEach(line ->
                    getLog().info("[DRAFT]   " + line));
            getLog().info("");
        } else {
            // ── Write subproject SHAs into workspace.yaml ────────────────
            try {
                java.util.Map<String, String> shaUpdates = new java.util.LinkedHashMap<>();
                for (SubprojectSnapshot snap : snapshots) {
                    shaUpdates.put(snap.name(), snap.sha());
                }
                ManifestWriter.updateShas(resolveManifest(), shaUpdates);
                manifestUpdated = true;
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
            checkpointFile = checkpointsDir.resolve(checkpointFileName(name));
            try {
                Files.writeString(checkpointFile, yamlContent, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to write " + checkpointFile, e);
            }

            // ── Tag and push workspace aggregator repo ──────────────────
            if (workspaceHasGit) {
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
                    tagPushed = true;
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
        }

        var reportContext = new CheckpointReportContext(
                name, wsTagName, timestamp, author, draft,
                snapshots, absentComponents, yamlContent,
                checkpointFile, workspaceHasGit, manifestUpdated, tagPushed,
                testingContext);
        writeReport(publish ? WsGoal.CHECKPOINT_PUBLISH : WsGoal.CHECKPOINT_DRAFT,
                buildCheckpointMarkdownReport(reportContext));
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

    /**
     * All inputs needed to render the checkpoint markdown report.
     *
     * @param name                the checkpoint name
     * @param wsTagName           the workspace aggregator tag (e.g. {@code checkpoint/<name>})
     * @param checkpointTimestamp ISO-UTC timestamp recorded in the checkpoint
     * @param author              git user.name (or system user) at execution time
     * @param draft               {@code true} for draft mode, {@code false} for publish
     * @param snapshots           per-subproject HEAD snapshots in topological order
     * @param absentComponents    component names declared in the manifest but not on disk
     * @param yamlContent         the full checkpoint YAML body (for the draft preview)
     * @param checkpointFile      path the YAML was written to (publish only; {@code null} in draft)
     * @param workspaceHasGit     whether the workspace aggregator has its own {@code .git}
     * @param manifestUpdated     whether the workspace.yaml SHA write succeeded (publish only)
     * @param tagPushed           whether the workspace tag was pushed to {@code origin} (publish only)
     * @param testingContext      milestone snapshot for testing context, or {@code null} when unavailable
     */
    private record CheckpointReportContext(
            String name,
            String wsTagName,
            String checkpointTimestamp,
            String author,
            boolean draft,
            List<SubprojectSnapshot> snapshots,
            List<String> absentComponents,
            String yamlContent,
            Path checkpointFile,
            boolean workspaceHasGit,
            boolean manifestUpdated,
            boolean tagPushed,
            ReleaseNotesSupport.TestingContext testingContext) {}

    private String buildCheckpointMarkdownReport(CheckpointReportContext ctx) {
        var sb = new StringBuilder();

        sb.append(ctx.snapshots().size()).append(" subproject(s) checkpointed");
        if (!ctx.absentComponents().isEmpty()) {
            sb.append(", ").append(ctx.absentComponents().size()).append(" absent");
        }
        sb.append(ctx.draft() ? " (draft)" : "").append(".\n\n");

        sb.append("## Checkpoint\n\n");
        sb.append("- **Name:** ").append(ctx.name()).append('\n');
        sb.append("- **Tag:** `").append(ctx.wsTagName()).append("`\n");
        sb.append("- **Time:** ").append(ctx.checkpointTimestamp()).append('\n');
        sb.append("- **Author:** ").append(ctx.author()).append('\n');
        sb.append("- **Mode:** ")
                .append(ctx.draft() ? "DRAFT — no tags, no files written" : "PUBLISH")
                .append("\n\n");

        sb.append("## Subprojects\n\n");
        sb.append("| Subproject | Version | SHA | Branch | Status |\n");
        sb.append("|-----------|---------|-----|--------|--------|\n");
        for (var snap : ctx.snapshots()) {
            sb.append("| ").append(snap.name())
                    .append(" | ").append(snap.version())
                    .append(" | `").append(snap.shortSha()).append('`')
                    .append(" | ").append(snap.branch())
                    .append(" | ✓ |\n");
        }
        for (String absentName : ctx.absentComponents()) {
            sb.append("| ").append(absentName)
                    .append(" | — | — | — | not cloned |\n");
        }
        sb.append('\n');

        sb.append("## Outputs\n\n");
        String checkpointPath = "checkpoints/" + checkpointFileName(ctx.name());
        if (ctx.draft()) {
            sb.append("- Checkpoint file `").append(checkpointPath)
                    .append("` would be written.\n");
            if (ctx.workspaceHasGit()) {
                sb.append("- Workspace tag `").append(ctx.wsTagName())
                        .append("` would be created.\n");
            } else {
                sb.append("- No `.git` at workspace root; tag/commit/push would be skipped.\n");
            }
            sb.append("- `workspace.yaml` subproject SHAs would be updated.\n");
        } else {
            sb.append("- Checkpoint file written: `").append(checkpointPath).append("`\n");
            if (ctx.workspaceHasGit()) {
                String tagOutcome = ctx.tagPushed()
                        ? "pushed to `origin`."
                        : "created locally (no `origin` remote — not pushed).";
                sb.append("- Workspace tag `").append(ctx.wsTagName())
                        .append("` ").append(tagOutcome).append('\n');
            } else {
                sb.append("- No `.git` at workspace root; tag/commit/push skipped.\n");
            }
            sb.append("- `workspace.yaml` subproject SHAs ")
                    .append(ctx.manifestUpdated() ? "updated" : "**not updated** (write failed)")
                    .append(".\n");
        }
        sb.append('\n');

        if (ctx.testingContext() != null) {
            sb.append(ctx.testingContext().toMarkdown().stripTrailing()).append("\n\n");
        } else {
            sb.append("## Testing context\n\nNo milestone found — skipping.\n\n");
        }

        if (ctx.draft()) {
            sb.append("## Checkpoint YAML (preview)\n\n");
            sb.append("```yaml\n")
                    .append(ctx.yamlContent().stripTrailing())
                    .append("\n```\n");
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

    private ReleaseNotesSupport.TestingContext snapshotTestingContext(WorkspaceGraph graph)
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

        return context;
    }

    private String deriveCheckpointName(File root) throws MojoException {
        String branch = gitBranch(root);
        String safeBranch = branch.replace('/', '-');
        String compactTime = COMPACT_UTC.format(Instant.now());
        return safeBranch + "-" + compactTime;
    }
}
