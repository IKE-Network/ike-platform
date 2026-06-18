package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.MavenWrapper;
import network.ike.plugin.ws.WsGoal;
import network.ike.workspace.IdeSettings;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import org.apache.maven.api.plugin.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconciler that upgrades workspace scaffold conventions to the
 * current plugin version (IKE-Network/ike-issues#393). Subsumes the
 * retired {@code ws:scaffold-upgrade-{draft,publish}} goals.
 *
 * <p>This reconciler owns the cross-cutting "scaffold layer" of a
 * workspace: gitignore, gitattributes, Syncthing ignore flags, Maven
 * wrapper, IntelliJ language level, POM {@code root="true"},
 * {@code .mvn/maven.config}, and the {@code ike-tooling.version}
 * property. It does not touch Maven dependency versions or workspace
 * alignment state.
 *
 * <p>Each upgrade step is idempotent — running the reconciler twice
 * produces the same result. {@code detect} reports the steps that
 * would change state; {@code apply} performs the mutations.
 *
 * <h2>Upgrade steps</h2>
 * <ol>
 *   <li><b>global-gitignore</b> — ensure {@code .ike/vcs-state} and
 *       {@code _git-init*} are in the user's global gitignore.</li>
 *   <li><b>workspace-gitignore</b> — sectioned whitelist enforcement
 *       for the workspace {@code .gitignore}.</li>
 *   <li><b>stignore-shared</b> — {@code (?d)} prefix on directory
 *       ignore patterns in Syncthing's {@code stignore-shared}.</li>
 *   <li><b>pom-root</b> — Maven 4.1.0 {@code root="true"} on the
 *       workspace POM.</li>
 *   <li><b>maven-config</b> — ensure {@code .mvn/maven.config} exists
 *       with {@code -T 1C}, and strip the retired {@code ws:ide-sync}
 *       {@code -P?with-*} block (IKE-Network/ike-issues#460).</li>
 *   <li><b>gitattributes</b> — line-ending policy at workspace root
 *       (ike-issues#189: Windows {@code mvnw.cmd} must be CRLF).</li>
 *   <li><b>mvnw</b> — regenerate missing Maven wrapper files, and
 *       replace the legacy custom wrapper with the standard
 *       {@code only-script} Apache wrapper (ike-issues#405).</li>
 *   <li><b>ide-language-level</b> — apply the {@code ide:} section
 *       from {@code workspace.yaml} to {@code .idea/misc.xml}.</li>
 *   <li><b>plugin-version</b> — bump {@code ike-tooling.version} in
 *       the workspace POM (and migrate the legacy
 *       {@code ike-maven-plugin.version} property name).</li>
 * </ol>
 *
 * <p>Opt out with {@code -DupdateScaffold=false}.
 */
public class ScaffoldConventionReconciler implements Reconciler {

    @Override
    public String dimension() {
        return "Scaffold conventions";
    }

    @Override
    public String optOutFlag() {
        return "updateScaffold";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        Run run = compute(ctx, false);
        if (run.drift.isEmpty()) {
            return DriftReport.noDrift(dimension());
        }
        List<String> detail = new ArrayList<>(run.drift);
        String summary = run.drift.size() + " scaffold convention(s) drift";
        String action = "apply scaffold upgrades on scaffold-publish";
        String optOut = "mvn " + WsGoal.SCAFFOLD_PUBLISH.qualified()
                + " -D" + optOutFlag() + "=false";
        return new DriftReport(dimension(), true, summary, detail, action, optOut);
    }

    @Override
    public void apply(WorkspaceContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension() + ": skipped (opted out via -D"
                    + optOutFlag() + "=false)");
            return;
        }
        Run run = compute(ctx, true);
        if (run.applied == 0) {
            return;
        }
        ctx.log().info("  " + dimension() + ": applied "
                + run.applied + " upgrade(s)");
    }

    // ── Drift run (shared by detect and apply) ──────────────────────

    /**
     * Mutable accumulator for one reconciliation pass. {@code drift}
     * collects human-readable summaries of steps that would change
     * state (used by {@link #detect}); {@code applied} counts the
     * steps that actually wrote to disk (used by {@link #apply}).
     */
    private static final class Run {
        final List<String> drift = new ArrayList<>();
        int applied = 0;
    }

    /**
     * Resolve the plugin's own version from manifest metadata. Falls
     * back to a non-zero default when the manifest entry is missing
     * (which happens in test classpath setups). Matches the original
     * Mojo's fallback to "49".
     */
    private static String resolvePluginVersion() {
        String v = ScaffoldConventionReconciler.class.getPackage()
                .getImplementationVersion();
        return v != null ? v : "49";
    }

    private Run compute(WorkspaceContext ctx, boolean publish) {
        Run run = new Run();
        Path root = ctx.workspaceRoot().toPath();
        String pluginVersion = resolvePluginVersion();
        Log log = ctx.log();

        upgradeGlobalGitignore(run, publish, log);
        upgradeWorkspaceGitignore(ctx, root, run, publish, log);
        upgradeStignoreShared(root, run, publish, log);
        upgradePomRoot(root, run, publish, log);
        upgradeMavenConfig(root, run, publish, log);
        upgradeGitattributes(root, run, publish, log);
        upgradeMvnw(ctx, root, run, publish, log);
        upgradeIdeLanguageLevel(ctx, root, run, publish, log);
        upgradePluginVersion(root, pluginVersion, run, publish, log);

        return run;
    }

    // ── 1. Global gitignore ────────────────────────────────────────

    private static void upgradeGlobalGitignore(Run run, boolean publish, Log log) {
        Path globalIgnore = Path.of(System.getProperty("user.home"),
                ".gitignore_global");
        try {
            String content = Files.exists(globalIgnore)
                    ? Files.readString(globalIgnore, StandardCharsets.UTF_8)
                    : "";

            boolean needsVcsState = !content.contains("vcs-state");
            boolean needsGitInit = !content.contains("_git-init");

            if (!needsVcsState && !needsGitInit) {
                return;
            }

            StringBuilder additions = new StringBuilder();
            if (needsGitInit) additions.append("_git-init*\n");
            if (needsVcsState) additions.append(".ike/vcs-state\n");

            run.drift.add("global-gitignore: add "
                    + (needsVcsState ? ".ike/vcs-state " : "")
                    + (needsGitInit ? "_git-init*" : "").trim());

            if (publish) {
                Files.writeString(globalIgnore,
                        content + (content.endsWith("\n") ? "" : "\n")
                                + additions,
                        StandardCharsets.UTF_8);
                run.applied++;
            }
        } catch (IOException e) {
            log.warn("  Could not update global gitignore: " + e.getMessage());
        }
    }

    // ── 2. Workspace .gitignore whitelist ──────────────────────────

    /**
     * Required {@code .gitignore} entries grouped into named sections.
     * Order mirrors what {@code WorkspaceBootstrap#generateGitignore()}
     * emits so that output from {@code ws:scaffold-init} and this
     * reconciler stays visually consistent.
     */
    static final List<GitignoreSection> GITIGNORE_SECTIONS = List.of(
            new GitignoreSection(
                    "# ── Whitelist workspace-level files ──────────────────────────────",
                    "!.gitignore", "!.gitattributes", "!pom.xml", "!workspace.yaml"
            ),
            new GitignoreSection(
                    "# ── Whitelist workspace-owned directories ────────────────────────",
                    "!.mvn/", "!.mvn/**", "!checkpoints/", "!checkpoints/**"
            ),
            new GitignoreSection(
                    "# ── IntelliJ project config (curated slice) ──────────────────────\n"
                            + "# Small, stable project-wide settings shared across collaborators.\n"
                            + "# compiler.xml and vcs.xml are excluded — they regenerate per\n"
                            + "# Maven reload or per workspace membership. misc.xml is excluded\n"
                            + "# by default (per-machine Maven profile selection); opt in with\n"
                            + "# `ide.track-misc-xml: true` in workspace.yaml (ike-issues#571).",
                    "!.idea/", "!.idea/.gitignore",
                    "!.idea/kotlinc.xml", "!.idea/encodings.xml",
                    "!.idea/jarRepositories.xml"
            )
    );

    /**
     * A named group of {@code .gitignore} entries sharing a section
     * header comment. The reconciler emits the full header when no
     * entries from this section are present; otherwise appends only
     * the missing entries individually.
     *
     * @param header  the section header comment block
     * @param entries the required entries for this section
     */
    record GitignoreSection(String header, List<String> entries) {
        GitignoreSection(String header, String... entries) {
            this(header, List.of(entries));
        }
    }

    /**
     * Compute the additions needed to bring an existing {@code .gitignore}
     * up to spec. Package-private for test access.
     *
     * @param content the current {@code .gitignore} content
     * @return the text to append (empty string if already current)
     */
    public static String computeGitignoreAdditions(String content) {
        Set<String> existingLines = new LinkedHashSet<>();
        for (String line : content.split("\n")) {
            existingLines.add(line.trim());
        }

        StringBuilder additions = new StringBuilder();
        for (GitignoreSection section : GITIGNORE_SECTIONS) {
            List<String> missing = new ArrayList<>();
            for (String entry : section.entries()) {
                if (!existingLines.contains(entry)) {
                    missing.add(entry);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            boolean fullSection = missing.size() == section.entries().size();
            if (fullSection) {
                additions.append("\n").append(section.header()).append("\n");
            }
            for (String entry : missing) {
                additions.append(entry).append("\n");
            }
        }
        return additions.toString();
    }

    private static void upgradeWorkspaceGitignore(WorkspaceContext ctx, Path root,
                                                   Run run, boolean publish,
                                                   Log log) {
        Path gitignore = root.resolve(".gitignore");
        try {
            if (!Files.exists(gitignore)) {
                return;
            }

            String content = Files.readString(gitignore, StandardCharsets.UTF_8);

            // Additive base sections (never removes user-authored lines).
            String additions = computeGitignoreAdditions(content);
            String withBase = additions.isEmpty()
                    ? content
                    : content + (content.endsWith("\n") ? "" : "\n") + additions;

            // misc.xml tracking is opt-in via `ide.track-misc-xml`
            // (ike-issues#571): it co-mingles per-machine Maven profile
            // selection, so by default it stays ignored. Keep the
            // `!.idea/misc.xml` whitelist line in sync with the setting,
            // self-healing a rogue line left by older plugin versions.
            boolean track = tracksMiscXml(ctx, log);
            String updated = reconcileMiscXmlWhitelist(withBase, track);

            if (updated.equals(content)) {
                return;
            }

            if (!additions.isEmpty()) {
                run.drift.add("workspace-gitignore: add missing whitelist entries");
            }
            if (!updated.equals(withBase)) {
                run.drift.add(track
                        ? "workspace-gitignore: whitelist .idea/misc.xml"
                                + " (ide.track-misc-xml)"
                        : "workspace-gitignore: stop tracking .idea/misc.xml"
                                + " (per-machine; opt in via ide.track-misc-xml)");
            }

            if (publish) {
                Files.writeString(gitignore, updated, StandardCharsets.UTF_8);
                run.applied++;
            }
        } catch (IOException e) {
            log.warn("  Could not update .gitignore: " + e.getMessage());
        }
    }

    /**
     * Read {@code ide.track-misc-xml} from {@code workspace.yaml},
     * defaulting to {@code false} (ignore misc.xml) when the manifest
     * is absent or unreadable.
     *
     * @param ctx the workspace context (provides the manifest path)
     * @param log the plugin log for debug diagnostics
     * @return whether the workspace opts in to tracking misc.xml
     */
    private static boolean tracksMiscXml(WorkspaceContext ctx, Log log) {
        try {
            return ManifestReader.read(ctx.manifestPath()).ide().trackMiscXml();
        } catch (ManifestException e) {
            log.debug("Could not read workspace.yaml for ide.track-misc-xml: "
                    + e.getMessage());
            return false;
        }
    }

    /**
     * Keep the {@code !.idea/misc.xml} whitelist line in sync with the
     * workspace's opt-in choice. When {@code track} is {@code true} the
     * line is ensured present (appended if missing); otherwise every
     * occurrence is removed so misc.xml falls back under the catch-all
     * ignore. misc.xml co-mingles per-machine Maven profile selection,
     * so tracking is opt-in (IKE-Network/ike-issues#571).
     * Visible for tests.
     *
     * @param content the current {@code .gitignore} content
     * @param track   whether the workspace opts in to tracking misc.xml
     * @return the reconciled content
     */
    public static String reconcileMiscXmlWhitelist(String content, boolean track) {
        String entry = "!.idea/misc.xml";
        String[] lines = content.split("\n", -1);
        boolean present = false;
        for (String line : lines) {
            if (line.trim().equals(entry)) {
                present = true;
                break;
            }
        }
        if (track) {
            if (present) {
                return content;
            }
            return content
                    + (content.isEmpty() || content.endsWith("\n") ? "" : "\n")
                    + entry + "\n";
        }
        if (!present) {
            return content;
        }
        List<String> kept = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (!line.trim().equals(entry)) {
                kept.add(line);
            }
        }
        return String.join("\n", kept);
    }

    // ── 3. stignore-shared (?d) flags ──────────────────────────────

    private static void upgradeStignoreShared(Path root, Run run,
                                               boolean publish, Log log) {
        Path stignore = root.getParent() != null
                ? root.getParent().resolve("stignore-shared")
                : null;
        if (stignore == null || !Files.exists(stignore)) {
            return;
        }

        try {
            String content = Files.readString(stignore, StandardCharsets.UTF_8);
            boolean changed = false;
            String updated = content;

            String[][] upgrades = {
                    {"\n.git/", "\n(?d).git/"},
                    {"\ntarget/", "\n(?d)target/"},
                    {"\nout/", "\n(?d)out/"},
                    {"\n.gradle/", "\n(?d).gradle/"},
                    {"\nbuild/", "\n(?d)build/"},
            };
            for (String[] pair : upgrades) {
                if (updated.contains(pair[0]) && !updated.contains(pair[1])) {
                    updated = updated.replace(pair[0], pair[1]);
                    changed = true;
                }
            }
            if (updated.startsWith(".git/") && !updated.startsWith("(?d).git/")) {
                updated = "(?d)" + updated;
                changed = true;
            }

            if (!changed) {
                return;
            }

            run.drift.add("stignore-shared: add (?d) prefix to directory patterns");

            if (publish) {
                Files.writeString(stignore, updated, StandardCharsets.UTF_8);
                run.applied++;
            }
        } catch (IOException e) {
            log.warn("  Could not update stignore-shared: " + e.getMessage());
        }
    }

    // ── 4. POM root="true" ────────────────────────────────────────

    private static void upgradePomRoot(Path root, Run run,
                                        boolean publish, Log log) {
        Path pom = root.resolve("pom.xml");
        try {
            if (!Files.exists(pom)) {
                return;
            }

            String content = Files.readString(pom, StandardCharsets.UTF_8);
            if (content.contains("root=\"true\"")) {
                return;
            }

            String updated = content.replaceFirst(
                    "(<project\\s[^>]*?)(>)",
                    "$1\n         root=\"true\"$2");

            run.drift.add("pom-root: add root=\"true\" to <project>");

            if (publish) {
                Files.writeString(pom, updated, StandardCharsets.UTF_8);
                run.applied++;
            }
        } catch (IOException e) {
            log.warn("  Could not update pom.xml: " + e.getMessage());
        }
    }

    // ── 5. .mvn/maven.config ──────────────────────────────────────

    private static void upgradeMavenConfig(Path root, Run run,
                                            boolean publish, Log log) {
        Path config = root.resolve(".mvn/maven.config");
        try {
            if (!Files.exists(config)) {
                run.drift.add("maven-config: create .mvn/maven.config with -T 1C");
                if (publish) {
                    Files.createDirectories(config.getParent());
                    Files.writeString(config, "-T 1C\n", StandardCharsets.UTF_8);
                    run.applied++;
                }
                return;
            }

            // Strip the retired ws:ide-sync managed -P?with-* block: it
            // force-activated the legacy with-* subproject profiles, which
            // the #460 migration to top-level <subprojects> retires
            // (IKE-Network/ike-issues#696). The block references profiles
            // that no longer exist; the leading '?' kept it non-fatal, but
            // it is dead config.
            String content = Files.readString(config, StandardCharsets.UTF_8);
            String stripped = stripIdeSyncBlock(content);
            if (stripped.equals(content)) {
                return;
            }
            run.drift.add("maven-config: remove retired ws:ide-sync "
                    + "-P?with-* block (#460)");
            if (publish) {
                Files.writeString(config, stripped, StandardCharsets.UTF_8);
                run.applied++;
            }
        } catch (IOException e) {
            log.warn("  Could not update .mvn/maven.config: " + e.getMessage());
        }
    }

    /**
     * Remove the {@code ws:ide-sync} managed block — the
     * {@code -P?with-*} profile-activation lines IntelliJ once needed —
     * from {@code .mvn/maven.config} content. The block is delimited by
     * the {@code # >>> ws:ide-sync managed >>>} /
     * {@code # <<< ws:ide-sync managed <<<} sentinels. Returns the input
     * unchanged when the block is absent or its sentinels are malformed.
     * Visible for tests.
     *
     * @param content the current {@code .mvn/maven.config} content
     * @return the content with the managed block removed
     */
    public static String stripIdeSyncBlock(String content) {
        final String begin = "# >>> ws:ide-sync managed >>>";
        final String end = "# <<< ws:ide-sync managed <<<";
        int b = content.indexOf(begin);
        if (b < 0) {
            return content;
        }
        int e = content.indexOf(end, b);
        if (e < 0) {
            return content;            // malformed — leave it untouched
        }
        e += end.length();
        if (e < content.length() && content.charAt(e) == '\n') {
            e++;
        }
        String result = content.substring(0, b) + content.substring(e);
        return result.isBlank() ? result : result.stripTrailing() + "\n";
    }

    // ── 6. .gitattributes ─────────────────────────────────────────

    /**
     * Header comment block emitted when {@code .gitattributes} is
     * created from scratch.
     */
    static final String GITATTRIBUTES_HEADER =
            "# Line-ending policy — managed by "
            + WsGoal.SCAFFOLD_PUBLISH.qualified() + "\n"
            + """
            # ──────────────────────────────────────────────────────
            #
            # Windows batch files MUST be CRLF or cmd.exe chokes — symptoms range
            # from "The system cannot find the path specified" to silent failure.
            # Without this file, mvnw.cmd checked out under core.autocrlf=false
            # (or input) is broken on Windows.
            #
            # Tracked in IKE-Network/ike-issues#189.
            """;

    /**
     * Required {@code .gitattributes} rules, in the order they should
     * appear when the file is created from scratch.
     */
    static final List<String> GITATTRIBUTES_RULES = List.of(
            "*.cmd  text eol=crlf",
            "*.bat  text eol=crlf",
            "*.sh   text eol=lf",
            "mvnw   text eol=lf",
            "* text=auto"
    );

    /**
     * Compute additions needed to bring an existing {@code .gitattributes}
     * up to spec. Detects each required rule by its leading whitespace-
     * separated pattern token. Returns the empty string when the file
     * is already current. Package-private for test access.
     *
     * @param content the current {@code .gitattributes} content (empty
     *                string when the file does not exist)
     * @return the text to append (empty when already current)
     */
    public static String computeGitattributesAdditions(String content) {
        Set<String> presentPatterns = new LinkedHashSet<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+", 2);
            if (tokens.length > 0) {
                presentPatterns.add(tokens[0]);
            }
        }

        List<String> missing = new ArrayList<>();
        for (String rule : GITATTRIBUTES_RULES) {
            String pattern = rule.split("\\s+", 2)[0];
            if (!presentPatterns.contains(pattern)) {
                missing.add(rule);
            }
        }

        if (missing.isEmpty()) {
            return "";
        }

        StringBuilder additions = new StringBuilder();
        boolean creating = content.isEmpty();
        if (creating) {
            additions.append(GITATTRIBUTES_HEADER);
            additions.append('\n');
        }
        for (String rule : missing) {
            additions.append(rule).append('\n');
        }
        return additions.toString();
    }

    private static void upgradeGitattributes(Path root, Run run,
                                              boolean publish, Log log) {
        Path gitattributes = root.resolve(".gitattributes");
        try {
            String content = Files.exists(gitattributes)
                    ? Files.readString(gitattributes, StandardCharsets.UTF_8)
                    : "";

            String additions = computeGitattributesAdditions(content);
            if (additions.isEmpty()) {
                return;
            }

            boolean creating = content.isEmpty();
            run.drift.add("gitattributes: "
                    + (creating ? "create with canonical rules"
                                : "add missing rules"));

            if (publish) {
                String updated = creating
                        ? additions
                        : content + (content.endsWith("\n") ? "" : "\n")
                                + additions;
                Files.writeString(gitattributes, updated, StandardCharsets.UTF_8);
                run.applied++;
            }
        } catch (IOException e) {
            log.warn("  Could not update .gitattributes: " + e.getMessage());
        }
    }

    // ── 7. Maven wrapper ───────────────────────────────────────────

    private static void upgradeMvnw(WorkspaceContext ctx, Path root, Run run,
                                     boolean publish, Log log) {
        Path propsFile = root.resolve(".mvn").resolve("wrapper")
                .resolve("maven-wrapper.properties");
        Path mvnw = root.resolve("mvnw");
        Path mvnwCmd = root.resolve("mvnw.cmd");

        boolean propsMissing = !Files.exists(propsFile);
        boolean mvnwMissing = !Files.exists(mvnw);
        boolean mvnwCmdMissing = !Files.exists(mvnwCmd);

        boolean legacy;
        try {
            legacy = MavenWrapper.isLegacyWrapper(root);
        } catch (IOException e) {
            log.warn("  Could not inspect Maven wrapper: " + e.getMessage());
            return;
        }

        if (!propsMissing && !mvnwMissing && !mvnwCmdMissing && !legacy) {
            return;
        }

        String mavenVersion = resolveMavenVersionForUpgrade(ctx, root, log);
        if (mavenVersion == null) {
            // No version configured — skip cleanly.
            return;
        }

        if (legacy) {
            run.drift.add("mvnw: replace legacy custom wrapper with standard "
                    + "only-script wrapper (Maven " + mavenVersion + ")");
            if (publish) {
                try {
                    MavenWrapper.writeAll(root, mavenVersion);
                    run.applied++;
                } catch (IOException e) {
                    log.warn("  Could not replace Maven wrapper: " + e.getMessage());
                }
            }
            return;
        }

        List<String> created = new ArrayList<>();
        if (propsMissing) created.add(".mvn/wrapper/maven-wrapper.properties");
        if (mvnwMissing) created.add("mvnw");
        if (mvnwCmdMissing) created.add("mvnw.cmd");

        run.drift.add("mvnw: create " + String.join(", ", created)
                + " (Maven " + mavenVersion + ")");

        if (publish) {
            try {
                MavenWrapper.writeMissingFiles(root, mavenVersion);
                run.applied++;
            } catch (IOException e) {
                log.warn("  Could not install Maven wrapper: " + e.getMessage());
            }
        }
    }

    /**
     * Resolve the Maven version to write into a regenerated wrapper.
     * Prefers an existing {@code maven.version} in the wrapper
     * properties file; falls back to {@code defaults.maven-version}
     * from {@code workspace.yaml}.
     */
    private static String resolveMavenVersionForUpgrade(WorkspaceContext ctx,
                                                         Path root, Log log) {
        try {
            String pinned = MavenWrapper.readPinnedVersion(root);
            if (pinned != null && !pinned.isBlank()) {
                return pinned;
            }
        } catch (IOException e) {
            log.warn("  Could not read maven-wrapper.properties: "
                    + e.getMessage());
        }
        try {
            Manifest m = ManifestReader.read(ctx.manifestPath());
            if (m.defaults() != null && m.defaults().mavenVersion() != null
                    && !m.defaults().mavenVersion().isBlank()) {
                return m.defaults().mavenVersion();
            }
        } catch (ManifestException e) {
            log.debug("Could not read workspace.yaml for maven-version: "
                    + e.getMessage());
        }
        return null;
    }

    // ── 8. IntelliJ language level ────────────────────────────────

    private static void upgradeIdeLanguageLevel(WorkspaceContext ctx, Path root,
                                                 Run run, boolean publish,
                                                 Log log) {
        Path misc = root.resolve(".idea").resolve("misc.xml");
        if (!Files.exists(misc)) {
            return;
        }

        IdeSettings ide;
        try {
            Manifest m = ManifestReader.read(ctx.manifestPath());
            ide = m.ide();
        } catch (ManifestException e) {
            log.warn("  IDE language level: could not read workspace.yaml: "
                    + e.getMessage());
            return;
        }

        if (!ide.hasAnyValue()) {
            return;
        }

        try {
            String content = Files.readString(misc, StandardCharsets.UTF_8);
            String updated = applyIdeSettings(content, ide);
            if (updated.equals(content)) {
                return;
            }

            run.drift.add("ide-language-level: set to "
                    + (ide.languageLevel() != null
                            ? ide.languageLevel() : "(jdk-name only)"));

            if (publish) {
                Files.writeString(misc, updated, StandardCharsets.UTF_8);
                run.applied++;
            }
        } catch (IOException e) {
            log.warn("  Could not update .idea/misc.xml: " + e.getMessage());
        }
    }

    /**
     * Apply {@code ide.languageLevel} and {@code ide.jdkName} to the
     * {@code ProjectRootManager} component in {@code .idea/misc.xml}
     * content. Returns the updated content, or the original if no
     * change is needed. Package-private for test access.
     *
     * @param content the current {@code misc.xml} content
     * @param ide     the settings to enforce (null fields are no-ops)
     * @return the updated content
     */
    public static String applyIdeSettings(String content, IdeSettings ide) {
        String updated = content;
        if (ide.languageLevel() != null) {
            updated = replaceProjectRootAttr(updated, "languageLevel",
                    ide.languageLevel());
        }
        if (ide.jdkName() != null) {
            updated = replaceProjectRootAttr(updated, "project-jdk-name",
                    ide.jdkName());
        }
        return updated;
    }

    private static String replaceProjectRootAttr(String content, String attr,
                                                  String value) {
        Pattern p = Pattern.compile(
                "(<component\\s+name=\"ProjectRootManager\"[^>]*\\b"
                        + Pattern.quote(attr) + "=\")([^\"]*)(\")");
        Matcher m = p.matcher(content);
        if (!m.find()) {
            return content;
        }
        if (m.group(2).equals(value)) {
            return content;
        }
        return m.replaceFirst(Matcher.quoteReplacement(m.group(1))
                + Matcher.quoteReplacement(value)
                + Matcher.quoteReplacement(m.group(3)));
    }

    // ── 9. Plugin version ─────────────────────────────────────────

    private static void upgradePluginVersion(Path root, String pluginVersion,
                                              Run run, boolean publish,
                                              Log log) {
        Path pom = root.resolve("pom.xml");
        try {
            if (!Files.exists(pom)) {
                return;
            }

            String content = Files.readString(pom, StandardCharsets.UTF_8);
            boolean changed = false;
            List<String> notes = new ArrayList<>();

            // Migration: rename ike-maven-plugin.version → ike-tooling.version.
            if (content.contains("<ike-maven-plugin.version>")) {
                content = content.replace(
                        "<ike-maven-plugin.version>", "<ike-tooling.version>");
                content = content.replace(
                        "</ike-maven-plugin.version>", "</ike-tooling.version>");
                content = content.replace(
                        "${ike-maven-plugin.version}", "${ike-tooling.version}");
                changed = true;
                notes.add("rename ike-maven-plugin.version → ike-tooling.version");
            }

            Pattern versionProp = Pattern.compile(
                    "(<ike-tooling\\.version>)(.*?)(</ike-tooling\\.version>)");
            Matcher m = versionProp.matcher(content);

            if (!m.find()) {
                if (changed) {
                    finalizePluginVersion(pom, content, publish, run, notes);
                }
                return;
            }

            String currentVersion = m.group(2);
            if (!currentVersion.equals(pluginVersion)) {
                content = m.replaceFirst("$1" + pluginVersion + "$3");
                changed = true;
                notes.add("ike-tooling.version " + currentVersion
                        + " → " + pluginVersion);
            }

            if (!changed) {
                return;
            }
            finalizePluginVersion(pom, content, publish, run, notes);
        } catch (IOException e) {
            log.warn("  Could not update plugin version: " + e.getMessage());
        }
    }

    private static void finalizePluginVersion(Path pom, String content,
                                                boolean publish, Run run,
                                                List<String> notes)
            throws IOException {
        run.drift.add("plugin-version: " + String.join("; ", notes));
        if (publish) {
            Files.writeString(pom, content, StandardCharsets.UTF_8);
            run.applied++;
        }
    }

}
