package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Apply scaffold per subproject (#350).
 *
 * <p>The {@code -publish} counterpart of
 * {@link WsScaffoldDraftMojo ws:scaffold-draft}. Walks every cloned
 * subproject in workspace.yaml (and the workspace root) and invokes
 * {@code ike:scaffold-publish} in each. The
 * {@code -Dike.scaffold.apply-foundation=true} flag is forwarded
 * unchanged.
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ws:scaffold-publish
 * mvn ws:scaffold-publish -Dike.scaffold.apply-foundation=true
 * }</pre>
 *
 * @see WsScaffoldDraftMojo
 */
@Mojo(name = "scaffold-publish", projectRequired = false, aggregator = true)
public class WsScaffoldPublishMojo extends WsScaffoldDraftMojo {

    /** Creates this goal instance. */
    public WsScaffoldPublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
