package network.ike.plugin.ws;

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
        // Snapshot the manifest's state BEFORE the re-derivation. If it was
        // unmodified, any change the derivation makes is attributable solely
        // to it and is committed in isolation; if it already carried an edit
        // (or there is no git state — e.g. .git excluded from Syncthing on a
        // not-yet-bootstrapped sibling) it is excluded from the snapshot, so
        // the combined change is left for the caller / bootstrapper (#774).
        GoalAuthoredChanges authored =
                GoalAuthoredChanges.snapshot(workspaceRoot, log, MANIFEST);

        YamlDepsSync.SyncResult result = YamlDepsSync.run(workspaceRoot, log);
        if (!result.changed()) {
            return false;
        }
        // Enumerate what changed in the commit itself, so a re-derivation
        // that reverses a hand edit is at least visible in history rather
        // than hiding behind a generic subject (#964).
        StringBuilder changes = new StringBuilder();
        for (String line : result.changeLines()) {
            changes.append("- ").append(line).append('\n');
        }
        boolean committed = authored.commitAuthored(
                "ws: re-derive depends-on edges\n\n"
                        + changes
                        + "\nRe-derives workspace.yaml depends-on from POM "
                        + "reality after a workspace mutation, so the "
                        + "manifest is never left uncommitted. Build edges "
                        + "are machine-derived; bundle/content/tooling "
                        + "edges are preserved as hand-declared.\n\n"
                        + "Refs: IKE-Network/ike-issues#774\n"
                        + "Refs: IKE-Network/ike-issues#279\n"
                        + "Refs: IKE-Network/ike-issues#964");
        if (committed) {
            log.info("  workspace.yaml: committed re-derived depends-on edges");
        }
        return committed;
    }
}
