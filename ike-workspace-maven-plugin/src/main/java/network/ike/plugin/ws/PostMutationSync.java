package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.Log;

import java.io.File;

/**
 * Refresh workspace state derived from POMs and on-disk siblings.
 * Called at the end of any goal whose effect can change which siblings
 * are present on disk or what their POMs declare.
 *
 * <p>Currently one derivation:
 * <ul>
 *   <li>{@link YamlDepsSync} — re-derives each subproject's
 *       {@code depends-on} edges from POM contents and rewrites
 *       {@code workspace.yaml} when the graph has drifted.</li>
 * </ul>
 *
 * <p>Earlier revisions also ran {@code IdeProfileSync} here to
 * maintain a {@code -P?with-*} block in {@code .mvn/maven.config}
 * (IKE-Network/ike-issues#276), so IntelliJ would activate the
 * {@code with-*} profiles that scoped subprojects into the reactor.
 * That whole mechanism was retired in
 * {@code IKE-Network/ike-issues#460}: the
 * {@code ike-workspace-extension} prunes non-existent
 * {@code <subprojects>} from workspace POMs at model-read time, so
 * IntelliJ sees the right reactor without any profile activation.
 *
 * <p>The step is idempotent — running this hook back-to-back produces
 * no further changes. Failures are logged at WARN and do not abort
 * the caller.
 *
 * <p><b>Self-committing.</b> {@code workspace.yaml} is machine-maintained,
 * never hand-authored, so a re-derivation it produces must not be left as
 * an uncommitted working-tree change — that strands the change for the
 * next goal's clean-tree preflight to trip over (the reported failure mode
 * in {@code IKE-Network/ike-issues#774}: {@code ws:commit} re-derived
 * {@code depends-on} edges <em>after</em> its commit loop, and the next
 * {@code ws:push}/{@code ws:sync} then refused to run). When the
 * re-derivation is the <em>sole</em> pending change to {@code workspace.yaml}
 * — i.e. the manifest was committed-clean when the hook ran — it is
 * committed here in isolation. When the manifest already carried a
 * caller's own edit (e.g. {@code ws:add} adding a subproject, which
 * deliberately leaves the manifest staged for the user), the combined
 * change is left for that caller's own commit policy and this hook does
 * not commit.
 *
 * <p>Triggered from: {@code ws:add}, {@code ws:remove}, {@code ws:sync},
 * {@code ws:pull}, {@code ws:commit-publish}, {@code ws:scaffold-init},
 * {@code ws:feature-finish-merge-publish},
 * {@code ws:feature-finish-squash-publish},
 * {@code ws:align-publish}, {@code ws:scaffold-publish}
 * (which subsumes the retired ws:set-parent).
 *
 * <p>See {@code IKE-Network/ike-issues#279} (origin) and
 * {@code IKE-Network/ike-issues#460} (IdeProfileSync retirement).
 */
public final class PostMutationSync {

    private PostMutationSync() {}

    /** Path of the machine-maintained manifest, relative to the root. */
    private static final String MANIFEST = "workspace.yaml";

    /**
     * Run all post-mutation derivations against the workspace at
     * {@code workspaceRoot}, committing a re-derived {@code workspace.yaml}
     * in isolation when it is the sole pending change (see the class
     * note on self-committing).
     *
     * @param workspaceRoot the workspace root directory
     * @param log           plugin log for status messages
     * @return {@code true} if a re-derived {@code workspace.yaml} was
     *         committed by this call; {@code false} if nothing changed or
     *         the change was left for the caller to commit
     */
    public static boolean refresh(File workspaceRoot, Log log) {
        // Was the manifest committed-clean before the re-derivation? If so,
        // any change the derivation makes is attributable solely to it and
        // can be committed on its own. If the manifest already carried an
        // edit, leave the combined change for the caller (#774).
        boolean manifestCleanBefore =
                VcsOperations.isPathClean(workspaceRoot, MANIFEST);

        boolean changed = YamlDepsSync.run(workspaceRoot, log);
        // Commit only when the re-derivation is the sole pending change.
        // A false manifestCleanBefore also covers the no-git-state case
        // (e.g. .git excluded from Syncthing on a not-yet-bootstrapped
        // sibling): there git status reports the path as not-clean, so the
        // rewrite is left on disk for whatever later bootstraps the repo.
        if (!changed || !manifestCleanBefore) {
            return false;
        }
        try {
            VcsOperations.commitPaths(workspaceRoot, log,
                    "ws: re-derive depends-on edges\n\n"
                            + "Re-derives workspace.yaml depends-on from POM "
                            + "reality after a workspace mutation, so the "
                            + "manifest is never left uncommitted.\n\n"
                            + "Refs: IKE-Network/ike-issues#774\n"
                            + "Refs: IKE-Network/ike-issues#279",
                    MANIFEST);
            log.info("  workspace.yaml: committed re-derived depends-on edges");
            return true;
        } catch (MojoException e) {
            log.warn("  workspace.yaml: re-derived depends-on edges but could "
                    + "not commit them — " + e.getMessage());
            return false;
        }
    }
}
