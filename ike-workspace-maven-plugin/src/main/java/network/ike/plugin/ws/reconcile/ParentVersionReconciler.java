package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.PomParentSupport;
import network.ike.plugin.ws.PomParentSupport.ParentInfo;
import network.ike.workspace.Subproject;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconciler that cascades the workspace root POM's
 * {@code <parent>} version across every cloned subproject (and any
 * nested submodule POMs whose parent block matches the same
 * {@code groupId:artifactId}).
 *
 * <p>Subsumes the retired {@code ws:set-parent-{draft,publish}}
 * goals (IKE-Network/ike-issues#393). The source of truth for the
 * target parent version is the workspace root POM's
 * {@code <parent><version>}. To pin to a specific (non-root-declared)
 * version, pass {@code -DparentVersion=<v>}; the reconciler then
 * also updates the root POM before cascading.
 *
 * <p>External-trigger drift dimension: a new ike-parent release
 * lands → the user updates the root POM (or passes
 * {@code -DparentVersion=}) → next scaffold-publish cascades to
 * every subproject.
 */
public class ParentVersionReconciler implements Reconciler {

    @Override
    public String dimension() {
        return "Parent version cascade";
    }

    @Override
    public String optOutFlag() {
        return "updateParent";
    }

    @Override
    public String pinFlag() {
        return "parentVersion";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        Plan plan = computePlan(ctx);
        if (plan == null || plan.changes().isEmpty()) {
            return DriftReport.noDrift(dimension());
        }
        List<String> detail = new ArrayList<>();
        for (Map.Entry<String, FieldChange> e : plan.changes().entrySet()) {
            detail.add(e.getKey() + ": "
                    + plan.parentArtifactId() + ":" + e.getValue().before()
                    + " → " + e.getValue().after());
        }
        String summary = plan.changes().size()
                + " POM(s) need parent " + plan.parentArtifactId()
                + " cascaded to " + plan.targetVersion();
        String action = "cascade to " + plan.targetVersion()
                + " on scaffold-publish";
        String optOut = "mvn ws:scaffold-publish -D" + optOutFlag() + "=false";
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
        Plan plan = computePlan(ctx);
        if (plan == null || plan.changes().isEmpty()) {
            return;
        }
        try {
            // Update root POM first if its current parent version doesn't
            // already match the target — covers the pin (-DparentVersion=)
            // case where root POM also needs to be updated.
            Path rootPomPath = ctx.workspaceRoot().toPath().resolve("pom.xml");
            ParentInfo rootParent = PomParentSupport.readParent(rootPomPath);
            if (rootParent != null
                    && !plan.targetVersion().equals(rootParent.version())) {
                updateOnePom(rootPomPath, plan.parentGroupId(),
                        plan.parentArtifactId(), plan.targetVersion());
            }
            // Then cascade to each subproject (and its submodules).
            for (String name : plan.changes().keySet()) {
                File subDir = new File(ctx.workspaceRoot(), name);
                cascadeIntoSubproject(subDir, plan);
            }
            ctx.log().info("  " + dimension() + ": cascaded to "
                    + plan.changes().size() + " subproject(s)");
        } catch (IOException e) {
            throw new MojoException(
                    "Parent cascade failed: " + e.getMessage(), e);
        }
    }

    // ── Plan computation (shared by detect and apply) ───────────────

    /**
     * "Before → after" pair for one subproject's parent version.
     * Compiler-visible alternative to a positional {@code String[]}.
     */
    private record FieldChange(String before, String after) {}

    /**
     * The full cascade plan for one reconciliation pass.
     *
     * @param parentGroupId    workspace root's parent groupId
     * @param parentArtifactId workspace root's parent artifactId
     * @param targetVersion    target parent version to cascade
     * @param changes          subproject name → before/after for those
     *                         whose parent version doesn't match target
     */
    private record Plan(
            String parentGroupId,
            String parentArtifactId,
            String targetVersion,
            Map<String, FieldChange> changes) {}

    private Plan computePlan(WorkspaceContext ctx) {
        Path rootPomPath = ctx.workspaceRoot().toPath().resolve("pom.xml");
        if (!Files.exists(rootPomPath)) {
            return null;
        }
        ParentInfo rootParent;
        try {
            rootParent = PomParentSupport.readParent(rootPomPath);
        } catch (IOException e) {
            ctx.log().warn("  Cannot read root POM parent: " + e.getMessage());
            return null;
        }
        if (rootParent == null) {
            return null;
        }

        // Target version: -DparentVersion=<v> pin overrides root POM.
        String targetVersion = ctx.options().pin(pinFlag())
                .orElse(rootParent.version());

        Map<String, FieldChange> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Subproject> entry
                : ctx.graph().manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Path subprojectPom = new File(ctx.workspaceRoot(), name)
                    .toPath().resolve("pom.xml");
            if (!Files.exists(subprojectPom)) continue;
            ParentInfo subParent;
            try {
                subParent = PomParentSupport.readParent(subprojectPom);
            } catch (IOException e) {
                ctx.log().warn("  " + name + ": cannot read parent — "
                        + e.getMessage());
                continue;
            }
            if (subParent == null) continue;
            // Skip subprojects with an unrelated parent GAV (see #241).
            if (!rootParent.groupId().equals(subParent.groupId())
                    || !rootParent.artifactId().equals(subParent.artifactId())) {
                continue;
            }
            if (!targetVersion.equals(subParent.version())) {
                changes.put(name, new FieldChange(
                        subParent.version(), targetVersion));
            }
        }
        return new Plan(rootParent.groupId(), rootParent.artifactId(),
                targetVersion, changes);
    }

    private static void cascadeIntoSubproject(File subDir, Plan plan)
            throws IOException {
        Path subprojectPom = subDir.toPath().resolve("pom.xml");
        if (!Files.exists(subprojectPom)) return;

        // Update the subproject's root POM.
        updateOnePom(subprojectPom, plan.parentGroupId(),
                plan.parentArtifactId(), plan.targetVersion());

        // Then update any submodule POMs that reference the same parent
        // GAV (matches WsSetParentDraftMojo's submodule cascade).
        List<File> subPoms;
        try {
            subPoms = ReleaseSupport.findPomFiles(subDir);
        } catch (MojoException e) {
            // Non-fatal — submodule scan failures don't roll back the
            // root POM update.
            return;
        }
        for (File subPom : subPoms) {
            if (subPom.toPath().equals(subprojectPom)) continue;
            String content = Files.readString(subPom.toPath(),
                    StandardCharsets.UTF_8);
            String updated = PomParentSupport.updateParentVersion(
                    content, plan.parentGroupId(),
                    plan.parentArtifactId(), plan.targetVersion());
            if (!updated.equals(content)) {
                Files.writeString(subPom.toPath(), updated,
                        StandardCharsets.UTF_8);
            }
        }
    }

    private static void updateOnePom(Path pomPath, String parentGroupId,
                                      String parentArtifactId,
                                      String newVersion) throws IOException {
        String content = Files.readString(pomPath, StandardCharsets.UTF_8);
        String updated = PomParentSupport.updateParentVersion(
                content, parentGroupId, parentArtifactId, newVersion);
        if (!updated.equals(content)) {
            Files.writeString(pomPath, updated, StandardCharsets.UTF_8);
        }
    }
}
