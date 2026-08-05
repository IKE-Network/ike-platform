package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Adopt an existing feature branch in selected subprojects.
 *
 * <p>This is the {@code -publish} counterpart of
 * {@code ws:feature-track-draft}: it performs the checkouts and writes
 * {@code workspace.yaml} rather than previewing them.
 *
 * <p>Usage:
 * {@code mvn ws:feature-track-publish -Dfeature=grpc_plugin -Daffected=komet,tinkar-service}
 *
 * @see FeatureTrackDraftMojo
 */
@Mojo(name = "feature-track-publish", projectRequired = false,
        aggregator = true)
public class FeatureTrackPublishMojo extends FeatureTrackDraftMojo {

    /** Creates this goal instance. */
    public FeatureTrackPublishMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        publish = true;
        return super.runGoal();
    }
}
