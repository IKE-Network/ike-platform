package network.ike.plugin.ws;

import network.ike.plugin.PomRewriter;
import network.ike.plugin.ReleaseSupport;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Walk the canonical IKE foundation cascade in topological order and
 * invoke {@code ike:release-publish} on each repo that has unreleased
 * changes. ike-issues#375.
 *
 * <p>The foundation cascade is structural: {@code ike-tooling →
 * ike-docs → ike-platform → workspace consumers}. Each upstream repo
 * must release (with its Nexus deploy complete) before the next can
 * pick up the new property versions in its catch-up alignment step.
 * Before this goal, an operator typed three {@code mvn ike:release-
 * publish} invocations in three different directories. This goal does
 * the same walk but cleanly: each foundation is checked for unreleased
 * changes via the same git logic ws:release-publish uses, releases are
 * skipped when not needed, and the report at the end states exactly
 * what happened.
 *
 * <p>Foundation repos are located as siblings of the current workspace
 * (default {@code ~/ike-dev/&lt;name&gt;/}). The starting directory's
 * parent is the search root; non-checked-out foundations are skipped
 * with a clear "not found" entry in the report.
 *
 * <p>After the foundation cascade succeeds, this goal also runs
 * {@code ws:release-publish} on the current workspace by default —
 * the operator is already in a workspace and almost always wants the
 * whole loop closed (foundations + workspace) rather than foundations
 * only. Pass {@code -DskipWorkspace=true} for the rarer
 * foundation-only case (e.g., releasing foundations so a sibling
 * workspace can pick them up, without touching this workspace yet).
 *
 * <p>No draft variant: foundation releases already have
 * {@code ike:release-draft} individually if the operator wants a
 * preview of a specific repo before kicking off the cascade.
 *
 * <p>Usage:
 * <pre>
 * # Full loop: release foundations that have unreleased changes,
 * # then release the workspace (default behavior):
 * mvn ws:cascade-foundation-publish
 *
 * # Foundations only (don't touch the workspace):
 * mvn ws:cascade-foundation-publish -DskipWorkspace=true
 *
 * # Override the foundation set (rarely useful):
 * mvn ws:cascade-foundation-publish -Dfoundations=ike-tooling,ike-docs
 * </pre>
 */
@Mojo(name = "cascade-foundation-publish",
      projectRequired = false,
      aggregator = true)
public class WsCascadeFoundationPublishMojo extends AbstractWorkspaceMojo {

    /**
     * Comma-separated list of foundation repos to walk, in topological
     * order. Defaults to the canonical IKE foundation cascade.
     */
    @Parameter(property = "foundations",
               defaultValue = "ike-tooling,ike-docs,ike-platform")
    String foundations;

    /**
     * Directory containing the foundation repos. Defaults to the
     * parent of the current workspace, which matches the standard
     * {@code ~/ike-dev/&lt;name&gt;/} layout.
     */
    @Parameter(property = "foundationsDir")
    File foundationsDir;

    /**
     * Skip the workspace's own {@code ws:release-publish} step at the
     * end of the cascade. Defaults to {@code false} — the operator
     * almost always wants the whole loop closed (foundations +
     * workspace) since they're invoking from inside a workspace.
     *
     * <p>Pass {@code -DskipWorkspace=true} for the foundation-only
     * case (e.g., releasing foundations so a sibling workspace can
     * pick them up, without touching this workspace yet).
     *
     * <p>Replaces the inverted {@code alsoReleaseWorkspace} parameter
     * (default false) — that default served the rare case, this one
     * serves the common case.
     */
    @Parameter(property = "skipWorkspace", defaultValue = "false")
    boolean skipWorkspace;

    /**
     * Forwarded to {@code ike:release-publish} on each foundation
     * (and to {@code ws:release-publish} when chained). Defaults to
     * {@code true}; pass {@code -DpushRelease=false} for a local-only
     * dry run that stops before pushing.
     */
    @Parameter(property = "pushRelease", defaultValue = "true")
    boolean pushRelease;

    /** Creates this goal instance. */
    public WsCascadeFoundationPublishMojo() {}

    @Override
    public void execute() throws MojoException {
        File wsRoot = workspaceRoot();
        File baseDir = foundationsDir != null
                ? foundationsDir
                : wsRoot.getParentFile();
        if (baseDir == null) {
            throw new MojoException(
                    "Could not determine foundations base directory. "
                            + "Workspace root has no parent. Pass "
                            + "-DfoundationsDir=<path>.");
        }

        List<String> names = Arrays.stream(foundations.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        getLog().info("");
        getLog().info("IKE Foundation Cascade");
        getLog().info("══════════════════════");
        getLog().info("  Base directory:  " + baseDir);
        getLog().info("  Foundations:     " + String.join(", ", names));
        getLog().info("  pushRelease:     " + pushRelease);
        getLog().info("  skipWorkspace:   " + skipWorkspace);
        getLog().info("");

        List<Outcome> outcomes = new ArrayList<>();
        // Track this-cycle releases so downstream foundations bump
        // their <X.version> properties to the just-shipped version
        // before they release. Mirrors ws:release-publish's catch-up
        // alignment for workspace subprojects (#375 followup).
        Map<String, String> releasedVersions = new LinkedHashMap<>();
        for (String name : names) {
            File dir = new File(baseDir, name);
            Outcome outcome = walkOne(dir, name, baseDir, names,
                    releasedVersions);
            outcomes.add(outcome);
            if (outcome.kind == OutcomeKind.RELEASED && outcome.releasedAs != null) {
                releasedVersions.put(name, outcome.releasedAs);
            }
            if (outcome.kind == OutcomeKind.FAILED) {
                reportAndMaybeFail(outcomes, name, baseDir);
            }
        }

        // Workspace cascade — default-on, opt out via -DskipWorkspace=true.
        // Only runs if all foundations cleared (any failure aborts above).
        Outcome wsOutcome = null;
        if (!skipWorkspace) {
            getLog().info("");
            getLog().info("─── Workspace cascade ────────────────────────────");
            wsOutcome = invokeWorkspaceRelease(wsRoot);
            outcomes.add(wsOutcome);
        }

        reportFinal(outcomes);
        if (wsOutcome != null && wsOutcome.kind == OutcomeKind.FAILED) {
            throw new MojoException(
                    "Foundation cascade succeeded but workspace release "
                            + "failed. " + wsOutcome.detail
                            + ". ike-issues#375.");
        }
    }

    /**
     * Process one foundation: detect git state, align upstream-version
     * properties, optionally release.
     *
     * @param dir              foundation repo directory (may not exist)
     * @param name             foundation repo name (used in messages)
     * @param baseDir          directory containing all foundations
     * @param cascade          full cascade order — upstream candidates
     *                         are the entries before {@code name}
     * @param releasedVersions versions released earlier in this cycle,
     *                         indexed by foundation name
     * @return outcome capturing what happened
     */
    Outcome walkOne(File dir, String name, File baseDir,
                     List<String> cascade,
                     Map<String, String> releasedVersions) {
        getLog().info("─── " + name + " ─────────────────────────────────────");
        if (!dir.isDirectory()) {
            getLog().info("  Not checked out at " + dir);
            getLog().info("  Skipping (downstream still picks up its "
                    + "released versions from Nexus).");
            getLog().info("");
            return Outcome.skipped(name, "not checked out at " + dir);
        }
        if (!new File(dir, ".git").exists()) {
            getLog().info("  No .git at " + dir);
            getLog().info("  Skipping.");
            getLog().info("");
            return Outcome.skipped(name, "no .git at " + dir);
        }
        if (!new File(dir, "pom.xml").exists()) {
            getLog().info("  No pom.xml at " + dir);
            getLog().info("  Skipping.");
            getLog().info("");
            return Outcome.skipped(name, "no pom.xml at " + dir);
        }

        // Catch-up alignment: bump every upstream <X.version> in this
        // foundation's pom to whatever X has shipped — either earlier
        // in this cycle (via releasedVersions) or in a prior cycle
        // (via X's latest release tag on disk). The alignment commits
        // by itself BECOME meaningful commits, so a foundation that
        // had no other source changes still releases once a property
        // needs catching up.
        alignUpstreamProperties(dir, name, baseDir, cascade,
                releasedVersions);

        // Detect unreleased changes the same way ws:release-publish does:
        // latest release tag + commits since that aren't release-cadence.
        String tag = latestReleaseTag(dir);
        if (tag == null) {
            getLog().info("  Never released — releasing for the first time.");
        } else {
            int meaningful = meaningfulCommitsSinceTag(dir, tag);
            if (meaningful <= 0) {
                getLog().info("  At " + tag
                        + "; no meaningful commits since.");
                getLog().info("  Skipping (already released).");
                getLog().info("");
                return Outcome.upToDate(name, tag);
            }
            getLog().info("  At " + tag + "; " + meaningful
                    + " meaningful commit(s) since.");
        }

        // Read the about-to-release version so the cascade can record
        // it for downstream alignment. ike:release-publish strips
        // -SNAPSHOT from the current pom version to produce the
        // release version.
        String releaseVersion = currentReleaseVersion(dir);

        // Run ike:release-publish.
        getLog().info("  Running mvn ike:release-publish...");
        String mvn = ReleaseSupport.resolveMavenWrapper(dir, getLog())
                .getAbsolutePath();
        try {
            ReleaseSupport.exec(dir, getLog(),
                    mvn, "ike:release-publish",
                    "-DpushRelease=" + pushRelease,
                    "-B");
            getLog().info("  ✓ Released " + name
                    + (releaseVersion != null ? " " + releaseVersion : ""));
            getLog().info("");
            return Outcome.released(name, releaseVersion);
        } catch (Exception e) {
            getLog().error("  ✗ Failed to release " + name + ": "
                    + e.getMessage());
            getLog().info("");
            return Outcome.failed(name, e.getMessage());
        }
    }

    /**
     * Update upstream-foundation {@code <X.version>} properties in
     * {@code dir}/pom.xml to the latest released version of each X.
     * Each property bump that lands a real change is committed
     * individually so the per-bump intent is visible in the git log.
     *
     * <p>Upstream candidates are the foundations earlier in the
     * cascade order. "Latest released version" is the cycle-released
     * version when available (from {@code releasedVersions}), else the
     * tip of {@code v*} tags on the upstream repo, else nothing.
     *
     * <p>Mirrors {@code WsReleaseDraftMojo}'s {@code updateParentVersions}
     * approach: read once, apply all updates, write once, commit once.
     * Uses {@link PomRewriter#updateProperty} so the rewrite goes
     * through OpenRewrite's LST instead of regex.
     *
     * @param dir              foundation repo dir
     * @param name             foundation name
     * @param baseDir          directory containing all foundations
     * @param cascade          full cascade order
     * @param releasedVersions versions released earlier this cycle
     */
    void alignUpstreamProperties(File dir, String name, File baseDir,
                                  List<String> cascade,
                                  Map<String, String> releasedVersions) {
        File pomFile = new File(dir, "pom.xml");
        if (!pomFile.isFile()) return;

        String content;
        try {
            content = Files.readString(pomFile.toPath(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLog().warn("  Could not read pom.xml for alignment: "
                    + e.getMessage());
            return;
        }

        String original = content;
        List<String> bumps = new ArrayList<>();

        for (String upstream : cascade) {
            if (upstream.equals(name)) break; // stop at self
            String target = resolveTargetVersion(upstream, baseDir,
                    releasedVersions);
            if (target == null) continue;
            String propertyName = upstream + ".version";
            String currentValue = extractPropertyValue(content,
                    propertyName);
            if (currentValue == null || target.equals(currentValue)) {
                continue;
            }
            String after = PomRewriter.updateProperty(content,
                    propertyName, target);
            if (!after.equals(content)) {
                content = after;
                bumps.add("<" + propertyName + ">: "
                        + currentValue + " -> " + target);
            }
        }

        if (content.equals(original)) {
            getLog().debug("  Upstream alignment: no bumps needed.");
            return;
        }

        try {
            Files.writeString(pomFile.toPath(), content,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLog().warn("  Could not write aligned pom.xml: "
                    + e.getMessage());
            return;
        }

        getLog().info("  Catch-up alignment:");
        for (String b : bumps) {
            getLog().info("    " + b);
        }

        try {
            ReleaseSupport.exec(dir, getLog(),
                    "git", "add", "pom.xml");
            ReleaseSupport.exec(dir, getLog(),
                    "git", "commit", "-m",
                    "chore: align upstream versions before release");
        } catch (Exception e) {
            getLog().warn("  Alignment commit failed: "
                    + e.getMessage());
        }
    }

    /**
     * Resolve the target version for {@code upstream}: prefer this-
     * cycle release, else the foundation's tip {@code v*} tag.
     */
    static String resolveTargetVersion(String upstream, File baseDir,
                                        Map<String, String> releasedVersions) {
        String fromCycle = releasedVersions.get(upstream);
        if (fromCycle != null) return fromCycle;
        File upstreamDir = new File(baseDir, upstream);
        if (!upstreamDir.isDirectory()) return null;
        String tag = latestReleaseTag(upstreamDir);
        if (tag == null) return null;
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    /**
     * Pure-string extract of a {@code <properties>}-block value by
     * name. Returns {@code null} when the property is absent. Used
     * for the "is this property already at the target version?"
     * pre-check so we don't rewrite-and-commit a no-op.
     */
    static String extractPropertyValue(String pomContent, String propertyName) {
        if (pomContent == null) return null;
        String openTag = "<" + propertyName + ">";
        int open = pomContent.indexOf(openTag);
        if (open < 0) return null;
        int valueStart = open + openTag.length();
        int close = pomContent.indexOf("</" + propertyName + ">",
                valueStart);
        if (close < 0) return null;
        return pomContent.substring(valueStart, close).trim();
    }

    /**
     * Read the current pom version and strip {@code -SNAPSHOT} so the
     * cascade can record what the next release will be tagged as.
     * Returns {@code null} when the pom can't be read.
     */
    static String currentReleaseVersion(File dir) {
        File pomFile = new File(dir, "pom.xml");
        if (!pomFile.isFile()) return null;
        try {
            String content = Files.readString(pomFile.toPath(),
                    StandardCharsets.UTF_8);
            // Skip <parent>...</parent> so the project's own version wins.
            int searchFrom = 0;
            int parentOpen = content.indexOf("<parent>");
            if (parentOpen >= 0) {
                int parentClose = content.indexOf("</parent>", parentOpen);
                if (parentClose > parentOpen) {
                    searchFrom = parentClose + "</parent>".length();
                }
            }
            int open = content.indexOf("<version>", searchFrom);
            if (open < 0) return null;
            int valueStart = open + "<version>".length();
            int close = content.indexOf("</version>", valueStart);
            if (close < 0) return null;
            String v = content.substring(valueStart, close).trim();
            return v.replaceFirst("-SNAPSHOT$", "");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Chain {@code ws:release-publish} on the workspace.
     */
    Outcome invokeWorkspaceRelease(File wsRoot) {
        String mvn = ReleaseSupport.resolveMavenWrapper(wsRoot, getLog())
                .getAbsolutePath();
        try {
            ReleaseSupport.exec(wsRoot, getLog(),
                    mvn, "ws:release-publish",
                    "-Dpush=" + pushRelease,
                    "-B");
            getLog().info("  ✓ Workspace release complete");
            return Outcome.released("(workspace)", null);
        } catch (Exception e) {
            getLog().error("  ✗ Workspace release failed: "
                    + e.getMessage());
            return Outcome.failed("(workspace)", e.getMessage());
        }
    }

    /**
     * Print the summary table and exit with a clear "resume from N"
     * instruction if any foundation failed.
     *
     * @param outcomes outcomes recorded so far
     * @param failedAt the name of the failed foundation
     * @param baseDir  the resolved foundations base dir (NOT the
     *                 {@link #foundationsDir} field, which may be
     *                 null when the default of "parent of workspace"
     *                 is in effect)
     * @throws MojoException always — release fails fast on first
     *                       foundation failure
     */
    void reportAndMaybeFail(List<Outcome> outcomes, String failedAt,
                              File baseDir) throws MojoException {
        reportFinal(outcomes);
        getLog().info("");
        getLog().info("Resume after fixing " + failedAt + ":");
        getLog().info("  cd " + new File(baseDir, failedAt).getAbsolutePath()
                + " && mvn ike:release-publish");
        getLog().info("Then re-run this cascade to continue with the "
                + "remaining foundations.");
        throw new MojoException(
                "Foundation cascade failed at " + failedAt
                        + ". See output above for details. "
                        + "ike-issues#375.");
    }

    /** Print the per-foundation summary table. */
    void reportFinal(List<Outcome> outcomes) {
        getLog().info("");
        getLog().info("Cascade summary:");
        for (Outcome o : outcomes) {
            String marker = switch (o.kind) {
                case RELEASED -> "✓ released";
                case UP_TO_DATE -> "— up to date";
                case SKIPPED -> "— skipped";
                case FAILED -> "✗ FAILED";
            };
            String detail = o.detail == null ? "" : "  (" + o.detail + ")";
            getLog().info("  " + padRight(o.name, 24)
                    + padRight(marker, 16) + detail);
        }
    }

    // ── git state helpers (mirror WsReleaseDraftMojo) ──────────────────

    static String latestReleaseTag(File dir) {
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
     * Match the same release-cadence patterns WsReleaseDraftMojo uses
     * (#347) so a partial-cascade retry doesn't re-release a foundation
     * whose only post-tag commits are bookkeeping from a prior
     * successful attempt.
     */
    static final Pattern RELEASE_CADENCE_PATTERN = Pattern.compile(
            "^(release: set version to .+"
                    + "|release: restore .+"
                    + "|merge: release .+"
                    + "|post-release: bump to .+"
                    + "|site: publish .+"
                    + "|post-release: sync workspace\\.yaml .+)$");

    static int meaningfulCommitsSinceTag(File dir, String tag) {
        try {
            String log = ReleaseSupport.execCapture(dir,
                    "git", "log", tag + "..HEAD",
                    "--pretty=format:%s", "--no-merges");
            if (log == null) return 0;
            String trimmed = log.strip();
            if (trimmed.isEmpty()) return 0;
            int count = 0;
            for (String line : trimmed.split("\n")) {
                if (!RELEASE_CADENCE_PATTERN.matcher(line.strip()).matches()) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /** One foundation's outcome in the cascade summary. */
    enum OutcomeKind { RELEASED, UP_TO_DATE, SKIPPED, FAILED }

    /**
     * Outcome record for one foundation step.
     *
     * @param name        foundation repo name
     * @param kind        what happened
     * @param detail      human-readable explanation; {@code null} when
     *                    RELEASED with no extra context
     * @param releasedAs  the released version, populated only for
     *                    {@link OutcomeKind#RELEASED}; used by the
     *                    cascade to drive downstream property alignment
     */
    record Outcome(String name, OutcomeKind kind, String detail,
                    String releasedAs) {
        static Outcome released(String name, String version) {
            return new Outcome(name, OutcomeKind.RELEASED,
                    version != null ? "v" + version : null, version);
        }
        static Outcome upToDate(String name, String tag) {
            return new Outcome(name, OutcomeKind.UP_TO_DATE,
                    "at " + tag, null);
        }
        static Outcome skipped(String name, String reason) {
            return new Outcome(name, OutcomeKind.SKIPPED, reason, null);
        }
        static Outcome failed(String name, String reason) {
            return new Outcome(name, OutcomeKind.FAILED, reason, null);
        }
    }
}
