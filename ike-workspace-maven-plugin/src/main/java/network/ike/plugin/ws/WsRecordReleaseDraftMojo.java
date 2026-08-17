package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.preflight.Preflight;
import network.ike.plugin.ws.preflight.PreflightCondition;
import network.ike.plugin.ws.preflight.PreflightContext;
import network.ike.plugin.ws.preflight.PreflightResult;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.ReleaseRecord;
import network.ike.workspace.ReleaseRecordFile;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Preview recording a member's release — the write side of the minimal
 * tag-aligned state semantics (IKE-Network/ike-issues#973, settled
 * 2026-08-10; the read side is state-aware alignment, #972).
 *
 * <p>After a member's {@code ike:release-publish} succeeds, this goal
 * pair records the release in the working set, in one root-repo commit:
 *
 * <ul>
 *   <li><b>workspace.yaml</b> — the member transitions to
 *       {@code state: tag-aligned, kind: release, tag: vN} with its
 *       {@code version:} field pinned at the released version, so
 *       alignment targets the pin and the release cascade excludes the
 *       member.</li>
 *   <li><b>releases/release-&lt;mission&gt;.yaml</b> — the member's row
 *       (version, tag, sha, recorded date) appends to the mission's
 *       record; one file per mission, finalized by the mission's
 *       workspace-root release.</li>
 * </ul>
 *
 * <p>Without {@code -Dmember}, the draft lists un-recorded candidates:
 * checked-out members whose tip {@code v*} tag is not yet reflected by
 * a tag-aligned pin. With {@code -Dmember}, it previews the exact
 * transition. {@code ws:record-release-publish} requires both
 * {@code -Dmember} and {@code -Dmission} and applies it.
 *
 * <pre>{@code
 * mvn ws:record-release-draft                                  # list candidates
 * mvn ws:record-release-draft -Dmember=komet-bom               # preview
 * mvn ws:record-release-publish -Dmember=komet-bom -Dmission=komet-wsr-1
 * }</pre>
 *
 * @see WsRecordReleasePublishMojo
 */
@Mojo(name = "record-release-draft", projectRequired = false, aggregator = true)
public class WsRecordReleaseDraftMojo extends AbstractWorkspaceMojo {

    /** Path-safe mission labels: no separators, no traversal. */
    private static final Pattern MISSION_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * The subproject whose release is being recorded. Required for
     * publish; optional for draft (omitted, the draft lists candidates).
     */
    @Parameter(property = "member")
    String member;

    /**
     * The release-mission label naming the record file
     * ({@code releases/release-<mission>.yaml}), e.g. {@code komet-wsr-1}.
     * Required for publish.
     */
    @Parameter(property = "mission")
    String mission;

    /**
     * Deprecated spelling of {@link #mission} from before the
     * cycle-to-mission rename (IKE-Network/ike-issues#1038). Honoured
     * with a deprecation warning when {@code -Dmission} is absent;
     * removed after one platform generation.
     */
    @Parameter(property = "cycle")
    String missionDeprecatedCycle;

    /**
     * When true, apply the transition; when false (default), preview.
     * Package-private so {@link WsRecordReleasePublishMojo} can flip it.
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Creates this goal instance. */
    public WsRecordReleaseDraftMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if ((mission == null || mission.isBlank())
                && missionDeprecatedCycle != null
                && !missionDeprecatedCycle.isBlank()) {
            getLog().warn("-Dcycle is deprecated — the release iteration"
                    + " is a mission; use -Dmission (ike-issues#1038).");
            mission = missionDeprecatedCycle;
        }
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        WsGoal goal = publish ? WsGoal.RECORD_RELEASE_PUBLISH
                              : WsGoal.RECORD_RELEASE_DRAFT;

        getLog().info("");
        getLog().info("IKE Workspace Record Release — pin a released member");
        getLog().info("══════════════════════════════════════════════════════════════");
        if (!publish) {
            getLog().info("  (draft — no files will be modified)");
        }
        getLog().info("");

        if (member == null || member.isBlank()) {
            if (publish) {
                throw new MojoException(
                        "-Dmember is required for " + goal.qualified()
                        + " — name the released subproject to record.");
            }
            return new WorkspaceReportSpec(goal, candidateReport(graph, root));
        }

        Subproject subproject = graph.manifest().subprojects().get(member);
        if (subproject == null) {
            throw new MojoException("No subproject '" + member
                    + "' in workspace.yaml.");
        }
        File memberDir = new File(root, member);
        if (!memberDir.isDirectory()
                || !new File(memberDir, ".git").exists()) {
            throw new MojoException("Subproject '" + member
                    + "' is not checked out — cannot read its release tag.");
        }

        String tag = latestReleaseTag(memberDir);
        if (tag == null) {
            throw new MojoException("Subproject '" + member
                    + "' has no v* release tag — release it first"
                    + " (ike:release-publish), then record.");
        }
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (version.isBlank() || version.endsWith("-SNAPSHOT")) {
            throw new MojoException("Tip release tag '" + tag + "' of '"
                    + member + "' does not name a released version.");
        }
        String sha = tagCommitSha(memberDir, tag);

        boolean alreadyPinned = subproject.isTagAligned()
                && tag.equals(subproject.tag())
                && version.equals(subproject.version());

        StringBuilder report = new StringBuilder();
        report.append("## Record release — ").append(member).append("\n\n");
        report.append("| | current | target |\n|---|---|---|\n");
        report.append("| state | ")
                .append(subproject.state() == null
                        ? Subproject.STATE_SNAPSHOT : subproject.state())
                .append(" | ").append(Subproject.STATE_TAG_ALIGNED)
                .append(" |\n");
        report.append("| kind | ").append(nullDash(subproject.kind()))
                .append(" | ").append(Subproject.KIND_RELEASE).append(" |\n");
        report.append("| version | ").append(nullDash(subproject.version()))
                .append(" | ").append(version).append(" |\n");
        report.append("| tag | ").append(nullDash(subproject.tag()))
                .append(" | ").append(tag).append(" |\n");
        report.append("| sha | | ").append(sha).append(" |\n\n");

        if (alreadyPinned) {
            getLog().info("  ✓ " + member + " already pinned at " + tag);
        } else {
            getLog().info("  " + member + ": "
                    + nullDash(subproject.version()) + " → " + version
                    + " (pinned " + tag + ")");
        }

        if (!publish) {
            String missionShown = (mission == null || mission.isBlank())
                    ? "<mission>" : mission;
            report.append("Apply with:\n\n```\nmvn ")
                    .append(WsGoal.RECORD_RELEASE_PUBLISH.qualified())
                    .append(" -Dmember=").append(member)
                    .append(" -Dmission=").append(missionShown)
                    .append("\n```\n");
            return new WorkspaceReportSpec(goal, report.toString());
        }

        // ── Publish ────────────────────────────────────────────────────
        if (mission == null || mission.isBlank()) {
            throw new MojoException("-Dmission is required for "
                    + goal.qualified() + " — e.g. -Dmission=komet-wsr-1.");
        }
        if (!MISSION_PATTERN.matcher(mission).matches()) {
            throw new MojoException("Invalid mission label '" + mission
                    + "' — use letters, digits, dot, dash, underscore.");
        }

        Path manifestPath = resolveManifest();
        Path recordPath = ReleaseRecordFile.pathFor(root.toPath(), mission);
        String recordRelPath = root.toPath().relativize(recordPath)
                .toString();

        // Root-scoped preflight (#780): this goal writes only root-repo
        // files, so only the root tree must be unmodified. Gated the
        // standard way: -Dallow-uncommitted relaxes, -Ddefer-commit hands
        // the clean-state guarantee to a cascade caller.
        PreflightResult preflight = Preflight.of(
                List.of(PreflightCondition.WORKING_TREE_CLEAN),
                PreflightContext.of(root, null, List.of()));
        if (allowUncommitted() || deferCommit()) {
            preflight.warnIfFailed(getLog(), goal);
        } else {
            preflight.requirePassed(goal);
        }

        String today = LocalDate.now().toString();
        GoalAuthoredChanges authored = GoalAuthoredChanges.snapshot(
                root, getLog(), "workspace.yaml", recordRelPath);
        try {
            ManifestWriter.recordRelease(manifestPath, member, version, tag);
            getLog().info("  ✓ workspace.yaml — " + member
                    + " pinned tag-aligned at " + tag);

            ReleaseRecord record = Files.exists(recordPath)
                    ? ReleaseRecordFile.read(recordPath)
                    : ReleaseRecord.start(mission, today);
            record = record.withMember(member,
                    new ReleaseRecord.MemberRelease(version, tag, sha, today));
            ReleaseRecordFile.write(recordPath, record);
            getLog().info("  ✓ " + recordRelPath + " — row for "
                    + member + " " + version);
        } catch (IOException e) {
            throw new MojoException("Failed to record release: "
                    + e.getMessage(), e);
        }

        if (!deferCommit()) {
            if (authored.commitAuthored("workspace: record release "
                    + member + " " + version
                    + "\n\nRefs: IKE-Network/ike-issues#973")) {
                getLog().info("  ✓ committed workspace.yaml + "
                        + recordRelPath);
            }
        }

        report.append("Recorded in mission `").append(mission).append("` (")
                .append(recordRelPath).append(").\n");
        return new WorkspaceReportSpec(goal, report.toString());
    }

    /**
     * Draft-mode candidate listing: checked-out members whose tip
     * {@code v*} tag is not yet reflected by a tag-aligned pin. Emits
     * the copy-pasteable publish command per candidate.
     *
     * @param graph the workspace graph
     * @param root  the workspace root directory
     * @return the report markdown
     */
    private String candidateReport(WorkspaceGraph graph, File root) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Subproject> entry
                : graph.manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File dir = new File(root, name);
            if (!dir.isDirectory() || !new File(dir, ".git").exists()) {
                continue;
            }
            String tag = latestReleaseTag(dir);
            if (tag == null) continue;
            boolean pinned = subproject.isTagAligned()
                    && tag.equals(subproject.tag());
            if (pinned) continue;
            lines.add(name + " — tip tag " + tag);
        }

        StringBuilder report = new StringBuilder();
        report.append("## Record release — candidates\n\n");
        if (lines.isEmpty()) {
            getLog().info("  ✓ no un-recorded releases"
                    + " (no member has a v* tag beyond its pin)");
            report.append("No un-recorded releases.\n");
            return report.toString();
        }
        report.append("Members with a v* release tag not yet recorded:\n\n");
        for (String line : lines) {
            getLog().info("  ⚠ " + line);
            report.append("- ").append(line).append("\n");
        }
        report.append("\nRecord one with:\n\n```\nmvn ")
                .append(WsGoal.RECORD_RELEASE_PUBLISH.qualified())
                .append(" -Dmember=<name> -Dmission=<mission>\n```\n");
        return report.toString();
    }

    /**
     * The member's tip release tag by version sort, or null when the
     * member has no {@code v*} tag. Mirrors the release goals' notion of
     * "latest release".
     *
     * @param dir the member's checkout directory
     * @return the tip tag, e.g. {@code v3.0.7}, or null
     */
    private static String latestReleaseTag(File dir) {
        try {
            String tags = ReleaseSupport.execCapture(dir,
                    "git", "tag", "-l", "v*", "--sort=-version:refname");
            if (tags == null || tags.isBlank()) return null;
            return tags.lines().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The commit a tag points at (peeled), for the record row.
     *
     * @param dir the member's checkout directory
     * @param tag the release tag
     * @return the full commit sha
     * @throws MojoException when the tag cannot be resolved
     */
    private static String tagCommitSha(File dir, String tag) {
        try {
            String sha = ReleaseSupport.execCapture(dir,
                    "git", "rev-list", "-n", "1", tag);
            if (sha == null || sha.isBlank()) {
                throw new MojoException("Could not resolve commit for tag '"
                        + tag + "' in " + dir.getName());
            }
            return sha.trim();
        } catch (MojoException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoException("Could not resolve commit for tag '"
                    + tag + "' in " + dir.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Render a possibly-null field value for the transition table.
     *
     * @param value the field value
     * @return the value, or an em-dash placeholder when null
     */
    private static String nullDash(String value) {
        return value == null ? "—" : value;
    }
}
