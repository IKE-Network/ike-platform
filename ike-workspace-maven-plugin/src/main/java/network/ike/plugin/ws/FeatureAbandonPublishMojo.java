package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Execute a feature branch abandonment with confirmation.
 *
 * <p>This is the publish variant of {@link FeatureAbandonDraftMojo}.
 * It prompts for confirmation (unless {@code -Dforce=true}), then
 * deletes the feature branch across all subprojects.
 *
 * <pre>{@code
 * mvn ws:feature-abandon-publish                     # with confirmation
 * mvn ws:feature-abandon-publish -Dforce=true        # skip confirmation
 * mvn ws:feature-abandon-publish -DdeleteRemote=true # also delete remote branches
 * }</pre>
 *
 * @see FeatureAbandonDraftMojo for the preview (draft) variant
 */
@Mojo(name = "feature-abandon-publish", projectRequired = false)
public class FeatureAbandonPublishMojo extends FeatureAbandonDraftMojo {

    /** Creates this goal instance. */
    public FeatureAbandonPublishMojo() {}

    @Override
    public void execute() throws MojoException {
        this.publish = true;
        super.execute();
    }
}
