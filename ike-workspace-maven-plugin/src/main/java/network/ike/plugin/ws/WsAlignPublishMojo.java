package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Apply inter-subproject version alignment.
 *
 * <p>This is the {@code -publish} counterpart of
 * {@code ws:align-draft} (which previews changes without writing them).
 *
 * <p>Usage: {@code mvn ws:align-publish}
 *
 * @see WsAlignDraftMojo
 */
@Mojo(name = "align-publish", projectRequired = false, aggregator = true)
public class WsAlignPublishMojo extends WsAlignDraftMojo {

    /** Creates this goal instance. */
    public WsAlignPublishMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        publish = true;
        WorkspaceReportSpec spec = super.runGoal();
        PostMutationSync.refresh(workspaceRoot(), getLog());
        return spec;
    }
}
