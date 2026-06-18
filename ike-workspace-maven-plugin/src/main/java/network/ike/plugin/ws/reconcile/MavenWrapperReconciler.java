package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.MavenWrapper;
import network.ike.plugin.ws.WsGoal;
import network.ike.workspace.Defaults;
import network.ike.workspace.Subproject;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reconciler that keeps each subproject's Maven wrapper pinned to the
 * workspace's declared Maven version (IKE-Network/ike-issues#701).
 *
 * <p>{@code workspace.yaml} declares {@code defaults.maven-version}
 * (e.g. {@code 4.0.0-rc-5}); a subproject may override it with its own
 * {@code maven-version}. Nothing previously enforced that a subproject's
 * {@code .mvn/wrapper/maven-wrapper.properties} actually matches. A
 * subproject onboarded with an older wrapper (e.g. {@code tinkar-schema}
 * carrying Maven 3.9.11) silently stayed pinned to the wrong version,
 * and {@code ws:scaffold-draft}/{@code -publish} then failed because the
 * workspace enforcer requires Maven 4+.
 *
 * <p>This reconciler closes that gap: it reads the pinned version from
 * each subproject's wrapper, compares it against the expected version
 * (subproject override, else {@code defaults.maven-version}), reports
 * drift in draft mode, and rewrites the {@code distributionUrl} (and the
 * legacy {@code maven.version} key, when present) in publish mode.
 *
 * <p>Scope is the <em>version</em> only — {@link ScaffoldConventionReconciler}
 * owns wrapper <em>presence</em> (regenerating missing files and replacing
 * the legacy custom launcher), but only for the workspace root and only
 * when files are absent or legacy; it never re-pins the version of a
 * well-formed subproject wrapper. The two reconcilers are complementary.
 *
 * <p>Subprojects without a wrapper file are skipped (no presence
 * enforcement here). Idempotent: a second run is a no-op. Opt out with
 * {@code -DupdateWrapper=false}.
 *
 * @see MavenWrapper#rewritePinnedVersion
 * @see ScaffoldConventionReconciler
 */
public class MavenWrapperReconciler implements Reconciler {

    /** Creates the reconciler. */
    public MavenWrapperReconciler() {}

    @Override
    public String dimension() {
        return "Maven wrapper version";
    }

    @Override
    public String optOutFlag() {
        return "updateWrapper";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        List<Change> changes = computeChanges(ctx);
        if (changes.isEmpty()) {
            return DriftReport.noDrift(dimension());
        }
        List<String> detail = new ArrayList<>();
        for (Change c : changes) {
            detail.add(c.name() + ": " + c.actual() + " → " + c.expected());
        }
        String summary = changes.size()
                + " wrapper(s) pinned to the wrong Maven version";
        String action = "rewrite each subproject's maven-wrapper.properties "
                + "to the workspace Maven version on scaffold-publish";
        String optOut = "mvn " + WsGoal.SCAFFOLD_PUBLISH.qualified()
                + " -D" + optOutFlag() + "=false";
        return new DriftReport(dimension(), true, summary, detail,
                action, optOut);
    }

    @Override
    public void apply(WorkspaceContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension() + ": skipped (opted out via -D"
                    + optOutFlag() + "=false)");
            return;
        }
        List<Change> changes = computeChanges(ctx);
        if (changes.isEmpty()) {
            return;
        }
        int rewritten = 0;
        for (Change c : changes) {
            Path dir = new File(ctx.workspaceRoot(), c.name()).toPath();
            try {
                if (MavenWrapper.rewritePinnedVersion(dir, c.expected())) {
                    rewritten++;
                }
            } catch (IOException e) {
                throw new MojoException("Maven wrapper reconciliation failed "
                        + "for " + c.name() + ": " + e.getMessage(), e);
            }
        }
        ctx.log().info("  " + dimension() + ": pinned " + rewritten
                + " wrapper(s) to the workspace Maven version");
    }

    // ── Change computation (shared by detect and apply) ─────────────

    /**
     * One subproject's wrapper-version drift.
     *
     * @param name     the subproject name
     * @param actual   the version its wrapper currently pins
     * @param expected the version it should pin (override or default)
     */
    private record Change(String name, String actual, String expected) {}

    /**
     * Compute the wrapper-version drift across all subprojects. The
     * expected version is the subproject's own {@code maven-version}
     * override when set, otherwise {@code defaults.maven-version}.
     * Subprojects with no expected version, no wrapper file, or an
     * unreadable wrapper are skipped.
     *
     * @param ctx the workspace context
     * @return the list of drifted subprojects (empty when converged)
     */
    private List<Change> computeChanges(WorkspaceContext ctx) {
        Defaults defaults = ctx.graph().manifest().defaults();
        String defaultVersion =
                defaults != null ? defaults.mavenVersion() : null;

        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Subproject> entry
                : ctx.graph().manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            String override = entry.getValue().mavenVersion();
            String expected = (override != null && !override.isBlank())
                    ? override : defaultVersion;
            if (expected == null || expected.isBlank()) {
                continue;
            }
            Path dir = new File(ctx.workspaceRoot(), name).toPath();
            Path props = dir.resolve(".mvn").resolve("wrapper")
                    .resolve("maven-wrapper.properties");
            if (!Files.exists(props)) {
                continue;
            }
            String actual;
            try {
                actual = MavenWrapper.readPinnedVersion(dir);
            } catch (IOException e) {
                ctx.log().warn("  " + name + ": cannot read wrapper version — "
                        + e.getMessage());
                continue;
            }
            if (actual == null || actual.isBlank()) {
                continue;
            }
            if (!expected.equals(actual)) {
                changes.add(new Change(name, actual, expected));
            }
        }
        return changes;
    }
}
