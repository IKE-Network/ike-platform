package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Execute a squash-merge of a feature branch.
 *
 * <p>This is the {@code -publish} counterpart of
 * {@code ws:feature-finish-squash} (which defaults to a draft preview).
 *
 * <p>Usage: {@code mvn ws:feature-finish-squash-publish -Dfeature=done -Dmessage="Ship it"}
 *
 * @see FeatureFinishSquashDraftMojo
 */
@Mojo(name = "feature-finish-squash-publish", projectRequired = false)
public class FeatureFinishSquashPublishMojo extends FeatureFinishSquashDraftMojo {

    /** Creates this goal instance. */
    public FeatureFinishSquashPublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
