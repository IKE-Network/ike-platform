package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Execute a workspace release with per-subproject catch-up alignment.
 *
 * <p>This is the {@code -publish} counterpart of {@code ws:release-draft}
 * (which defaults to a draft preview). The release loop in the parent
 * {@link WsReleaseDraftMojo} performs <em>per-subproject</em> catch-up
 * alignment immediately before each subproject's release: every
 * workspace-internal upstream version reference (parent and version
 * properties) is bumped to the upstream's current target version in a
 * single commit. There is no separate workspace-wide pre-pass — the
 * old {@code autoAlign()} step (which fired {@code ws:align-publish}
 * before the release loop) was removed in #192 because it produced two
 * commits per downstream subproject and silently swallowed alignment
 * failures.
 *
 * <p>If catch-up alignment fails for any subproject, the release halts
 * with a {@link MojoException} naming the failing subproject — never
 * silently continues, never leaves downstream POMs stale.
 *
 * <p>Usage: {@code mvn ws:release-publish}
 *
 * @see WsReleaseDraftMojo
 */
@Mojo(name = "release-publish", projectRequired = false, aggregator = true)
public class WsReleasePublishMojo extends WsReleaseDraftMojo {

    /** Creates this goal instance. */
    public WsReleasePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
