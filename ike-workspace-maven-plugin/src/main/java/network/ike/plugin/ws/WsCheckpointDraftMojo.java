package network.ike.plugin.ws;

import network.ike.plugin.ReleaseNotesSupport;
import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;

import network.ike.workspace.ManifestWriter;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkingSet;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    protected WorkspaceReportSpec runGoal() throws MojoException {
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

        // Idempotency guard (#294): in publish mode, exit cleanly when a
        // checkpoint with this name was already created. The checkpoint
        // file in checkpoints/ is the durable success marker; if it
        // exists, re-running would either duplicate-tag (failing at git)
        // or rewrite the file with a fresh timestamp (changing the
        // commit). Either way "running twice = same result" is violated.
        if (publish) {
            Path existingCheckpoint = root.toPath().resolve("checkpoints")
                    .resolve(checkpointFileName(name));
            if (Files.isRegularFile(existingCheckpoint)) {
                getLog().info("");
                getLog().info("  Checkpoint '" + name
                        + "' already exists — nothing to do.");
                getLog().info("    " + existingCheckpoint);
                getLog().info("  To create a new checkpoint, pass a "
                        + "different -Dname=…, or delete the existing");
                getLog().info("  file (and the matching checkpoint/"
                        + name + " tag in each subproject) first.");
                getLog().info("");
                return new WorkspaceReportSpec(WsGoal.CHECKPOINT_PUBLISH,
                        "Idempotent skip — checkpoint **`" + name
                                + "`** already exists at `"
                                + root.toPath().relativize(existingCheckpoint)
                                + "`.\n");
            }
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
        // Per-subproject closing-keyword trailer issue refs (#394) —
        // report-only, never mutates issue state.
        Map<String, List<ReleaseNotesSupport.IssueRef>> issuesSinceLastRelease =
                new LinkedHashMap<>();

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

            // Collect issue refs from closing-keyword trailers in commits
            // since the last release tag. Pure read — does not close
            // issues or remove pending-release labels (#394).
            List<ReleaseNotesSupport.IssueRef> issues =
                    collectClosingTrailerIssuesSinceLastRelease(dir);
            if (!issues.isEmpty()) {
                issuesSinceLastRelease.put(subName, issues);
            }

            if (draft) {
                getLog().info(Ansi.green("  ✓ ") + subName
                        + " [" + shortSha + "] " + branch
                        + " (" + version + ")"
                        + (issues.isEmpty() ? ""
                            : " — " + issues.size() + " issue(s) since last release"));
                CheckpointSupport.preview(dir, wsTagName, getLog());
                snapshots.add(new SubprojectSnapshot(
                        subName, sha, shortSha, branch, version, false));
            } else {
                CheckpointSupport.checkpoint(dir, wsTagName, getLog());
                getLog().info(Ansi.green("  ✓ ") + subName
                        + " [" + shortSha + "] → " + wsTagName
                        + (issues.isEmpty() ? ""
                            : " — " + issues.size() + " issue(s) since last release"));
                snapshots.add(new SubprojectSnapshot(
                        subName, sha, shortSha, branch, version, false));
            }
        }

        // ── Build checkpoint YAML ──────────────────────────────────────
        String yamlContent = buildCheckpointYaml(
                name, timestamp, author,
                graph.manifest().schemaVersion(),
                snapshots, absentComponents, issuesSinceLastRelease);

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
            getLog().info("  [DRAFT] Publish would build the reactor "
                    + "(ws.checkpoint.verifyGoals) before tagging and refuse "
                    + "to cut on failure (#689).");
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
            for (Map.Entry<String, Subproject> entry : graph.manifest().subprojects().entrySet()) {
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

        // Resolve the working set so the report renders one row per member
        // INCLUDING the aggregator (workspace root). The per-subproject
        // snapshots above (which drive the YAML) never carried the
        // aggregator, so a subproject-only table hid the root's state — the
        // #763 gap this checkpoint report (#766) exists to close. Gather the
        // aggregator's own version/branch the same way as a subproject; its
        // root directory always exists, so reading its POM version is the
        // #763 fix.
        WorkingSet workingSet = resolveWorkingSet();
        String aggregatorVersion = readVersionOrNull(root);
        String aggregatorBranch = workspaceHasGit ? gitBranch(root) : null;

        CheckpointReportContext reportContext = new CheckpointReportContext(
                name, wsTagName, timestamp, author, draft,
                snapshots, absentComponents, yamlContent,
                checkpointFile, workspaceHasGit, manifestUpdated, tagPushed,
                testingContext, issuesSinceLastRelease,
                workingSet, aggregatorVersion, aggregatorBranch);
        return new WorkspaceReportSpec(
                publish ? WsGoal.CHECKPOINT_PUBLISH : WsGoal.CHECKPOINT_DRAFT,
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
     * @param workingSet          the resolved working set — its {@code members()} are the subprojects
     *                            then the aggregator (workspace root), so the report table can render
     *                            the aggregator row the per-subproject snapshots omit (#763/#766)
     * @param aggregatorVersion   the workspace root's POM version (the #763 fix — always gathered)
     * @param aggregatorBranch    the workspace root's current branch, or {@code null} when it has no git
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
            ReleaseNotesSupport.TestingContext testingContext,
            Map<String, List<ReleaseNotesSupport.IssueRef>> issuesSinceLastRelease,
            WorkingSet workingSet,
            String aggregatorVersion,
            String aggregatorBranch) {}

    private String buildCheckpointMarkdownReport(CheckpointReportContext ctx) {
        GoalReportBuilder report = new GoalReportBuilder();

        StringBuilder lead = new StringBuilder();
        lead.append(ctx.snapshots().size()).append(" subproject(s) checkpointed");
        if (!ctx.absentComponents().isEmpty()) {
            lead.append(", ").append(ctx.absentComponents().size()).append(" absent");
        }
        lead.append(ctx.draft() ? " (draft)" : "").append(".");
        report.paragraph(lead.toString());

        report.section("Checkpoint")
                .bullet("**Name:** " + ctx.name())
                .bullet("**Tag:** `" + ctx.wsTagName() + "`")
                .bullet("**Time:** " + ctx.checkpointTimestamp())
                .bullet("**Author:** " + ctx.author())
                .bullet("**Mode:** " + (ctx.draft()
                        ? "DRAFT — no tags, no files written" : "PUBLISH"));

        renderWorkingSetTable(report, ctx);

        report.section("Outputs");
        String checkpointPath = "checkpoints/" + checkpointFileName(ctx.name());
        if (ctx.draft()) {
            report.bullet("Checkpoint file `" + checkpointPath
                    + "` would be written.");
            if (ctx.workspaceHasGit()) {
                report.bullet("Workspace tag `" + ctx.wsTagName()
                        + "` would be created.");
            } else {
                report.bullet("No `.git` at workspace root; "
                        + "tag/commit/push would be skipped.");
            }
            report.bullet("`workspace.yaml` subproject SHAs would be updated.");
        } else {
            report.bullet("Checkpoint file written: `" + checkpointPath + "`");
            if (ctx.workspaceHasGit()) {
                String tagOutcome = ctx.tagPushed()
                        ? "pushed to `origin`."
                        : "created locally (no `origin` remote — not pushed).";
                report.bullet("Workspace tag `" + ctx.wsTagName()
                        + "` " + tagOutcome);
            } else {
                report.bullet("No `.git` at workspace root; "
                        + "tag/commit/push skipped.");
            }
            report.bullet("`workspace.yaml` subproject SHAs "
                    + (ctx.manifestUpdated()
                            ? "updated" : "**not updated** (write failed)")
                    + ".");
        }

        if (ctx.testingContext() != null) {
            report.raw(ctx.testingContext().toMarkdown().stripTrailing()
                    + "\n\n");
        } else {
            report.section("Testing context")
                    .paragraph("No milestone found — skipping.");
        }

        // #394: report issues referenced by closing trailers in commits
        // since each subproject's last release tag. Report-only — checkpoint
        // never closes issues or removes pending-release labels.
        report.section("Issues since last release");
        if (ctx.issuesSinceLastRelease().isEmpty()) {
            report.paragraph("No closing-trailer issue references found in "
                    + "commits since the last release tag in each subproject.");
        } else {
            report.paragraph("Per-subproject `Fixes`/`Closes`/`Resolves` "
                    + "trailers in commits since the last `v*` tag. **This "
                    + "checkpoint does not close any of these issues** — they "
                    + "remain in their current state until an actual release "
                    + "ships.");
            for (Map.Entry<String, List<ReleaseNotesSupport.IssueRef>> entry : ctx.issuesSinceLastRelease().entrySet()) {
                report.section(entry.getKey());
                for (ReleaseNotesSupport.IssueRef ref : entry.getValue()) {
                    report.bullet(ref.repo() + "#" + ref.number());
                }
            }
        }

        if (ctx.draft()) {
            report.section("Checkpoint YAML (preview)")
                    .codeBlock("yaml", ctx.yamlContent().stripTrailing());
        }

        return report.build();
    }

    /**
     * Render the shared working-set table for the checkpoint report —
     * one {@link WorkingSetReportTable.Row} per working-set member, the
     * aggregator (workspace root) INCLUDED.
     *
     * <p>The per-subproject {@link SubprojectSnapshot snapshots} that drive
     * the checkpoint YAML never carried the aggregator, so the table this
     * replaces hid the workspace root entirely — the staleness #763 describes.
     * Iterating {@link WorkingSet#members()} (subprojects then the aggregator)
     * makes the root a first-class row, and gathering its POM version from the
     * always-present root directory is the #763 fix (#766).
     *
     * <p>The {@code Effect} column states what the checkpoint did or will do to
     * each member — phrased as <em>planned</em> in a draft, <em>applied</em> in
     * a publish. Subprojects are tagged at their current HEAD; the aggregator is
     * committed (recording the set) and tagged, so it cannot cite its own
     * not-yet-made SHA and uses the {@link WorkingSetReportTable#SELF_COMMIT}
     * sentinel.
     *
     * @param report the report builder to append the table to
     * @param ctx    the gathered checkpoint report context
     */
    private void renderWorkingSetTable(GoalReportBuilder report,
                                       CheckpointReportContext ctx) {
        Map<String, SubprojectSnapshot> byName = new LinkedHashMap<>();
        for (SubprojectSnapshot snap : ctx.snapshots()) {
            byName.put(snap.name(), snap);
        }
        Set<String> absent = new LinkedHashSet<>(ctx.absentComponents());

        List<WorkingSetReportTable.Row> rows = new ArrayList<>();
        for (WorkingSet.Member member : ctx.workingSet().members()) {
            if (member.isAggregator()) {
                rows.add(new WorkingSetReportTable.Row(
                        member,
                        ctx.aggregatorVersion(),
                        ctx.aggregatorBranch(),
                        WorkingSetReportTable.SELF_COMMIT,
                        aggregatorEffect(ctx)));
                continue;
            }
            String memberName = member.name();
            if (absent.contains(memberName)) {
                rows.add(new WorkingSetReportTable.Row(
                        member, null, null, null,
                        "skipped (not cloned)"));
                continue;
            }
            SubprojectSnapshot snap = byName.get(memberName);
            if (snap == null) {
                // A declared member with neither a snapshot nor an absent
                // marker — render what we know rather than dropping the row.
                rows.add(new WorkingSetReportTable.Row(
                        member, null, null, null, "skipped (no-op)"));
                continue;
            }
            rows.add(new WorkingSetReportTable.Row(
                    member, snap.version(), snap.branch(), snap.shortSha(),
                    subprojectEffect(ctx)));
        }

        WorkingSetReportTable.render(report, "Working set", rows);
    }

    /**
     * The {@code Effect} cell for a tagged subproject — planned in a draft,
     * applied in a publish.
     *
     * @param ctx the checkpoint report context
     * @return the effect phrase, e.g. {@code "tag checkpoint/<name> (planned)"}
     */
    private static String subprojectEffect(CheckpointReportContext ctx) {
        return ctx.draft()
                ? "tag `" + ctx.wsTagName() + "` (planned)"
                : "tagged `" + ctx.wsTagName() + "`";
    }

    /**
     * The {@code Effect} cell for the aggregator (workspace root) — it is
     * committed (recording the set) and tagged, with the outcome reflecting
     * draft vs. publish and whether the root has git / an {@code origin}.
     *
     * @param ctx the checkpoint report context
     * @return the effect phrase for the aggregator row
     */
    private static String aggregatorEffect(CheckpointReportContext ctx) {
        if (!ctx.workspaceHasGit()) {
            return "skipped (no `.git` at root)";
        }
        if (ctx.draft()) {
            return "commit + tag `" + ctx.wsTagName() + "` (planned)";
        }
        return ctx.tagPushed()
                ? "committed + tagged + pushed"
                : "committed + tagged (no `origin`)";
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
                                              List<String> absentNames,
                                              Map<String, List<ReleaseNotesSupport.IssueRef>>
                                                      issuesSinceLastRelease) {
        List<String> yaml = new ArrayList<>();
        yaml.add("# IKE Workspace Checkpoint");
        yaml.add("# Generated by: mvn " + WsGoal.CHECKPOINT_PUBLISH.qualified());
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
            List<ReleaseNotesSupport.IssueRef> refs =
                    issuesSinceLastRelease.get(snap.name());
            if (refs != null && !refs.isEmpty()) {
                yaml.add("      issues-since-last-release:");
                for (ReleaseNotesSupport.IssueRef ref : refs) {
                    yaml.add("        - \"" + ref.repo() + "#"
                            + ref.number() + "\"");
                }
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

    /**
     * Read a directory's POM version for the report's working-set table,
     * returning {@code null} instead of throwing when the POM is absent or
     * unreadable. Used to gather the aggregator (workspace root) version —
     * the #763 fix surfaces the root as a table row, so a root without a
     * readable POM must render an empty version cell rather than aborting
     * the whole checkpoint.
     *
     * @param dir the directory whose {@code pom.xml} version to read
     * @return the POM version, or {@code null} when it cannot be read
     */
    private String readVersionOrNull(File dir) {
        try {
            return readVersion(dir);
        } catch (MojoException e) {
            return null;
        }
    }

    /**
     * Collect issue references from closing-keyword trailers
     * ({@code Fixes}, {@code Closes}, {@code Resolves} and grammatical
     * variants) in commits since the given subproject's last release
     * tag (matching {@code v*}). Returns an empty list when no
     * previous release tag is reachable, when no commits are in the
     * range, or when {@code git} or the parser fails.
     *
     * <p>Per IKE-Network/ike-issues#394: this is a <em>report-only</em>
     * collection. The checkpoint never closes any of these issues and
     * never removes {@code pending-release} labels — that's reserved
     * for actual releases ({@link ReleaseNotesSupport#removePendingReleaseLabels}).
     *
     * @param subDir the subproject's git working tree
     * @return ordered set of unique issue references, empty when none
     */
    private List<ReleaseNotesSupport.IssueRef>
            collectClosingTrailerIssuesSinceLastRelease(File subDir) {
        try {
            String previousTag = ReleaseSupport.execCapture(subDir,
                    "git", "describe", "--tags", "--abbrev=0",
                    "--match", "v*", "HEAD");
            if (previousTag == null || previousTag.isBlank()) {
                return List.of();
            }
            String body = ReleaseSupport.execCapture(subDir,
                    "git", "log",
                    "--format=%B%n--end--", previousTag + "..HEAD");
            Set<ReleaseNotesSupport.IssueRef> refs =
                    ReleaseNotesSupport.parseClosingTrailers(body, null);
            return new ArrayList<>(refs);
        } catch (Exception e) {
            // No previous tag, no commits, or git failure — non-fatal
            // for a checkpoint. Empty list signals "nothing to report."
            return List.of();
        }
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
            Map<String, Subproject> components = graph.manifest().subprojects();
            if (!components.isEmpty()) {
                Map.Entry<String, Subproject> first = components.entrySet().iterator().next();
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
        ReleaseNotesSupport.TestingContext context = ReleaseNotesSupport.snapshotMilestone(
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
