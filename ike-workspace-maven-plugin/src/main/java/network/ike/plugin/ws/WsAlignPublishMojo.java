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
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
