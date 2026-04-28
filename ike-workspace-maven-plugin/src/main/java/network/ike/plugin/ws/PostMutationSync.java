package network.ike.plugin.ws;

import org.apache.maven.api.plugin.Log;

import java.io.File;

/**
 * Refresh workspace state derived from POMs and on-disk siblings.
 * Called at the end of any goal whose effect can change which siblings
 * are present on disk or what their POMs declare.
 *
 * <p>Combines two independent derivations into one call:
 * <ul>
 *   <li>{@link IdeProfileSync} — writes the {@code -P} block in
 *       {@code .mvn/maven.config} so IntelliJ activates the right
 *       {@code with-*} profiles for the current sibling set.</li>
 *   <li>{@link YamlDepsSync} — re-derives each subproject's
 *       {@code depends-on} edges from POM contents and rewrites
 *       {@code workspace.yaml} when the graph has drifted.</li>
 * </ul>
 *
 * <p>Each step is idempotent — running this hook back-to-back produces
 * no further changes. Failures in one step are logged at WARN and do
 * not stop the other.
 *
 * <p>Triggered from: {@code ws:add}, {@code ws:remove}, {@code ws:sync},
 * {@code ws:pull}, {@code ws:commit}, {@code ws:init},
 * {@code ws:feature-finish-merge-publish},
 * {@code ws:feature-finish-squash-publish},
 * {@code ws:align-publish}, {@code ws:set-parent-publish},
 * {@code ws:versions-upgrade-publish}.
 *
 * <p>See {@code IKE-Network/ike-issues#279}.
 */
final class PostMutationSync {

    private PostMutationSync() {}

    /**
     * Run all post-mutation derivations against the workspace at
     * {@code workspaceRoot}.
     *
     * @param workspaceRoot the workspace root directory
     * @param log           plugin log for status messages
     */
    static void refresh(File workspaceRoot, Log log) {
        IdeProfileSync.run(workspaceRoot, log);
        YamlDepsSync.run(workspaceRoot, log);
    }
}
