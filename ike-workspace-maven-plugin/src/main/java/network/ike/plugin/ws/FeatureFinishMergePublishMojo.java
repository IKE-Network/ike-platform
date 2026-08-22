package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Execute a no-fast-forward merge of a feature branch.
 *
 * <p>This is the {@code -publish} counterpart of
 * {@code ws:feature-finish-merge} (which defaults to a draft preview).
 *
 * <p>Usage: {@code mvn ws:feature-finish-merge-publish -Dfeature=long-running}
 *
 * @see FeatureFinishMergeDraftMojo
 */
@Mojo(name = "feature-finish-merge-publish", projectRequired = false, aggregator = true)
public class FeatureFinishMergePublishMojo extends FeatureFinishMergeDraftMojo {

    /** Creates this goal instance. */
    public FeatureFinishMergePublishMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        confirmWorkingSetLease();
        publish = true;
        WorkspaceReportSpec spec;
        // A sibling's merge landing absorbs into the parent exactly as the
        // squash does (FinishLanding unifies the two), so the parent's
        // lease is short-held here too (#992, #1005).
        try (ParentLeaseHold hold =
                ParentLeaseHold.acquire(workspaceRoot(), getLog())) {
            spec = super.runGoal();
            PostMutationSync.refresh(workspaceRoot(), getLog());
        }
        return spec;
    }
}
