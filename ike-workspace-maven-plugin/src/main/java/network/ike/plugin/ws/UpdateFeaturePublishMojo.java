package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Execute a feature branch update from main.
 *
 * <p>This is the publish variant of {@link UpdateFeatureDraftMojo}.
 * It performs the actual rebase or merge rather than previewing it.
 *
 * <pre>{@code
 * mvn ws:update-feature-publish                    # rebase (default)
 * mvn ws:update-feature-publish -Dstrategy=merge   # merge main into feature
 * }</pre>
 *
 * @see UpdateFeatureDraftMojo for the preview (draft) variant
 */
@Mojo(name = "update-feature-publish", projectRequired = false)
public class UpdateFeaturePublishMojo extends UpdateFeatureDraftMojo {

    /** Creates this goal instance. */
    public UpdateFeaturePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        this.publish = true;
        super.execute();
    }
}
