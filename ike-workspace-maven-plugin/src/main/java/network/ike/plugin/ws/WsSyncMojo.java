package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Deprecated alias for {@code ws:align-publish -Dscope=branches}. Kept
 * for backwards compatibility with users who learned the old name
 * before #180 folded branch reconciliation into the {@code ws:align-*}
 * goals. Emits a deprecation warning and delegates to
 * {@link WsAlignDraftMojo} with {@code scope=branches}.
 *
 * <p>This alias will be removed one release cycle after #180 ships.
 *
 * <pre>{@code
 * mvn ws:sync                       # equivalent to: ws:align-publish -Dscope=branches
 * mvn ws:sync -Dpublish=false       # equivalent to: ws:align-draft   -Dscope=branches
 * mvn ws:sync -Dfrom=manifest       # equivalent to: ws:align-publish -Dscope=branches -Dfrom=manifest
 * }</pre>
 */
@Deprecated
@Mojo(name = "sync", projectRequired = false, aggregator = true)
public class WsSyncMojo extends WsAlignDraftMojo {

    /** Creates this goal instance. */
    public WsSyncMojo() {}

    @Override
    public void execute() throws MojoException {
        getLog().warn("ws:sync is deprecated — use ws:align-publish"
                + " -Dscope=branches (see #180). This alias will be"
                + " removed in a future release. Note: the default is"
                + " now draft (preview only); pass -Dpublish=true"
                + " to apply.");
        scope = "branches";
        super.execute();
    }
}
