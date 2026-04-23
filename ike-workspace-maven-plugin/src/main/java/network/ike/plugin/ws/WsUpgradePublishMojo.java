package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Execute workspace convention upgrades.
 *
 * <p>This is the {@code -publish} counterpart of {@code ws:upgrade}
 * (which defaults to a draft preview).
 *
 * <p>Usage: {@code mvn ws:upgrade-publish}
 *
 * @see WsUpgradeDraftMojo
 */
@Mojo(name = "upgrade-publish", projectRequired = false)
public class WsUpgradePublishMojo extends WsUpgradeDraftMojo {

    /** Creates this goal instance. */
    public WsUpgradePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
