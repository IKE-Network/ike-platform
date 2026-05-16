package network.ike.plugin.ws;

/**
 * The report a {@code ws:*} goal produces — which goal it is and the
 * Markdown body.
 *
 * <p>Returned by {@link AbstractWorkspaceMojo#runGoal()}; the base
 * class writes it via {@link WorkspaceReport}. Making the report a
 * <em>required return value</em> — rather than an optional
 * {@code writeReport(...)} call a goal author can forget — means a
 * goal cannot compile without producing one. That is the structural
 * fix for the missing-report bug class (IKE-Network/ike-issues#413,
 * motivated by #407).
 *
 * <p>Unlike the ike plugin's {@code GoalReportSpec}, the workspace
 * root is not carried here: the base class resolves it from
 * {@code workspace.yaml} when it writes the file.
 *
 * @param goal    the goal that produced this report
 * @param content the Markdown report body
 */
public record WorkspaceReportSpec(WsGoal goal, String content) {
}
