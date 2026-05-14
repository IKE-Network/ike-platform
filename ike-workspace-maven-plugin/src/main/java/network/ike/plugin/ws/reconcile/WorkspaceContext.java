package network.ike.plugin.ws.reconcile;

import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.Log;

import java.io.File;
import java.nio.file.Path;

/**
 * Context handed to each {@link Reconciler} for {@code detect} and
 * {@code apply}. Bundles the workspace's filesystem location, parsed
 * manifest graph, user-supplied flags, and a logger.
 *
 * @param workspaceRoot the workspace root directory
 * @param manifestPath  the path to {@code workspace.yaml}
 * @param graph         the parsed workspace graph
 * @param options       user-supplied flag values
 * @param log           Maven logger for reconciler output
 */
public record WorkspaceContext(
        File workspaceRoot,
        Path manifestPath,
        WorkspaceGraph graph,
        ReconcilerOptions options,
        Log log) {
}
