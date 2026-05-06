package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Apply branch reconciliation against on-disk git state (publish).
 *
 * <p>This is the {@code -publish} counterpart of
 * {@link WsReconcileBranchesDraftMojo} (which previews changes
 * without writing them). See ike-issues#200 for the two-axis split
 * rationale.
 *
 * <p>Usage: {@code mvn ws:reconcile-branches-publish}
 */
@Mojo(name = "reconcile-branches-publish", projectRequired = false, aggregator = true)
public class WsReconcileBranchesPublishMojo extends WsReconcileBranchesDraftMojo {

    /** Creates this goal instance. */
    public WsReconcileBranchesPublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
