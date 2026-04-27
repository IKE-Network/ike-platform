package network.ike.plugin.ws.preflight;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.SnapshotScanner;
import network.ike.plugin.ws.WsGoal;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
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
}
