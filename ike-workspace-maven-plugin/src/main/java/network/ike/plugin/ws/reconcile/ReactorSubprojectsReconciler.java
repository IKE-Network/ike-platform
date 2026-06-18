package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.ReactorPom;
import network.ike.plugin.ws.ReactorPom.ProfileEntry;
import network.ike.plugin.ws.WsGoal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reconciler that keeps the workspace <em>reactor</em> POM's subproject
 * membership in lockstep with {@code workspace.yaml}, and migrates the
 * legacy {@code with-<name>} file-activated profile pattern to
 * unconditional top-level {@code <subprojects>} entries
 * (IKE-Network/ike-issues#696, completing #460).
 *
 * <p>Before this reconciler, the only path that added a member to the
 * reactor POM was {@code ws:add}, one profile at a time. A subproject
 * that entered {@code workspace.yaml} by any other route — feature-finish
 * YAML reconciliation, bulk import, a hand edit — silently dropped out of
 * the reactor, and IntelliJ (which imports the workspace root) never saw
 * it. There was no convergence step to catch the drift.
 *
 * <p>Each {@code ws:scaffold-{draft,publish}} run now converges the
 * reactor POM to:
 * <ol>
 *   <li>a top-level {@code <subprojects>} block listing exactly the
 *       {@code workspace.yaml} keys, in declaration order — adding the
 *       missing, removing the stale; and</li>
 *   <li>zero {@code with-*} subproject profiles — every one is retired,
 *       its member already declared top-level.</li>
 * </ol>
 *
 * <p>Top-level declarations are safe even for not-yet-cloned subprojects:
 * {@code ike-workspace-extension}'s {@code SubprojectPruneTransformer}
 * prunes entries whose directory is absent at model-read time (#460).
 *
 * <p>Pure normalization — no version bumps, no clones. {@code detect}
 * reports the convergence; {@code apply} performs it. Idempotent: a second
 * run is a no-op. Opt out with {@code -DupdateReactor=false}.
 *
 * @see ReactorPom
 */
public class ReactorSubprojectsReconciler implements Reconciler {

    /** Creates the reconciler. */
    public ReactorSubprojectsReconciler() {}

    @Override
    public String dimension() {
        return "Reactor subprojects";
    }

    @Override
    public String optOutFlag() {
        return "updateReactor";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        Path pom = ctx.workspaceRoot().toPath().resolve("pom.xml");
        if (!Files.exists(pom)) {
            return DriftReport.noDrift(dimension());
        }
        String content;
        try {
            content = Files.readString(pom, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ctx.log().warn("  Reactor subprojects: could not read "
                    + "pom.xml: " + e.getMessage());
            return DriftReport.noDrift(dimension());
        }

        List<String> yamlKeys = subprojectKeys(ctx);
        String converged = converge(content, yamlKeys);
        if (converged.equals(content)) {
            return DriftReport.noDrift(dimension());
        }

        List<String> detail = describe(content, yamlKeys);
        String summary = detail.size() + " reactor-membership change(s)";
        String action = "rewrite the reactor POM's <subprojects> and retire "
                + "with-* profiles on scaffold-publish";
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
        Path pom = ctx.workspaceRoot().toPath().resolve("pom.xml");
        if (!Files.exists(pom)) {
            return;
        }
        try {
            String content = Files.readString(pom, StandardCharsets.UTF_8);
            String converged = converge(content, subprojectKeys(ctx));
            if (converged.equals(content)) {
                return;
            }
            Files.writeString(pom, converged, StandardCharsets.UTF_8);
            ctx.log().info("  " + dimension() + ": reactor POM converged to "
                    + "workspace.yaml membership");
        } catch (IOException e) {
            ctx.log().warn("  Reactor subprojects: could not update "
                    + "pom.xml: " + e.getMessage());
        }
    }

    // ── Core transform (shared by detect and apply) ─────────────────

    /**
     * Converge a reactor POM to the target membership: declare exactly
     * {@code yamlKeys} top-level, then retire every legacy subproject
     * profile. Pure — returns the input unchanged when already converged.
     *
     * @param pomContent the reactor POM text
     * @param yamlKeys   the {@code workspace.yaml} subproject keys, in order
     * @return the converged POM text
     */
    static String converge(String pomContent, List<String> yamlKeys) {
        String result = ReactorPom.setSubprojects(pomContent, yamlKeys);
        for (ProfileEntry profile : ReactorPom.listSubprojectProfiles(result)) {
            if (profile.id() != null) {
                result = ReactorPom.removeProfile(result, profile.id());
            }
        }
        return result;
    }

    /**
     * Build the human-readable drift detail for {@code detect}: which
     * members migrate from a profile, which are newly added, which stale
     * entries are dropped, and which profiles are retired.
     */
    private static List<String> describe(String pomContent,
                                         List<String> yamlKeys) {
        List<String> top = ReactorPom.listSubprojects(pomContent);
        List<ProfileEntry> profiles =
                ReactorPom.listSubprojectProfiles(pomContent);
        Set<String> topSet = new LinkedHashSet<>(top);
        Set<String> profileMembers = new LinkedHashSet<>();
        for (ProfileEntry p : profiles) {
            profileMembers.addAll(p.subprojects());
        }
        Set<String> yamlSet = new LinkedHashSet<>(yamlKeys);

        List<String> detail = new ArrayList<>();
        for (String key : yamlKeys) {
            if (topSet.contains(key)) {
                continue;
            }
            if (profileMembers.contains(key)) {
                detail.add("migrate profile member → <subprojects>: " + key);
            } else {
                detail.add("add subproject: " + key);
            }
        }
        for (String s : top) {
            if (!yamlSet.contains(s)) {
                detail.add("remove stale subproject: " + s);
            }
        }
        for (ProfileEntry p : profiles) {
            detail.add("retire profile: "
                    + (p.id() != null ? p.id() : p.subprojects()));
        }
        return detail;
    }

    /**
     * The {@code workspace.yaml} subproject keys in declaration order.
     */
    private static List<String> subprojectKeys(WorkspaceContext ctx) {
        return new ArrayList<>(
                ctx.graph().manifest().subprojects().keySet());
    }
}
