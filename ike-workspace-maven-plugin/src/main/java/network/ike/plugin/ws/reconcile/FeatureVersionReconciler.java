package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.WsGoal;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.Subproject;
import network.ike.workspace.VersionSupport;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reconciler that branch-qualifies each subproject's <em>own</em> Maven
 * version to match the branch it tracks (IKE-Network/ike-issues#574).
 *
 * <p>On a feature branch every subproject's version must carry the
 * branch slug ({@code <base>-<slug>-SNAPSHOT}) so feature builds are
 * isolated from main SNAPSHOTs. {@code ws:feature-start} applies this
 * once, at branch-creation; a subproject added <em>later</em> (via
 * {@code ws:add}) — or otherwise left un-qualified — stays un-isolated
 * and its feature build collides with its own main SNAPSHOT. This
 * reconciler self-heals that on {@code scaffold-publish}.
 *
 * <p>Scope is each subproject's own {@code <version>} (its POM) plus the
 * denormalized {@code version} field in {@code workspace.yaml}.
 * Propagating the new version into <em>consumers'</em> dependency
 * references is left to {@link AlignmentReconciler}, which runs after
 * this one in {@link ReconcilerRegistry}.
 *
 * <p>The target branch is read from the manifest ({@code branch:} per
 * subproject), not git, so the reconciler is deterministic and
 * branch-coherent. It is a no-op for {@code main}-tracking subprojects
 * and for members already qualified —
 * {@link VersionSupport#branchQualifiedVersion} strips any existing slug
 * before re-applying, so repeated runs converge.
 */
public class FeatureVersionReconciler implements Reconciler {

    @Override
    public String dimension() {
        return "Feature-branch version qualification";
    }

    @Override
    public String optOutFlag() {
        return "updateFeatureVersions";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        List<Change> changes = computeChanges(ctx);
        if (changes.isEmpty()) {
            return DriftReport.noDrift(dimension());
        }
        List<String> detail = new ArrayList<>();
        for (Change c : changes) {
            detail.add(c.name() + ": " + c.before() + " → " + c.after()
                    + " (" + c.branch() + ")");
        }
        String summary = changes.size()
                + " subproject version(s) not branch-qualified";
        String action = "qualify each to its branch slug on scaffold-publish";
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
        try {
            // Rewrite each subproject's own POM <version>, then mirror the
            // new value into workspace.yaml's denormalized version field in
            // a single pass (FieldNormalizationReconciler runs *before* this
            // one, so it won't re-sync the manifest from the rewritten POM).
            Path manifestPath = ctx.manifestPath();
            String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);
            for (Change c : changes) {
                File pom = new File(
                        new File(ctx.workspaceRoot(), c.name()), "pom.xml");
                rewriteOwnVersion(pom.toPath(), c.before(), c.after());
                yaml = ManifestWriter.updateSubprojectField(
                        yaml, c.name(), "version", c.after());
            }
            Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
            ctx.log().info("  " + dimension() + ": qualified "
                    + changes.size() + " subproject version(s)");
        } catch (IOException e) {
            throw new MojoException(
                    "Feature-version qualification failed: " + e.getMessage(), e);
        }
    }

    // ── Change computation (shared by detect and apply) ─────────────

    /**
     * One subproject's version qualification.
     *
     * @param name   the subproject name
     * @param branch the branch it tracks (from the manifest)
     * @param before its current POM version
     * @param after  the branch-qualified version
     */
    private record Change(String name, String branch,
                          String before, String after) {}

    private List<Change> computeChanges(WorkspaceContext ctx) {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, Subproject> entry
                : ctx.graph().manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            String branch = entry.getValue().branch();
            // Only feature branches qualify; main keeps the plain version.
            if (branch == null || branch.isBlank() || "main".equals(branch)) {
                continue;
            }
            File pom = new File(new File(ctx.workspaceRoot(), name), "pom.xml");
            if (!pom.exists()) {
                continue;
            }
            String current;
            try {
                current = ReleaseSupport.readPomVersion(pom);
            } catch (MojoException e) {
                ctx.log().warn("  " + name + ": cannot read version — "
                        + e.getMessage());
                continue;
            }
            if (current == null || current.isBlank()) {
                continue;
            }
            String qualified =
                    VersionSupport.branchQualifiedVersion(current, branch);
            if (!qualified.equals(current)) {
                changes.add(new Change(name, branch, current, qualified));
            }
        }
        return changes;
    }

    /**
     * Rewrite a POM's own {@code <version>} from {@code oldVersion} to
     * {@code newVersion}. Searches past the {@code <parent>} block first,
     * so a parent whose version coincidentally equals {@code oldVersion}
     * is not mistaken for the project version (the project {@code <version>}
     * precedes {@code <dependencies>}, so the first match after
     * {@code </parent>} is the project's own). Public — also reused by
     * {@code ws:add}'s add-time qualification (#574).
     *
     * @param pom        the POM path
     * @param oldVersion the current project version
     * @param newVersion the qualified version
     * @throws IOException if the POM cannot be read or written
     */
    public static void rewriteOwnVersion(Path pom, String oldVersion,
                                  String newVersion) throws IOException {
        String content = Files.readString(pom, StandardCharsets.UTF_8);
        int searchFrom = 0;
        int parentEnd = content.indexOf("</parent>");
        if (parentEnd >= 0) {
            searchFrom = parentEnd + "</parent>".length();
        }
        String needle = "<version>" + oldVersion + "</version>";
        int idx = content.indexOf(needle, searchFrom);
        if (idx < 0) {
            return;
        }
        String updated = content.substring(0, idx)
                + "<version>" + newVersion + "</version>"
                + content.substring(idx + needle.length());
        if (!updated.equals(content)) {
            Files.writeString(pom, updated, StandardCharsets.UTF_8);
        }
    }
}
