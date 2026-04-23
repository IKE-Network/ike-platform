package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;

/**
 * Start a feature branch with auto-alignment.
 *
 * <p>This is the {@code -publish} counterpart of {@code ws:feature-start}
 * (which defaults to a draft preview). Before creating feature branches,
 * this goal automatically aligns inter-subproject dependency versions so
 * that the feature branch starts from a consistent state.
 *
 * <p>Usage: {@code mvn ws:feature-start-publish -Dfeature=my-feature}
 *
 * @see FeatureStartDraftMojo
 */
@Mojo(name = "feature-start-publish", projectRequired = false)
public class FeatureStartPublishMojo extends FeatureStartDraftMojo {

    /** Creates this goal instance. */
    public FeatureStartPublishMojo() {}

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
