package network.ike.plugin.ws;

import network.ike.plugin.PomRewriter;
import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.cascade.CascadeRepo;
import network.ike.workspace.cascade.ReleaseCascade;
import network.ike.workspace.cascade.ReleaseCascadeIo;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Canonical foundation cascade — the fallback order used only
     * when no {@code release-cascade.yaml} manifest can be found and
     * {@code -Dfoundations} was not supplied.
     */
    private static final String DEFAULT_FOUNDATIONS =
            "ike-tooling,ike-docs,ike-platform";

    /**
     * Comma-separated list of foundation repos to walk, in topological
     * order. When left unset (the normal case) the order is read from
     * the {@code release-cascade.yaml} manifest
     * (IKE-Network/ike-issues#402). Set this only to override the
     * cascade for a partial run, e.g.
     * {@code -Dfoundations=ike-tooling,ike-docs}.
     */
    @Parameter(property = "foundations")
    String foundations;

    /**
     * Path to the {@code release-cascade.yaml} manifest. Bound to the
     * standard {@code ike.release.cascade.manifest} property, which
     * {@code ike-parent} declares in its {@code <properties>}
     * pointing at {@code target/release-cascade.yaml}. When the
     * property is unset the goal falls back to
     * {@code <workspaceRoot>/target/release-cascade.yaml}. See
     * IKE-Network/ike-issues#402.
     */
    @Parameter(property = "ike.release.cascade.manifest")
    String cascadeManifest;

    /**
     * Base directory containing the foundation repo checkouts as
     * sibling directories. Bound to the {@code ike.release.cascade.basedir}
     * property. Defaults to the parent of the current workspace, which
     * matches the standard local {@code ~/ike-dev/&lt;repo&gt;/} layout.
     *
     * <p>This goal is the <em>single-process</em> cascade walker — it
     * assumes every foundation repo is checked out under one base
     * directory. That is the local-workstation model. On a CI server
     * each repo is typically its own build configuration with its own
     * checkout; there the cascade is expressed as CI build-chain
     * dependencies and this goal is not used. See the goal's reference
     * page and IKE-Network/ike-issues#402.
     */
    @Parameter(property = "ike.release.cascade.basedir")
    File foundationsDir;

    /**
     * Per-repo checkout-location overrides for non-standard layouts —
     * keyed by cascade repo name, valued with an absolute path. A repo
     * absent from this map is located at
     * {@code <ike.release.cascade.basedir>/<repo>}. Configure in the
     * POM when the foundation checkouts are not co-located:
     *
     * <pre>{@code
     * <configuration>
     *   <cascadeRepoDirs>
     *     <ike-tooling>/agent/work/a1/ike-tooling</ike-tooling>
     *     <ike-docs>/agent/work/b2/ike-docs</ike-docs>
     *   </cascadeRepoDirs>
     * </configuration>
     * }</pre>
     *
     * IKE-Network/ike-issues#402.
     */
    @Parameter
    Map<String, String> cascadeRepoDirs;

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

    /**
     * The running plugin's own version, read from
     * {@code META-INF/maven/.../pom.properties} on the classpath
     * (see {@link #resolvePluginVersion}). Used by
     * {@link #invokeWorkspaceRelease} to build explicit-coords
     * invocations of {@code ws:release-publish} that pin to THIS
     * plugin version instead of letting the workspace's
     * {@code pluginManagement} pick (which, for a workspace still
     * on an old {@code ike-parent}, would resolve to an old version
     * that lacks the cascade-driven fixes). ike-issues#378.
     *
     * <p>Maven 4's {@code @Parameter(defaultValue = "${plugin.version}")}
     * idiom does NOT work — Maven 4.0.0-rc-5 doesn't bind
     * {@code PluginDescriptor} in the DI container (see WsHelpMojo
     * for the same observation). The classpath-resource fallback
     * is independent of the DI container and works reliably.
     */
    private String pluginVersion;

    /**
     * Skip the per-foundation pre-install step that seeds {@code
     * ~/.m2} with the current SNAPSHOT before invoking
     * {@code ike:release-publish}. Defaults to {@code false} (do
     * the pre-install) because {@code ike-tooling}'s X-SNAPSHOT
     * self-host pattern (#370) needs the current SNAPSHOT to be
     * locally resolvable — otherwise Maven cannot even start the
     * release goal. ike-issues#379.
     */
    @Parameter(property = "skipPreInstall", defaultValue = "false")
    boolean skipPreInstall;

    /** Creates this goal instance. */
    public WsCascadeFoundationPublishMojo() {}

    /**
     * Resolves the foundation cascade order (IKE-Network/ike-issues#402).
     *
     * <p>Priority: an explicit {@code -Dfoundations} CSV when supplied,
     * otherwise the {@code release-cascade.yaml} manifest, otherwise
     * the canonical {@link #DEFAULT_FOUNDATIONS} fallback. The chosen
     * source is logged so the operator can see where the order came
     * from.
     *
     * @param searchRoot the directory from which the manifest is
     *                   located (the workspace root)
     * @return foundation repo names in topological release order
     */
    private List<String> resolveFoundations(File searchRoot) {
        if (foundations != null && !foundations.isBlank()) {
            getLog().info("  Cascade source: -Dfoundations override");
            return splitCsv(foundations);
        }
        Path manifestPath =
                (cascadeManifest != null && !cascadeManifest.isBlank())
                        ? Path.of(cascadeManifest)
                        : new File(searchRoot, "target/release-cascade.yaml")
                                .toPath();
        Optional<ReleaseCascade> loaded;
        try {
            loaded = ReleaseCascadeIo.load(manifestPath);
        } catch (RuntimeException e) {
            getLog().warn("release-cascade.yaml could not be read ("
                    + e.getMessage()
                    + "); using the built-in default order.");
            loaded = Optional.empty();
        }
        if (loaded.isPresent()) {
            getLog().info("  Cascade source: release-cascade.yaml");
            return loaded.get().repos().stream()
                    .map(CascadeRepo::repo)
                    .toList();
        }
        getLog().info("  Cascade source: built-in default"
                + " (no release-cascade.yaml found)");
        return splitCsv(DEFAULT_FOUNDATIONS);
    }

    private static List<String> splitCsv(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Resolves a cascade repo's checkout directory
     * (IKE-Network/ike-issues#402).
     *
     * <p>An explicit per-repo override in {@link #cascadeRepoDirs}
     * wins; otherwise the repo is located as {@code <baseDir>/<repo>}
     * — the co-located sibling layout.
     *
     * @param baseDir the foundations base directory
     * @param repo    the cascade repo name
     * @return the directory the repo is expected to be checked out in
     */
    private File resolveRepoDir(File baseDir, String repo) {
        if (cascadeRepoDirs != null) {
            String override = cascadeRepoDirs.get(repo);
            if (override != null && !override.isBlank()) {
                File dir = new File(override);
                getLog().info("  " + repo + ": location override → "
                        + dir);
                return dir;
            }
        }
        return new File(baseDir, repo);
    }

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        File wsRoot = workspaceRoot();
        File baseDir = foundationsDir != null
                ? foundationsDir
                : wsRoot.getParentFile();
        if (baseDir == null) {
            throw new MojoException(
                    "Could not determine the foundations base directory. "
                            + "Workspace root has no parent. Pass "
                            + "-Dike.release.cascade.basedir=<path>.");
        }

        getLog().info("");
        getLog().info("IKE Foundation Cascade");
        getLog().info("══════════════════════");
        getLog().info("  Base directory:  " + baseDir);
        List<String> names = resolveFoundations(wsRoot);
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
            File dir = resolveRepoDir(baseDir, name);
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
        return new WorkspaceReportSpec(WsGoal.CASCADE_FOUNDATION_PUBLISH,
                buildCascadeReport(outcomes));
    }

    /**
     * Render the per-foundation cascade outcomes as a Markdown table
     * for the session report.
     *
     * @param outcomes the outcomes recorded across the cascade
     * @return the Markdown report body
     */
    static String buildCascadeReport(List<Outcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Repo | Outcome | Detail |\n");
        sb.append("|------|---------|--------|\n");
        for (Outcome o : outcomes) {
            String marker = switch (o.kind) {
                case RELEASED -> "✓ released";
                case UP_TO_DATE -> "— up to date";
                case SKIPPED -> "— skipped";
                case FAILED -> "✗ FAILED";
            };
            sb.append("| ").append(o.name)
              .append(" | ").append(marker)
              .append(" | ").append(o.detail == null ? "" : o.detail)
              .append(" |\n");
        }
        return sb.toString();
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

        String mvn = ReleaseSupport.resolveMavenWrapper(dir, getLog())
                .getAbsolutePath();

        // SNAPSHOT bootstrap (ike-issues#379). Between cycles, no
        // process installs the freshly-bumped SNAPSHOT to ~/.m2 —
        // the previous cycle's post-release-bump only edits the
        // pom; nothing rebuilds. For reactors with the X-SNAPSHOT
        // self-host pattern (ike-tooling per #370), Maven can't
        // resolve the reactor's own plugin reference at the current
        // SNAPSHOT, so `ike:release-publish` fails before its
        // own `mvn clean install` can fix the gap.
        //
        // Seed ~/.m2 with a fast install. Tests skipped (the release
        // flow's own pre-release verify runs them). Best-effort: a
        // failure here usually points at a real build problem the
        // operator should see, but we let the release flow run
        // anyway so the operator gets the clearer error from there.
        if (!skipPreInstall) {
            getLog().info("  Seeding ~/.m2 with current SNAPSHOT...");
            try {
                ReleaseSupport.exec(dir, getLog(),
                        mvn, "install", "-DskipTests", "-T", "4", "-B");
            } catch (Exception e) {
                getLog().warn("  Pre-install for " + name
                        + " failed (continuing — release-publish will "
                        + "surface the underlying error if it's real): "
                        + e.getMessage());
            }
        }

        // Run ike:release-publish.
        getLog().info("  Running mvn ike:release-publish...");
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
     *
     * <p>Uses explicit self-version coordinates rather than the short
     * {@code ws:release-publish} prefix (ike-issues#378). The short
     * prefix resolves through the workspace's {@code pluginManagement}
     * → {@code ike-parent} → ws-plugin pin; for a workspace still on
     * an older {@code ike-parent}, this would load the OLDER ws-plugin
     * even when the running cascade is a newer version. The classic
     * chicken-and-egg: the cascade's fixes that ALIGN the workspace's
     * parent to the current version can't take effect because they're
     * gated on the workspace having already aligned. Pinning to
     * {@link #pluginVersion} (the running plugin's own version)
     * makes the cascade self-consistent: a v47 cascade always calls
     * v47 ws:release-publish, regardless of what the workspace
     * inherits.
     */
    Outcome invokeWorkspaceRelease(File wsRoot) {
        String mvn = ReleaseSupport.resolveMavenWrapper(wsRoot, getLog())
                .getAbsolutePath();
        if (pluginVersion == null) {
            pluginVersion = resolvePluginVersion();
        }
        String selfCoords = "network.ike.platform"
                + ":ike-workspace-maven-plugin:"
                + pluginVersion
                + ":release-publish";
        try {
            ReleaseSupport.exec(wsRoot, getLog(),
                    mvn, selfCoords,
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

    /**
     * Resolve this plugin's own version by reading
     * {@code META-INF/maven/network.ike.platform/
     * ike-workspace-maven-plugin/pom.properties} from the classpath.
     * Maven generates this file at build time for every artifact, so
     * it's a reliable self-version source independent of the Maven
     * DI container's plugin-descriptor bindings (which Maven 4.0.0-rc-5
     * doesn't reliably populate).
     *
     * @return this plugin's version, or {@code "UNKNOWN"} if the
     *         pom.properties resource can't be read (a build error
     *         that would prevent invocation entirely, so should
     *         never happen at runtime).
     */
    static String resolvePluginVersion() {
        String resource = "/META-INF/maven/network.ike.platform"
                + "/ike-workspace-maven-plugin/pom.properties";
        try (java.io.InputStream is =
                     WsCascadeFoundationPublishMojo.class
                             .getResourceAsStream(resource)) {
            if (is == null) return "UNKNOWN";
            java.util.Properties p = new java.util.Properties();
            p.load(is);
            String v = p.getProperty("version");
            return v != null ? v : "UNKNOWN";
        } catch (java.io.IOException e) {
            return "UNKNOWN";
        }
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
