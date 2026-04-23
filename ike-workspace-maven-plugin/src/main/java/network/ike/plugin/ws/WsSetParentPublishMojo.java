package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Apply the aggregator parent version cascade.
 *
 * <p>This is the {@code -publish} counterpart of
 * {@code ws:set-parent-draft}. It updates the root POM and all
 * subproject POMs to the specified parent version.
 *
 * <pre>{@code
 * mvn ws:set-parent-publish -Dparent.version=92
 * }</pre>
 *
 * @see WsSetParentDraftMojo
 */
@Mojo(name = "set-parent-publish", projectRequired = false)
public class WsSetParentPublishMojo extends WsSetParentDraftMojo {

    /** Creates this goal instance. */
    public WsSetParentPublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
