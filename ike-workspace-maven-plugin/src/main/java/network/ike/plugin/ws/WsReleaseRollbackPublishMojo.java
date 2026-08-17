package network.ike.plugin.ws;

import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Roll the working set back from a failed release mission
 * (IKE-Network/ike-issues#1010) — the {@code -publish} counterpart of
 * {@link WsReleaseRollbackDraftMojo}, which documents the semantics:
 * unpushed release-cadence commits and their local tags are discarded
 * per repository, atomically over the set, and pushed history is never
 * touched.
 *
 * <p>Usage: {@code mvn ws:release-rollback-publish}
 */
@Mojo(name = "release-rollback-publish", projectRequired = false,
        aggregator = true)
public class WsReleaseRollbackPublishMojo extends WsReleaseRollbackDraftMojo {

    /** Creates this goal instance in publish mode. */
    public WsReleaseRollbackPublishMojo() {
        publish = true;
    }
}
