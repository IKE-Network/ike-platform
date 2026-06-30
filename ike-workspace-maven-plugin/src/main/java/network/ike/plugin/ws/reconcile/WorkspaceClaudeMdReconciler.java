package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.bootstrap.SubprojectInitializer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Keeps the workspace-root {@code CLAUDE.md} in lockstep with its
 * generator (IKE-Network/ike-issues#790).
 *
 * <p>The cheatsheets ({@code GOALS.md}, {@code WS-REFERENCE.md}) were
 * promoted to continuous reconciliation by {@link CheatsheetReconciler}
 * (#452), but the generated workspace-root {@code CLAUDE.md} was left
 * behind: it is written only at the tail of {@link SubprojectInitializer}'s
 * initial {@code ws:scaffold-init} pass. A workspace that exists without a
 * {@code CLAUDE.md} — created before the generator landed, or renamed —
 * therefore never gets one, because nothing backfills it. This reconciler
 * closes that gap so {@code ws:scaffold-draft} reports the drift and
 * {@code ws:scaffold-publish} heals it, exactly like the cheatsheets.
 *
 * <p>Only the fully-generated {@code CLAUDE.md} is managed here. Hand-authored
 * notes live in {@code CLAUDE-<ws>.md}, which {@link SubprojectInitializer}
 * creates if absent and never overwrites — so they are intentionally outside
 * this reconciler's scope.
 *
 * <p>Source-of-truth lives in
 * {@link SubprojectInitializer#generateWorkspaceClaudeMd(String, network.ike.workspace.WorkspaceGraph)},
 * already static so this reconciler can call it without additional plumbing.
 *
 * <p>Opt-out: {@code -DupdateClaudeMd=false}. Useful when the user has
 * intentionally edited {@code CLAUDE.md} and does not want
 * {@code ws:scaffold-publish} to overwrite it.
 */
public class WorkspaceClaudeMdReconciler implements Reconciler {

    static final String CLAUDE_FILE = "CLAUDE.md";

    @Override
    public String dimension() {
        return "Workspace CLAUDE.md";
    }

    @Override
    public String optOutFlag() {
        return "updateClaudeMd";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        if (isUpToDate(ctx)) {
            return DriftReport.noDrift(dimension());
        }
        Path file = ctx.workspaceRoot().toPath().resolve(CLAUDE_FILE);
        String reason = Files.exists(file)
                ? CLAUDE_FILE + ": content drifted from generator"
                : CLAUDE_FILE + ": missing";
        return new DriftReport(
                dimension(),
                true,
                CLAUDE_FILE + " stale",
                List.of(reason),
                "regenerate " + CLAUDE_FILE,
                "-D" + optOutFlag() + "=false");
    }

    @Override
    public void apply(WorkspaceContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension()
                    + ": skipped (opted out via -D" + optOutFlag() + "=false)");
            return;
        }
        if (isUpToDate(ctx)) {
            return;
        }
        Path file = ctx.workspaceRoot().toPath().resolve(CLAUDE_FILE);
        try {
            Files.writeString(file, generated(ctx), StandardCharsets.UTF_8);
            ctx.log().info("  " + dimension() + ": regenerated " + CLAUDE_FILE);
        } catch (IOException e) {
            ctx.log().warn("  " + dimension()
                    + ": could not write " + CLAUDE_FILE + " — " + e.getMessage());
        }
    }

    /**
     * Whether the on-disk {@code CLAUDE.md} exists and already matches the
     * generator's current output. A missing file counts as stale so a
     * workspace that never had one still gets it written by
     * {@code ws:scaffold-publish}.
     */
    private static boolean isUpToDate(WorkspaceContext ctx) {
        Path file = ctx.workspaceRoot().toPath().resolve(CLAUDE_FILE);
        if (!Files.exists(file)) {
            return false;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).equals(generated(ctx));
        } catch (IOException e) {
            // Unreadable file is treated as drifted — apply then attempts to
            // overwrite it, surfacing the underlying IO error via its warn path.
            return false;
        }
    }

    private static String generated(WorkspaceContext ctx) {
        return SubprojectInitializer.generateWorkspaceClaudeMd(
                workspaceName(ctx), ctx.graph());
    }

    /**
     * Derive the workspace name the same way {@code ws:scaffold-init} does —
     * from the root POM's own {@code <artifactId>} — so the regenerated
     * heading matches what the initial scaffold wrote and a correct file is
     * never falsely flagged as drifted. (The manifest's typed
     * {@code workspace-root} GAV is optional and absent in older workspaces,
     * so the POM is the reliable source.)
     *
     * @param ctx the workspace context
     * @return the workspace name, or {@code "Workspace"} if the POM cannot
     *         be read
     */
    private static String workspaceName(WorkspaceContext ctx) {
        File rootPom = new File(ctx.workspaceRoot(), "pom.xml");
        if (rootPom.exists()) {
            try {
                return ReleaseSupport.readPomArtifactId(rootPom);
            } catch (Exception e) {
                // Fall through to the stable default.
            }
        }
        return "Workspace";
    }
}
