package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

/**
 * Apply the aggregator parent version cascade and commit the result
 * across the workspace.
 *
 * <p>This is the {@code -publish} counterpart of
 * {@link WsSetParentDraftMojo ws:set-parent-draft}. It updates the root
 * POM and all subproject POMs to the specified parent version, then
 * commits with the default message {@code "build: bump <parent> to <N>"}.
 * Use {@code -Dmessage="..."} to override the message, or
 * {@code -DnoCommit} to inspect the edits before committing manually
 * (positive-form flag per the compiler-visibility principle).
 *
 * <pre>{@code
 * mvn ws:set-parent-publish -Dparent.version=92                # edit + auto-commit
 * mvn ws:set-parent-publish -Dparent.version=92 -DnoCommit     # edit only
 * mvn ws:set-parent-publish -Dparent.version=92 \
 *     -Dmessage="build: ike-parent 92 (security fix CVE-1234)"
 * }</pre>
 *
 * <p>See issue #196 and the {@code dev-workspace-ops-completion} topic
 * in {@code ike-lab-documents} for the design rationale: workspace
 * orchestration goals should leave the developer at a stopping point,
 * not mid-operation.
 *
 * @see WsSetParentDraftMojo
 */
@Mojo(name = "set-parent-publish", projectRequired = false, aggregator = true)
public class WsSetParentPublishMojo extends WsSetParentDraftMojo {

    /** Creates this goal instance. */
    public WsSetParentPublishMojo() {}

    /**
     * Skip the auto-commit step and leave the POM edits uncommitted
     * for manual inspection. Default {@code false} — the goal commits
     * by default per the workspace-orchestration completion principle.
     */
    @Parameter(property = "noCommit", defaultValue = "false")
    boolean noCommit;

    /**
     * Override the auto-commit message. When unset, the goal commits
     * with {@code "build: bump <parent-artifactId> to <version>"}.
     * Has no effect when {@link #noCommit} is set.
     */
    @Parameter(property = "message")
    String commitMessage;

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();

        if (noCommit || resolvedChangeCount == 0) {
            return;
        }

        String message = (commitMessage != null && !commitMessage.isBlank())
                ? commitMessage
                : "build: bump " + resolvedParentArtifactId
                        + " to " + parentVersion;

        CommitMojo commit = new CommitMojo();
        commit.setLog(getLog());
        commit.manifest = this.manifest;
        commit.message = message;
        commit.execute();
    }
}
