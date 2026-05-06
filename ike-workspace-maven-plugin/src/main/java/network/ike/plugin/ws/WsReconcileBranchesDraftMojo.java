package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Reconcile {@code workspace.yaml} branch fields against on-disk git
 * state (preview).
 *
 * <p>This is the {@code ws:reconcile-branches-draft} goal — recovery /
 * rare-use, separated from {@link WsAlignDraftMojo}'s POM-axis daily
 * driver per ike-issues#200's two-axis split (Option B). Each goal
 * name now describes its audience: {@code ws:align} is the safe daily
 * POM convergence; {@code ws:reconcile-branches} is the
 * branch-state recovery operation that runs when something has
 * already gone wrong.
 *
 * <p>Three directions are supported via {@code -Dfrom=...}:
 *
 * <ul>
 *   <li>{@code repos} (default) — read each subproject's actual branch
 *       and update {@code workspace.yaml} to match.</li>
 *   <li>{@code manifest} — {@code git checkout} each subproject to the
 *       branch declared in {@code workspace.yaml}.</li>
 *   <li>{@code workspace-head} — the workspace repo's HEAD is
 *       authoritative; reconcile both YAML fields <em>and</em> on-disk
 *       branches to that single value (ike-issues#287).</li>
 * </ul>
 *
 * <pre>{@code
 * mvn ws:reconcile-branches-draft                       # report only (from=repos)
 * mvn ws:reconcile-branches-publish                      # apply (from=repos)
 * mvn ws:reconcile-branches-publish -Dfrom=manifest      # checkout repos to declared branches
 * mvn ws:reconcile-branches-publish -Dfrom=workspace-head -Dforce=true
 * }</pre>
 */
@Mojo(name = "reconcile-branches-draft", projectRequired = false, aggregator = true)
public class WsReconcileBranchesDraftMojo extends WsAlignDraftMojo {

    /** Creates this goal instance. */
    public WsReconcileBranchesDraftMojo() {}

    @Override
    protected boolean isBranchScopeAllowed() {
        return true;
    }

    @Override
    public void execute() throws MojoException {
        // The shared implementation in WsAlignDraftMojo dispatches on
        // scope/from. Force the branch-only path; from= remains user-tunable.
        scope = "branches";
        super.execute();
    }
}
