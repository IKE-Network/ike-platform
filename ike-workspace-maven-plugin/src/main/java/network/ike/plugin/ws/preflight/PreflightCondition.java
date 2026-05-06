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
            for (String name : uncommitted) {
                File dir = WORKSPACE_ROOT_NAME.equals(name)
                        ? root : new File(root, name);
                String files = gitStatus(dir).lines()
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
}
