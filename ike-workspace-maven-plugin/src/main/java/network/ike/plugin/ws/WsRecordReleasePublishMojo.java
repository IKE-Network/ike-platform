package network.ike.plugin.ws;

import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Record a member's release: transition it to the tag-aligned
 * {@code release} state in workspace.yaml and append its row to the
 * cycle's {@code releases/release-<cycle>.yaml}, in one root-repo
 * commit (IKE-Network/ike-issues#973).
 *
 * <p>Publish variant of {@link WsRecordReleaseDraftMojo} — requires
 * {@code -Dmember} and {@code -Dcycle}; see that goal for the full
 * contract and the draft preview.
 *
 * <pre>{@code
 * mvn ws:record-release-publish -Dmember=komet-bom -Dcycle=komet-wsr-1
 * }</pre>
 */
@Mojo(name = "record-release-publish", projectRequired = false,
        aggregator = true)
public class WsRecordReleasePublishMojo extends WsRecordReleaseDraftMojo {

    /** Creates this goal instance in publish mode. */
    public WsRecordReleasePublishMojo() {
        this.publish = true;
    }
}
