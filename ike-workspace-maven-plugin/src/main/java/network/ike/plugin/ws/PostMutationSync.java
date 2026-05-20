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

    /**
     * Run all post-mutation derivations against the workspace at
     * {@code workspaceRoot}.
     *
     * @param workspaceRoot the workspace root directory
     * @param log           plugin log for status messages
     */
    public static void refresh(File workspaceRoot, Log log) {
        YamlDepsSync.run(workspaceRoot, log);
    }
}
