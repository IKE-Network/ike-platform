package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Execute a branch switch across workspace subprojects.
 *
 * <p>This is the publish variant of {@link WsSwitchDraftMojo}.
 * It performs the actual checkout rather than previewing it.
 *
 * <pre>{@code
 * mvn ws:switch-publish                        # interactive
 * mvn ws:switch-publish -Dbranch=feature/foo   # non-interactive
 * }</pre>
 *
 * @see WsSwitchDraftMojo for the preview (draft) variant
 */
@Mojo(name = "switch-publish", projectRequired = false)
public class WsSwitchPublishMojo extends WsSwitchDraftMojo {

    /** Creates this goal instance. */
    public WsSwitchPublishMojo() {}

    @Override
    public void execute() throws MojoException {
        this.publish = true;
        super.execute();
    }
}
