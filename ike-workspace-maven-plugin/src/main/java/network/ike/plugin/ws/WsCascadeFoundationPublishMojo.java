package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * <p>This goal does NOT run {@code ws:release-publish} on the workspace
 * itself. Pass {@code -DalsoReleaseWorkspace=true} to chain the
 * workspace cascade after the foundation cascade completes — useful
 * for "release everything" pipelines. Default is off so operators who
 * want a draft-only foundation update don't accidentally trigger the
 * workspace release.
 *
 * <p>No draft variant: foundation releases already have
 * {@code ike:release-draft} individually if the operator wants a
 * preview of a specific repo before kicking off the cascade.
 *
 * <p>Usage:
 * <pre>
 * # From a workspace root, walk and release foundations that have
 * # unreleased changes:
 * mvn ws:cascade-foundation-publish
 *
 * # Same, plus run ws:release-publish on the workspace after:
 * mvn ws:cascade-foundation-publish -DalsoReleaseWorkspace=true
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
     * Chain {@code ws:release-publish} on the current workspace after
     * the foundation cascade completes. Defaults to {@code false} so
     * this goal can be used as a foundation-only release driver.
     */
    @Parameter(property = "alsoReleaseWorkspace", defaultValue = "false")
    boolean alsoReleaseWorkspace;

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
        getLog().info("  alsoReleaseWorkspace: " + alsoReleaseWorkspace);
        getLog().info("");

        List<Outcome> outcomes = new ArrayList<>();
        for (String name : names) {
            File dir = new File(baseDir, name);
            Outcome outcome = walkOne(dir, name);
            outcomes.add(outcome);
            if (outcome.kind == OutcomeKind.FAILED) {
                reportAndMaybeFail(outcomes, name);
            }
        }

        // Optional workspace cascade — only if all foundations cleared.
        Outcome wsOutcome = null;
        if (alsoReleaseWorkspace) {
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
     * Process one foundation: detect git state, optionally release.
     *
     * @param dir  foundation repo directory (may not exist)
     * @param name foundation repo name (used in messages)
     * @return outcome capturing what happened
     */
    Outcome walkOne(File dir, String name) {
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

        // Run ike:release-publish.
        getLog().info("  Running mvn ike:release-publish...");
        String mvn = findMvn(dir);
        try {
            ReleaseSupport.exec(dir, getLog(),
                    mvn, "ike:release-publish",
                    "-DpushRelease=" + pushRelease,
                    "-B");
            getLog().info("  ✓ Released " + name);
            getLog().info("");
            return Outcome.released(name);
        } catch (Exception e) {
            getLog().error("  ✗ Failed to release " + name + ": "
                    + e.getMessage());
            getLog().info("");
            return Outcome.failed(name, e.getMessage());
        }
    }

    /**
     * Chain {@code ws:release-publish} on the workspace.
     */
    Outcome invokeWorkspaceRelease(File wsRoot) {
        String mvn = findMvn(wsRoot);
        try {
            ReleaseSupport.exec(wsRoot, getLog(),
                    mvn, "ws:release-publish",
                    "-Dpush=" + pushRelease,
                    "-B");
            getLog().info("  ✓ Workspace release complete");
            return Outcome.released("(workspace)");
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
     * @throws MojoException always — release fails fast on first
     *                       foundation failure
     */
    void reportAndMaybeFail(List<Outcome> outcomes, String failedAt)
            throws MojoException {
        reportFinal(outcomes);
        getLog().info("");
        getLog().info("Resume after fixing " + failedAt + ":");
        getLog().info("  cd " + foundationsDir + "/" + failedAt
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

    /**
     * Locate {@code mvnw} in {@code dir}; fall back to PATH {@code mvn}.
     * Mirrors {@link WsReleaseDraftMojo#findMvn} so both goals invoke
     * mvn the same way.
     */
    static String findMvn(File dir) {
        File mvnw = new File(dir, "mvnw");
        if (mvnw.exists() && mvnw.canExecute()) {
            return mvnw.getAbsolutePath();
        }
        return "mvn";
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
     * @param name   foundation repo name
     * @param kind   what happened
     * @param detail human-readable explanation for SKIPPED/FAILED;
     *               {@code null} for RELEASED/UP_TO_DATE
     */
    record Outcome(String name, OutcomeKind kind, String detail) {
        static Outcome released(String name) {
            return new Outcome(name, OutcomeKind.RELEASED, null);
        }
        static Outcome upToDate(String name, String tag) {
            return new Outcome(name, OutcomeKind.UP_TO_DATE, "at " + tag);
        }
        static Outcome skipped(String name, String reason) {
            return new Outcome(name, OutcomeKind.SKIPPED, reason);
        }
        static Outcome failed(String name, String reason) {
            return new Outcome(name, OutcomeKind.FAILED, reason);
        }
    }
}
