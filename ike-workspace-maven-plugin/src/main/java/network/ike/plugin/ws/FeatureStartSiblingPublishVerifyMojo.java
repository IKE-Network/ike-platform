package network.ike.plugin.ws;

import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Start a feature in a <em>sibling workspace clone</em> and then build the
 * sibling's whole reactor to verify it (IKE-Network/ike-issues#777).
 *
 * <p>Identical to {@link FeatureStartSiblingPublishMojo} except the
 * post-create reactor build is on by default — this is the {@code -Dverify}
 * flag promoted to its own goal so it is discoverable in {@code ws:help} and
 * writes its own report file. The build (default {@code clean install
 * -DskipTests -T 1C}, overridable via {@code -Dws.sibling.verifyGoals}) proves
 * the sibling compiles and installs every {@code -<feature>-SNAPSHOT} artifact
 * into the local repository, so a later partial ({@code -pl … -am}) or IDE
 * build can resolve them — the gap that made a freshly created, unbuilt
 * sibling look broken. On a build failure the clone is left intact and the
 * goal fails loud, mirroring {@code ws:checkpoint-publish}'s pre-tag gate.
 *
 * <pre>{@code
 * mvn ws:feature-start-sibling-publish-verify -Dfeature=jira-456
 * #   creates ../<workspace>-jira-456/ on feature/jira-456, then builds the
 * #   whole reactor from its root before handing it back ready to work in.
 * }</pre>
 *
 * @see FeatureStartSiblingPublishMojo for the create logic and the
 *      {@code -Dverify} flag this goal defaults on
 */
@Mojo(name = "feature-start-sibling-publish-verify",
        projectRequired = false, aggregator = true)
public class FeatureStartSiblingPublishVerifyMojo
        extends FeatureStartSiblingPublishMojo {

    /** Creates this goal instance. */
    public FeatureStartSiblingPublishVerifyMojo() {}

    /**
     * {@inheritDoc}
     *
     * @return {@code true} — this goal always builds the sibling reactor
     */
    @Override
    protected boolean verifyByDefault() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link WsGoal#FEATURE_START_SIBLING_PUBLISH_VERIFY} so this goal
     *         reports to its own file
     */
    @Override
    protected WsGoal reportGoal() {
        return WsGoal.FEATURE_START_SIBLING_PUBLISH_VERIFY;
    }
}
