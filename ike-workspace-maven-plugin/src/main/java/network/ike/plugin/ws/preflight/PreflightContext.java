package network.ike.plugin.ws.preflight;

import network.ike.workspace.WorkspaceGraph;

import java.io.File;
import java.util.List;
import java.util.Set;

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
 * @param releaseSet    names of the members releasing in the current
 *                      cycle, for release-set-aware conditions
 *                      (ike-issues#981) — {@code null} when the invoking
 *                      goal has no release-cycle notion
 * @param cycleReleasedArtifacts {@code groupId:artifactId} keys of the
 *                      artifacts the current cycle's release plan
 *                      de-qualifies — every reference to one of them is
 *                      retargeted by the version pass, so snapshot
 *                      references to them must not refuse the release
 *                      (ike-issues#1022); empty when no cycle is running
 */
public record PreflightContext(
        File workspaceRoot,
        WorkspaceGraph graph,
        List<String> subprojects,
        String branchName,
        String tagName,
        String parentVersion,
        Set<String> releaseSet,
        Set<String> cycleResolvedProperties,
        Set<String> cycleReleasedArtifacts) {

    /** Minimal context for conditions that only need root + subproject list. */
    public static PreflightContext of(File root,
                                       WorkspaceGraph graph,
                                       List<String> subprojects) {
        return new PreflightContext(root, graph, subprojects,
                null, null, null, null, Set.of(), Set.of());
    }

    /**
     * Context for the release preflight — carries this cycle's release
     * set so conditions can exempt what the cycle itself resolves
     * (ike-issues#981).
     *
     * @param root        the workspace root directory
     * @param graph       the loaded workspace graph
     * @param subprojects subproject names (topological order) to evaluate
     * @param releaseSet  names of the members releasing this cycle
     * @return the release-cycle context
     */
    public static PreflightContext of(File root,
                                       WorkspaceGraph graph,
                                       List<String> subprojects,
                                       Set<String> releaseSet) {
        return new PreflightContext(root, graph, subprojects,
                null, null, null, releaseSet, Set.of(), Set.of());
    }

    /**
     * Context for the release preflight, carrying both this cycle's
     * release set and the version properties the cycle's own release
     * plan rewrites. The plan is the authority on what the cycle
     * resolves: a property it de-qualifies never reaches a released
     * POM as a SNAPSHOT, whatever the manifest does or does not
     * declare about it (ike-issues#1004).
     *
     * @param root                     the workspace root directory
     * @param graph                    the loaded workspace graph
     * @param subprojects              subproject names (topological
     *                                 order) to evaluate
     * @param releaseSet               names of the members releasing
     *                                 this cycle
     * @param cycleResolvedProperties  {@code <subproject>::<property>}
     *                                 keys the release plan rewrites
     * @return the release-cycle context
     */
    public static PreflightContext of(File root,
                                       WorkspaceGraph graph,
                                       List<String> subprojects,
                                       Set<String> releaseSet,
                                       Set<String> cycleResolvedProperties) {
        return new PreflightContext(root, graph, subprojects,
                null, null, null, releaseSet,
                cycleResolvedProperties == null
                        ? Set.of() : cycleResolvedProperties, Set.of());
    }

    /**
     * Context for the release preflight, carrying the release set, the
     * version properties the cycle's plan rewrites (ike-issues#1004),
     * and the artifacts the cycle releases (ike-issues#1022). The plan
     * remains the authority on both exemption channels: a property it
     * rewrites and a reference to an artifact it de-qualifies are each
     * resolved by the cycle before anything deploys.
     *
     * @param root                     the workspace root directory
     * @param graph                    the loaded workspace graph
     * @param subprojects              subproject names (topological
     *                                 order) to evaluate
     * @param releaseSet               names of the members releasing
     *                                 this cycle
     * @param cycleResolvedProperties  {@code <subproject>::<property>}
     *                                 keys the release plan rewrites
     * @param cycleReleasedArtifacts   {@code groupId:artifactId} keys
     *                                 the release plan de-qualifies
     * @return the release-cycle context
     */
    public static PreflightContext of(File root,
                                       WorkspaceGraph graph,
                                       List<String> subprojects,
                                       Set<String> releaseSet,
                                       Set<String> cycleResolvedProperties,
                                       Set<String> cycleReleasedArtifacts) {
        return new PreflightContext(root, graph, subprojects,
                null, null, null, releaseSet,
                cycleResolvedProperties == null
                        ? Set.of() : cycleResolvedProperties,
                cycleReleasedArtifacts == null
                        ? Set.of() : cycleReleasedArtifacts);
    }
}
