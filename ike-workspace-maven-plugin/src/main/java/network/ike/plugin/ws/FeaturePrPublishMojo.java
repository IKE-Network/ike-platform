package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Open cross-linked review pull requests for a feature branch.
 *
 * <p>This is the {@code -publish} counterpart of
 * {@code ws:feature-pr-draft}: it pushes the branch where needed, opens the
 * pull requests, and rewrites their bodies with the sibling links, rather
 * than previewing all of it.
 *
 * <p>Usage: {@code mvn ws:feature-pr-publish -Dfeature=grpc_plugin}
 *
 * @see FeaturePrDraftMojo
 */
@Mojo(name = "feature-pr-publish", projectRequired = false, aggregator = true)
public class FeaturePrPublishMojo extends FeaturePrDraftMojo {

    /** Creates this goal instance. */
    public FeaturePrPublishMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        publish = true;
        return super.runGoal();
    }
}
