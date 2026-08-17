package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Record a member's release: transition it to the tag-aligned
 * {@code release} state in workspace.yaml and append its row to the
 * mission's {@code releases/release-<mission>.yaml}, in one root-repo
 * commit (IKE-Network/ike-issues#973).
 *
 * <p>Publish variant of {@link WsRecordReleaseDraftMojo} — requires
 * {@code -Dmember} and {@code -Dmission}; see that goal for the full
 * contract and the draft preview.
 *
 * <pre>{@code
 * mvn ws:record-release-publish -Dmember=komet-bom -Dmission=komet-wsr-1
 * }</pre>
 */
@Mojo(name = "record-release-publish", projectRequired = false,
        aggregator = true)
public class WsRecordReleasePublishMojo extends WsRecordReleaseDraftMojo {

    /** Creates this goal instance. */
    public WsRecordReleasePublishMojo() {}

    /**
     * Run in publish mode. The flag is set here — after Maven's
     * parameter injection — because injection applies the inherited
     * {@code publish} parameter's {@code defaultValue="false"} AFTER
     * construction: a constructor assignment is silently overwritten
     * and the goal degrades to a draft (shipped as that defect in
     * platform 152; the harness bypasses injection, so only a real
     * Maven run showed it). Mirrors {@code WsAlignPublishMojo}.
     *
     * @return the goal report
     * @throws MojoException if the goal fails
     */
    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        confirmWorkingSetLease();
        publish = true;
        return super.runGoal();
    }
}
