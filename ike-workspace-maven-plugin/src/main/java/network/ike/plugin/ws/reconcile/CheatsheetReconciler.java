package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.bootstrap.SubprojectInitializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the workspace cheatsheets — {@code GOALS.md} and
 * {@code WS-REFERENCE.md} — in lockstep with the goal set the plugin
 * actually ships (IKE-Network/ike-issues#452).
 *
 * <p>Before this reconciler, those files were written only by
 * {@link SubprojectInitializer}, which is constructed by exactly one
 * mojo ({@code ws:scaffold-init}). After a plugin upgrade the
 * cheatsheets stayed stale until someone remembered to re-run
 * {@code ws:scaffold-init} — and {@code ws:scaffold-draft} did not
 * even report the drift. Routing cheatsheet generation through the
 * reconciler chain restores the "draft reports / publish heals"
 * contract every other workspace dimension already enjoys.
 *
 * <p>Source-of-truth lives in
 * {@link SubprojectInitializer#generateGoalCheatsheet()} and
 * {@link SubprojectInitializer#generateWorkspaceReference()} — both
 * already static so this reconciler can call them without any
 * additional plumbing. A future iteration of those generators is
 * planned to iterate {@code WsGoal} so the content cannot drift from
 * the actual goal set.
 *
 * <p>Opt-out: {@code -DupdateCheatsheets=false}. Useful when the user
 * has intentionally edited the cheatsheets and does not want
 * {@code ws:scaffold-publish} to overwrite them.
 */
public class CheatsheetReconciler implements Reconciler {

    static final String GOALS_FILE = "GOALS.md";
    static final String REFERENCE_FILE = "WS-REFERENCE.md";

    @Override
    public String dimension() {
        return "Workspace cheatsheets (GOALS.md, WS-REFERENCE.md)";
    }

    @Override
    public String optOutFlag() {
        return "updateCheatsheets";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        Path root = ctx.workspaceRoot().toPath();
        List<String> stale = staleFiles(root);
        if (stale.isEmpty()) {
            return DriftReport.noDrift(dimension());
        }
        List<String> detail = new ArrayList<>();
        for (String name : stale) {
            detail.add(name + ": content drifted from generator");
        }
        String summary = stale.size() == 1
                ? "1 cheatsheet stale"
                : stale.size() + " cheatsheets stale";
        return new DriftReport(
                dimension(),
                true,
                summary,
                detail,
                "regenerate " + String.join(" and ", stale),
                "-D" + optOutFlag() + "=false");
    }

    @Override
    public void apply(WorkspaceContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension()
                    + ": skipped (opted out via -D" + optOutFlag() + "=false)");
            return;
        }
        Path root = ctx.workspaceRoot().toPath();
        List<String> stale = staleFiles(root);
        if (stale.isEmpty()) {
            return;
        }
        int written = 0;
        for (String name : stale) {
            String generated = generatorFor(name);
            try {
                Files.writeString(root.resolve(name), generated,
                        StandardCharsets.UTF_8);
                written++;
            } catch (IOException e) {
                ctx.log().warn("  " + dimension()
                        + ": could not write " + name + " — " + e.getMessage());
            }
        }
        if (written > 0) {
            ctx.log().info("  " + dimension()
                    + ": regenerated " + written + " file(s)");
        }
    }

    /**
     * Return the cheatsheet filenames whose on-disk content differs
     * from the generator's current output (or that are missing
     * entirely). A missing file counts as stale so a fresh workspace
     * that never ran {@code ws:scaffold-init} still gets one written
     * by {@code ws:scaffold-publish}.
     */
    private static List<String> staleFiles(Path workspaceRoot) {
        List<String> stale = new ArrayList<>();
        if (!matchesGenerator(workspaceRoot.resolve(GOALS_FILE),
                SubprojectInitializer.generateGoalCheatsheet())) {
            stale.add(GOALS_FILE);
        }
        if (!matchesGenerator(workspaceRoot.resolve(REFERENCE_FILE),
                SubprojectInitializer.generateWorkspaceReference())) {
            stale.add(REFERENCE_FILE);
        }
        return stale;
    }

    private static boolean matchesGenerator(Path file, String expected) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            return existing.equals(expected);
        } catch (IOException e) {
            // Unreadable file is treated as drifted — apply will then
            // attempt to overwrite it (which surfaces the underlying
            // IO error to the user explicitly via the warn path above).
            return false;
        }
    }

    private static String generatorFor(String filename) {
        return switch (filename) {
            case GOALS_FILE -> SubprojectInitializer.generateGoalCheatsheet();
            case REFERENCE_FILE -> SubprojectInitializer.generateWorkspaceReference();
            default -> throw new IllegalArgumentException(
                    "Unknown cheatsheet filename: " + filename);
        };
    }
}
