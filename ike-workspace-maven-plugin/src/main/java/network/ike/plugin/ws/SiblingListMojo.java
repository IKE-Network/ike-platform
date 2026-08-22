package network.ike.plugin.ws;

import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.WorkingSet;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * List the primary's sibling working sets — first-class, scriptable
 * visibility for what {@code ls} and eyeballing used to answer
 * (IKE-Network/ike-issues#599).
 *
 * <p>Read-only: no fetch ever touches the network (origins that are
 * remote URLs — the legacy shape — are assessed against their last-fetched
 * refs and flagged), nothing acquires a lease, nothing mutates. Each
 * sibling row carries what matters when deciding its fate:
 * <ul>
 *   <li>the actual branch, flagged when it is not
 *       {@code feature/<suffix>};</li>
 *   <li>member count, bare members called out — a bare tree is
 *       unverifiable and dies silently with a removal;</li>
 *   <li>uncommitted paths, <b>stashes</b> (the one thing no upstream
 *       comparison reveals), and unlanded branches (squash-aware: a
 *       branch whose tree equals an origin tip's tree has landed);</li>
 *   <li>origin conformance — local parent ✓, or the legacy remote-remote
 *       shape awaiting repair (IKE-Network/ike-issues#992, #1057);</li>
 *   <li>the lease line, when this machine runs the lease protocol.</li>
 * </ul>
 *
 * <pre>{@code mvn ws:sibling-list}</pre>
 *
 * @see SiblingRemoveDraftMojo for the removal preview these rows feed
 */
@Mojo(name = "sibling-list", projectRequired = false, aggregator = true)
public class SiblingListMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public SiblingListMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        WorkingSet workingSet = resolveWorkingSet();
        String baseName = workingSet.baseName();
        File primaryRoot = isWorkspaceMode()
                ? workspaceRoot()
                : workingSet.members().getFirst().directory().toFile();

        List<SiblingInventory.Sibling> siblings =
                SiblingInventory.discover(primaryRoot, baseName);

        getLog().info("");
        getLog().info(header("Sibling List"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Primary:  " + primaryRoot.getAbsolutePath());
        getLog().info("  Siblings: " + siblings.size());
        getLog().info("");

        List<String[]> rows = new ArrayList<>();
        for (SiblingInventory.Sibling sibling : siblings) {
            List<SiblingInventory.MemberState> states =
                    SiblingInventory.assess(sibling.root(), true);
            rows.add(row(sibling, states));
            getLog().info("  " + sibling.name()
                    + (sibling.conformant() ? "" : "  [legacy origin]"));
        }

        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Primary:** `" + primaryRoot.getAbsolutePath() + "`");
        if (siblings.isEmpty()) {
            report.paragraph("No siblings. `ws:feature-start-sibling-publish "
                    + "-Dfeature=<name>` creates one beside the primary.");
        } else {
            report.section("Siblings")
                    .table(List.of("Sibling", "Feature", "Branch", "Members",
                                    "Uncommitted", "Stashes", "Unlanded",
                                    "Origin", "Lease"),
                            rows);
            report.paragraph("*Unlanded is squash-aware (a branch whose tree "
                    + "equals an origin tip has landed) and is measured "
                    + "against last-fetched refs — remote-URL origins are "
                    + "never fetched by a listing. `?` = not determinable. "
                    + "Remove a finished sibling with "
                    + "`ws:sibling-remove-draft -Dfeature=<name>`; repair a "
                    + "legacy origin via the IDE notification or the "
                    + "materializer CLI (ike-issues#1057).*");
        }
        return new WorkspaceReportSpec(WsGoal.SIBLING_LIST, report.build());
    }

    private String[] row(SiblingInventory.Sibling sibling,
                         List<SiblingInventory.MemberState> states) {
        long bare = states.stream()
                .filter(SiblingInventory.MemberState::bare).count();
        int uncommitted = states.stream()
                .mapToInt(SiblingInventory.MemberState::uncommitted).sum();
        int stashes = states.stream()
                .mapToInt(SiblingInventory.MemberState::stashes).sum();
        boolean undeterminable = states.stream()
                .anyMatch(state -> state.unlanded() == null && !state.bare());
        long unlanded = states.stream()
                .filter(state -> state.unlanded() != null)
                .mapToLong(state -> state.unlanded().size()).sum();

        String expectedBranch = "feature/" + sibling.feature();
        String rootBranch = states.isEmpty() ? "—" : states.getFirst().branch();
        String branch = rootBranch.equals(expectedBranch)
                ? rootBranch : rootBranch + " (≠ " + expectedBranch + ")";

        String members = states.size()
                + (bare > 0 ? " (" + bare + " bare)" : "");
        String origin = sibling.conformant() ? "local ✓" : "legacy";
        String lease = WorkingSetLease.status(sibling.root().toPath())
                .map(WorkingSetLease.Status::line).orElse("—");
        return new String[]{sibling.name(), sibling.feature(), branch,
                members, String.valueOf(uncommitted),
                String.valueOf(stashes),
                undeterminable ? unlanded + "?" : String.valueOf(unlanded),
                origin, lease};
    }
}
