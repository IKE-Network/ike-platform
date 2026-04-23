package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;

/**
 * Execute a workspace checkpoint with auto-alignment.
 *
 * <p>This is the {@code -publish} counterpart of {@code ws:checkpoint}
 * (which defaults to a draft preview). Before checkpointing, this
 * goal automatically aligns inter-subproject dependency versions.
 *
 * <p>Usage: {@code mvn ws:checkpoint-publish}
 *
 * @see WsCheckpointDraftMojo
 */
@Mojo(name = "checkpoint-publish", projectRequired = false)
public class WsCheckpointPublishMojo extends WsCheckpointDraftMojo {

    /** Creates this goal instance. */
    public WsCheckpointPublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        autoAlign();
        super.execute();
    }

    private void autoAlign() throws MojoException {
        File root = workspaceRoot();
        String mvn = WsReleaseDraftMojo.resolveMvnCommand(root);
        getLog().info("Auto-aligning workspace versions...");
        try {
            ReleaseSupport.exec(root, getLog(), mvn,
                    WsGoal.ALIGN_PUBLISH.qualified(), "-B");
        } catch (MojoException e) {
            getLog().warn("Auto-alignment completed with warnings: "
                    + e.getMessage());
        }
    }
}
