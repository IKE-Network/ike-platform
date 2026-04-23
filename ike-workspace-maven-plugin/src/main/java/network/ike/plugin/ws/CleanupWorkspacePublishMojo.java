package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Execute workspace cleanup — delete merged feature branches.
 *
 * <p>This is the {@code -publish} counterpart of {@code ws:cleanup}
 * (which defaults to a draft listing).
 *
 * <p>Usage: {@code mvn ws:cleanup-publish}
 *
 * @see CleanupWorkspaceMojo
 */
@Mojo(name = "cleanup-publish", projectRequired = false)
public class CleanupWorkspacePublishMojo extends CleanupWorkspaceMojo {

    /** Creates this goal instance. */
    public CleanupWorkspacePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
