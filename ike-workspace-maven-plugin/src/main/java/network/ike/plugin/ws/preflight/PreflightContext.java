package network.ike.plugin.ws.preflight;

import network.ike.workspace.WorkspaceGraph;

import java.io.File;
import java.util.List;

/**
 * Data that {@link PreflightCondition#check(PreflightContext)} invocations
 * may need. Each condition pulls only the fields it uses — context
 * fields that aren't relevant for a given invocation may be {@code null}.
 *
 * <p>New fields are added here as new preflight conditions are introduced
 * that need them. Keep the record flat: a condition that grows a private
 * parameter should pass it through this record rather than through a
 * back-channel.
 *
 * @param workspaceRoot the workspace root directory
 * @param graph         the loaded workspace graph (may be {@code null}
 *                      for conditions that operate on raw files)
 * @param subprojects   subproject names (in topological order) to evaluate
 * @param branchName    target branch for branch-oriented checks
 *                      (e.g. {@code "feature/my-feature"}), or {@code null}
 * @param tagName       target tag name for tag-oriented checks
 *                      (checkpoint, release), or {@code null}
 * @param parentVersion target parent version for set-parent checks,
 *                      or {@code null}
 */
public record PreflightContext(
        File workspaceRoot,
        WorkspaceGraph graph,
        List<String> subprojects,
        String branchName,
        String tagName,
        String parentVersion) {

    /** Minimal context for conditions that only need root + subproject list. */
    public static PreflightContext of(File root,
                                       WorkspaceGraph graph,
                                       List<String> subprojects) {
        return new PreflightContext(root, graph, subprojects, null, null, null);
    }
}
