package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Apply workspace scaffold upgrades.
 *
 * <p>This is the {@code -publish} counterpart of
 * {@code ws:scaffold-upgrade-draft} (which defaults to a preview).
 *
 * <p>Usage: {@code mvn ws:scaffold-upgrade-publish}
 *
 * @see WsScaffoldUpgradeDraftMojo
 */
@Mojo(name = "scaffold-upgrade-publish", projectRequired = false)
public class WsScaffoldUpgradePublishMojo extends WsScaffoldUpgradeDraftMojo {

    /** Creates this goal instance. */
    public WsScaffoldUpgradePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
