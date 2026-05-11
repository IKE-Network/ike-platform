package network.ike.plugin.ws.preflight;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.SnapshotScanner;
import network.ike.plugin.ws.WsGoal;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Closed vocabulary of preflight checks that {@code ws:*} goals can
 * require before they mutate workspace state. Each entry declares a
 * human-readable description and a {@link #check(PreflightContext)}
 * implementation that returns {@link Optional#empty()} on success or a
 * remediation message on failure.
 *
 * <p>Drafts and publishes invoke the same {@code PreflightCondition}
 * sequence via {@link Preflight}; whether failure is a warning (draft)
 * or a hard error (publish) is decided at the call site via
 * {@link PreflightResult#requirePassed(WsGoal)} vs
 * {@link PreflightResult#warnIfFailed(Log, WsGoal)}.
 *
 * <p>New conditions are added here as goals adopt the contract from
 * issue #154. Each new entry must stay self-contained: it does not
 * depend on the mojo instance, only on the shared {@link PreflightContext}.
 */
public enum PreflightCondition {

    /**
     * Every subproject working tree (and the workspace root itself, if
     * it is a git repo) must have no uncommitted changes. Any draft or
     * publish goal that creates branches, edits POMs, or otherwise
     * mutates files requires this.
     */
    WORKING_TREE_CLEAN("All subproject working trees are clean") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> uncommitted = new ArrayList<>();

            if (new File(root, ".git").exists()
                    && !gitStatus(root).isEmpty()) {
                uncommitted.add(WORKSPACE_ROOT_NAME);
            }
            for (String name : ctx.subprojects()) {
                File dir = new File(root, name);
                if (!new File(dir, ".git").exists()) continue;
                if (!gitStatus(dir).isEmpty()) {
                    uncommitted.add(name);
                }
            }

            if (uncommitted.isEmpty()) return Optional.empty();

            var sb = new StringBuilder();
            sb.append(uncommitted.size())
                    .append(" subproject(s) have uncommitted changes:\n");
            boolean anyGhPagesLeak = false;
            for (String name : uncommitted) {
                File dir = WORKSPACE_ROOT_NAME.equals(name)
                        ? root : new File(root, name);
                String status = gitStatus(dir);
                if (containsGhPagesLeakPattern(status)) {
                    anyGhPagesLeak = true;
                }
                String files = status.lines()
                        .map(l -> "      " + l.strip())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
                sb.append("    • ").append(name).append(":\n")
                  .append(files).append("\n");
            }
            sb.append("  To resolve:\n");
            sb.append("    mvn ws:commit"
                    + " -Dmessage=\"<your message>\"\n");
            sb.append("  Or stash changes in each affected subproject.");
            if (anyGhPagesLeak) {
                sb.append("\n");
                sb.append("  ⚠ Some entries above match gh-pages-style site\n");
                sb.append("    output (paths like <artifactId>/<artifactId>/\n");
                sb.append("    or examples/<name>/, containing bom.json,\n");
                sb.append("    built-with.html, dependencies.html, etc.).\n");
                sb.append("    These do NOT belong in main — the canonical\n");
                sb.append("    published content lives on each repo's\n");
                sb.append("    gh-pages branch. Delete them and add\n");
                sb.append("    .gitignore entries to prevent recurrence.\n");
                sb.append("    NEVER use `git add -A` or `git add .` to\n");
                sb.append("    stage cascade bump commits — these sweep\n");
                sb.append("    such leaks into main. Use explicit paths:\n");
                sb.append("    `git add pom.xml`. ike-issues#358.");
            }
            return Optional.of(sb.toString());
        }
    },

    /**
     * No {@code <properties>} entry in any subproject root POM may hold a
     * value ending in {@code -SNAPSHOT}. Maven 4's consumer POM flattener
     * resolves properties and promotes {@code <pluginManagement>} into
     * {@code <plugins>} when writing the released artifact — a SNAPSHOT
     * property value would then be baked in as a literal and break
     * downstream consumers (e.g. {@code <ike-tooling.version>112-SNAPSHOT}
     * leaking into released {@code ike-parent-105.pom}). This check
     * forces the release operator to bump the property to a released
     * version before cutting the release.
     */
    NO_SNAPSHOT_PROPERTIES("No subproject root POM carries -SNAPSHOT property values") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<SnapshotScanner.Violation> all = new ArrayList<>();

            for (String name : ctx.subprojects()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (!pom.isFile()) continue;
                all.addAll(SnapshotScanner.scanSourceProperties(pom));
            }

            if (all.isEmpty()) return Optional.empty();

            return Optional.of(SnapshotScanner.formatViolations(all, root,
                    all.size() + " SNAPSHOT property value(s) would leak into"
                            + " released POMs:",
                    "  These values are substituted by Maven 4's consumer POM\n"
                    + "  flattener and baked into released artifacts. Bump each\n"
                    + "  property to a released (non-SNAPSHOT) version before\n"
                    + "  re-running the release."));
        }
    },

    /**
     * No {@code .mvn/jvm.config} file in the workspace root or any
     * subproject may contain a line starting with {@code #}.
     *
     * <p>Maven parses {@code .mvn/jvm.config} as raw JVM arguments —
     * one token per line, with no comment syntax. A {@code #} at
     * column 0 is passed to the JVM launcher as a main-class name and
     * IntelliJ surfaces it as
     * {@code Error: Could not find or load main class #}. The fix is
     * to delete the offending line; comments belong in
     * {@code .mvn/jvm.config.notes} or similar adjacent files.
     *
     * <p>This is the gate referenced in ike-issues#217. The check fires
     * before the bad file can propagate to git or Syncthing — Maven's
     * own {@code validate} phase can't catch this in the project that
     * contains the bad file because the JVM dies before plugin code
     * runs.
     */
    JVM_CONFIG_NO_HASH_COMMENTS(
            "No .mvn/jvm.config file contains a # comment line") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> violations = new ArrayList<>();

            collectJvmConfigViolations(root, WORKSPACE_ROOT_NAME, violations);
            for (String name : ctx.subprojects()) {
                collectJvmConfigViolations(new File(root, name), name,
                        violations);
            }

            if (violations.isEmpty()) return Optional.empty();

            var sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" .mvn/jvm.config file(s) contain # comment lines:\n");
            for (String v : violations) {
                sb.append("    • ").append(v).append('\n');
            }
            sb.append("  Maven parses .mvn/jvm.config as raw JVM arguments\n");
            sb.append("  — '#' at column 0 is passed to the JVM launcher as a\n");
            sb.append("  main-class name and crashes the build. Delete the\n");
            sb.append("  offending line; put commentary in an adjacent file.");
            return Optional.of(sb.toString());
        }
    },

    /**
     * Every workspace subproject's root POM must either declare
     * {@code <distributionManagement>} locally or have a {@code <parent>}
     * block to inherit from (#346, surfaced by #343 when {@code its/}
     * was missing both).
     *
     * <p>Site goals — specifically {@code site:stage}, which the
     * workspace release cascade runs — fail with
     * <em>"Missing distribution management in project ..."</em> when
     * neither is present. That failure surfaces deep inside the
     * release flow, after some subprojects have already tagged. This
     * preflight catches the missing declaration upfront so the
     * release-draft is authoritative.
     */
    SUBPROJECT_HAS_DISTRIBUTION_MANAGEMENT(
            "Every subproject root POM resolves <distributionManagement>") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> violations = new ArrayList<>();

            for (String name : ctx.subprojects()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (!pom.isFile()) continue;
                String content;
                try {
                    content = Files.readString(pom.toPath(),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    // Best-effort — preflight does not fail on read errors
                    continue;
                }
                if (!hasDistributionManagementOrParent(content)) {
                    violations.add(name + " (no <distributionManagement> "
                            + "or <parent> in its root POM)");
                }
            }

            if (violations.isEmpty()) return Optional.empty();

            var sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" subproject(s) missing <distributionManagement>:\n");
            for (String v : violations) {
                sb.append("    • ").append(v).append('\n');
            }
            sb.append("  site:stage during the workspace release cascade\n");
            sb.append("  will fail with \"Missing distribution management in\n");
            sb.append("  project ...\". Either declare\n");
            sb.append("  <distributionManagement><site>...</site>... directly\n");
            sb.append("  on the subproject's root POM, or add a <parent>\n");
            sb.append("  block inheriting from one of the IKE parents (e.g.\n");
            sb.append("  network.ike.platform:ike-parent).");
            return Optional.of(sb.toString());
        }
    },

    /**
     * No subproject's {@code <properties>} block may declare a
     * locally-overriding value for any IKE-foundation property name
     * (#346, surfaced by the {@code its/} pom property-shadowing in
     * the v150 cascade).
     *
     * <p>Foundation properties (
     * {@code ike-tooling.version},
     * {@code ike-docs.version},
     * {@code ike-platform.version}) are set by {@code ike-parent}'s
     * inheritance chain. Local overrides silently shadow the
     * workspace's intended versions and pin plugins to old releases
     * that may lack newer goals. Preferred discipline: namespace
     * local overrides under {@code it.*}, {@code local.*}, or a
     * project-specific prefix that doesn't collide with the
     * foundation set.
     */
    NO_FOUNDATION_PROPERTY_SHADOWING(
            "No subproject overrides ike-foundation property names") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            List<String> violations = new ArrayList<>();

            for (String name : ctx.subprojects()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (!pom.isFile()) continue;
                String content;
                try {
                    content = Files.readString(pom.toPath(),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                for (String prop : FOUNDATION_PROPERTY_NAMES) {
                    if (shadowsProperty(content, prop)) {
                        violations.add(name + "/pom.xml shadows <" + prop
                                + "> (inherited from ike-parent)");
                    }
                }
            }

            if (violations.isEmpty()) return Optional.empty();

            var sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" IKE-foundation property shadow(s):\n");
            for (String v : violations) {
                sb.append("    • ").append(v).append('\n');
            }
            sb.append("  These property names control plugin resolution for\n");
            sb.append("  every project inheriting ike-parent. Local overrides\n");
            sb.append("  pin plugins to whatever version the subproject\n");
            sb.append("  declares, often pre-dating goals the release cycle\n");
            sb.append("  expects (e.g. ike-tooling 126 lacks #335's\n");
            sb.append("  render-spdx-licenses). Rename the local property to\n");
            sb.append("  an `it.*` / `local.*` namespace and update any\n");
            sb.append("  references to use the new name.");
            return Optional.of(sb.toString());
        }
    },

    /**
     * When a subproject's {@code <parent>} declares the same GA as the
     * workspace aggregator's own {@code <parent>}, enforce the two
     * coherence rules from #324:
     *
     * <ol>
     *   <li>{@code <parent><version>} matches the workspace's.</li>
     *   <li>{@code <parent>} block includes an empty
     *       {@code <relativePath/>} to prevent the Maven 4
     *       parent-cycle error.</li>
     * </ol>
     *
     * <p>This preflight is the release-gate analog of
     * {@code ws:verify}'s coherence check (#324). The verify check
     * warns; the preflight blocks. Composed via Preflight so
     * release-draft surfaces it as a warning and release-publish
     * promotes it to a hard error.
     */
    PARENT_COHERENCE(
            "Subprojects sharing the workspace's parent GA have "
                    + "matching version + <relativePath/>") {
        @Override
        public Optional<String> check(PreflightContext ctx) {
            File root = ctx.workspaceRoot();
            File workspacePom = new File(root, "pom.xml");
            if (!workspacePom.isFile()) return Optional.empty();

            String workspaceContent;
            try {
                workspaceContent = Files.readString(workspacePom.toPath(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                return Optional.empty();
            }

            String wsParentGA = extractParentGa(workspaceContent);
            String wsParentVersion = extractParentVersion(workspaceContent);
            if (wsParentGA == null) return Optional.empty();

            List<String> violations = new ArrayList<>();

            for (String name : ctx.subprojects()) {
                File pom = new File(new File(root, name), "pom.xml");
                if (!pom.isFile()) continue;
                String content;
                try {
                    content = Files.readString(pom.toPath(),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                String subParentGA = extractParentGa(content);
                String subParentVersion = extractParentVersion(content);
                if (subParentGA == null
                        || !subParentGA.equals(wsParentGA)) {
                    continue; // out of scope — different parent
                }

                if (wsParentVersion != null
                        && !wsParentVersion.equals(subParentVersion)) {
                    violations.add(name + " parent " + subParentGA + ":"
                            + subParentVersion + " != workspace " + wsParentGA
                            + ":" + wsParentVersion + " (#324)");
                    continue;
                }

                if (!network.ike.plugin.ws.PomParentSupport
                        .hasEmptyRelativePathInContent(content)) {
                    violations.add(name + " parent " + subParentGA + ":"
                            + subParentVersion + " missing empty "
                            + "<relativePath/> (#324 cycle prevention)");
                }
            }

            if (violations.isEmpty()) return Optional.empty();

            var sb = new StringBuilder();
            sb.append(violations.size())
                    .append(" parent-coherence violation(s):\n");
            for (String v : violations) {
                sb.append("    • ").append(v).append('\n');
            }
            sb.append("  Subprojects sharing the workspace's parent GA must\n");
            sb.append("  agree on parent version AND declare an empty\n");
            sb.append("  <relativePath/> to prevent the Maven 4\n");
            sb.append("  \"parents form a cycle\" error. ws:verify reports\n");
            sb.append("  the same coherence check as a warning; the release\n");
            sb.append("  gate promotes it to a hard requirement.");
            return Optional.of(sb.toString());
        }
    };

    /** Special marker used when the workspace root itself has uncommitted changes. */
    public static final String WORKSPACE_ROOT_NAME = "workspace root";

    private final String description;

    PreflightCondition(String description) {
        this.description = description;
    }

    /** Short human description of what this condition enforces. */
    public String description() {
        return description;
    }

    /**
     * Evaluate the condition against the given context.
     *
     * @param ctx the preflight context
     * @return {@link Optional#empty()} if the condition is satisfied;
     *         a remediation message otherwise
     */
    public abstract Optional<String> check(PreflightContext ctx);

    // ── Shared helpers ──────────────────────────────────────────────

    static String gitStatus(File dir) {
        try {
            return ReleaseSupport.execCapture(dir,
                    "git", "status", "--porcelain").trim();
        } catch (MojoException e) {
            return "";
        }
    }

    /**
     * Return {@code true} when the given {@code git status --porcelain}
     * output mentions paths matching a gh-pages-style site output
     * layout: {@code <something>/<same>/<site files>} doubling, or
     * {@code examples/<name>/<site files>}. The signal files we look
     * for ({@code bom.json}, {@code built-with.html},
     * {@code dependencies.html}, {@code dependency-management.html},
     * {@code distribution-management.html}, {@code project-info.html})
     * are emitted by maven-project-info-reports-plugin during
     * site:stage and don't belong in source control.
     *
     * <p>Used by {@link #WORKING_TREE_CLEAN} to amplify the diagnostic
     * when an operator is about to bump a workspace whose main tree
     * contains leaked gh-pages content. ike-issues#358.
     *
     * @param porcelain raw {@code git status --porcelain} output
     * @return {@code true} when at least one entry matches
     */
    static boolean containsGhPagesLeakPattern(String porcelain) {
        if (porcelain == null || porcelain.isBlank()) return false;
        for (String line : porcelain.split("\n")) {
            String path = line.length() > 3 ? line.substring(3).trim() : "";
            if (path.endsWith("/bom.json")
                    || path.endsWith("/built-with.html")
                    || path.endsWith("/dependencies.html")
                    || path.endsWith("/dependency-management.html")
                    || path.endsWith("/distribution-management.html")
                    || path.endsWith("/project-info.html")) {
                return true;
            }
        }
        return false;
    }

    /**
     * If {@code dir/.mvn/jvm.config} exists and contains any line whose
     * first non-empty character is {@code #}, append one entry per
     * offending line to {@code accum}. Format:
     * {@code <displayName>/.mvn/jvm.config:<lineNo>: <text>}.
     *
     * <p>Empty lines and whitespace-only lines are ignored. Quietly
     * tolerates {@link IOException} — preflight is best-effort and
     * shouldn't fail the gate over a transient read error.
     *
     * @param dir         the directory whose {@code .mvn/jvm.config} to scan
     * @param displayName name shown in the violation list
     *                    ({@link #WORKSPACE_ROOT_NAME} for the workspace
     *                    root, otherwise the subproject name)
     * @param accum       accumulator for formatted violation strings
     */
    static void collectJvmConfigViolations(File dir, String displayName,
                                           List<String> accum) {
        Path config = dir.toPath().resolve(".mvn").resolve("jvm.config");
        if (!Files.isRegularFile(config)) return;
        try {
            List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.stripLeading();
                if (trimmed.startsWith("#")) {
                    accum.add(displayName + "/.mvn/jvm.config:"
                            + (i + 1) + ": " + line);
                }
            }
        } catch (IOException e) {
            // Best-effort — preflight does not fail on read errors.
        }
    }

    /**
     * IKE-foundation property names that
     * {@link #NO_FOUNDATION_PROPERTY_SHADOWING} flags as illegal local
     * overrides.
     */
    static final List<String> FOUNDATION_PROPERTY_NAMES = List.of(
            "ike-tooling.version",
            "ike-docs.version",
            "ike-platform.version");

    /**
     * Return {@code true} when the POM content contains either a
     * {@code <distributionManagement>} block or a {@code <parent>}
     * block. Used by
     * {@link #SUBPROJECT_HAS_DISTRIBUTION_MANAGEMENT} — site:stage
     * needs at least one of these to resolve the deploy target.
     *
     * @param pomContent the POM XML as a string
     * @return {@code true} when at least one block is present
     */
    static boolean hasDistributionManagementOrParent(String pomContent) {
        if (pomContent == null) return false;
        return DISTRIBUTION_MGMT_OR_PARENT.matcher(pomContent).find();
    }

    /**
     * Return {@code true} when the POM has a {@code <properties>} block
     * containing an element whose tag name equals {@code propertyName}.
     * Tolerates whitespace inside the tag value (including blank).
     *
     * @param pomContent   the POM XML as a string
     * @param propertyName the property tag name to scan for
     * @return {@code true} when the property is declared at the
     *         project's top-level {@code <properties>}
     */
    static boolean shadowsProperty(String pomContent, String propertyName) {
        if (pomContent == null || propertyName == null) return false;
        // Scope to <properties>...</properties> at top level — a
        // <properties> nested inside a plugin <configuration> isn't
        // a shadowing site.
        var matcher = TOP_LEVEL_PROPERTIES_BLOCK.matcher(pomContent);
        while (matcher.find()) {
            String block = matcher.group(1);
            if (java.util.regex.Pattern
                    .compile("<" + java.util.regex.Pattern.quote(propertyName)
                            + "\\b")
                    .matcher(block).find()) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern DISTRIBUTION_MGMT_OR_PARENT =
            java.util.regex.Pattern.compile(
                    "(?s)<distributionManagement\\b|<parent\\b");

    /**
     * Extract the parent {@code groupId:artifactId} from a POM string,
     * or {@code null} when no {@code <parent>} block is present.
     *
     * @param pomContent the POM XML
     * @return {@code "groupId:artifactId"} or {@code null}
     */
    static String extractParentGa(String pomContent) {
        if (pomContent == null) return null;
        var parentMatcher = java.util.regex.Pattern.compile(
                "(?s)<parent\\b[^>]*>(.*?)</parent>").matcher(pomContent);
        if (!parentMatcher.find()) return null;
        String block = parentMatcher.group(1);
        var gMatch = java.util.regex.Pattern.compile(
                "<groupId>\\s*([^<]+?)\\s*</groupId>").matcher(block);
        var aMatch = java.util.regex.Pattern.compile(
                "<artifactId>\\s*([^<]+?)\\s*</artifactId>").matcher(block);
        if (!gMatch.find() || !aMatch.find()) return null;
        return gMatch.group(1).trim() + ":" + aMatch.group(1).trim();
    }

    /**
     * Extract the parent {@code <version>} from a POM string, or
     * {@code null} when no {@code <parent>} block is present or it
     * lacks an explicit version.
     *
     * @param pomContent the POM XML
     * @return the version string or {@code null}
     */
    static String extractParentVersion(String pomContent) {
        if (pomContent == null) return null;
        var parentMatcher = java.util.regex.Pattern.compile(
                "(?s)<parent\\b[^>]*>(.*?)</parent>").matcher(pomContent);
        if (!parentMatcher.find()) return null;
        String block = parentMatcher.group(1);
        var vMatch = java.util.regex.Pattern.compile(
                "<version>\\s*([^<]+?)\\s*</version>").matcher(block);
        if (!vMatch.find()) return null;
        return vMatch.group(1).trim();
    }

    /**
     * Match the project's top-level {@code <properties>} block.
     * Anchored to follow either {@code </artifactId>},
     * {@code </version>}, or {@code </name>} so it doesn't match
     * nested {@code <properties>} inside plugin {@code <configuration>}
     * blocks. Best-effort — handles the common shape of IKE POMs.
     */
    private static final java.util.regex.Pattern TOP_LEVEL_PROPERTIES_BLOCK =
            java.util.regex.Pattern.compile(
                    "(?s)<properties>\\s*(.*?)\\s*</properties>");
}
