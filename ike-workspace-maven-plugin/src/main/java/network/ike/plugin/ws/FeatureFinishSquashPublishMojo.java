package network.ike.plugin.ws;

import network.ike.workspace.FeatureName;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Execute a squash-merge of a feature branch.
 *
 * <p>This is the {@code -publish} counterpart of
 * {@code ws:feature-finish-squash} (which defaults to a draft preview).
 *
 * <p>Usage: {@code mvn ws:feature-finish-squash-publish -Dfeature=done -Dmessage="Ship it"}
 *
 * <p>{@code -DdeleteSibling=true} (IKE-Network/ike-issues#992, settled
 * 2026-08-22) removes the sibling working set after a successful landing:
 * at that moment everything is landed by construction, so the removal
 * preflight (IKE-Network/ike-issues#600) is re-run as a cheap guard and
 * anything it still finds — a stash, an unrelated branch, a bare tree —
 * keeps the sibling in place with a warning rather than deleting it.
 * There is deliberately no force here; deliberate discard belongs to
 * {@code ws:sibling-remove-publish -Dforce=true}. The lease held by
 * {@link #confirmWorkingSetLease()} covers the deletion, and the finish
 * receipt is persisted to the <em>parent</em> first, because its own home
 * vanishes with the sibling.
 *
 * @see FeatureFinishSquashDraftMojo
 * @see SiblingRemovePublishMojo
 */
@Mojo(name = "feature-finish-squash-publish", projectRequired = false, aggregator = true)
public class FeatureFinishSquashPublishMojo extends FeatureFinishSquashDraftMojo {

    /**
     * Remove the sibling working set after a successful landing. Ignored
     * with a warning when the working set is not a local-origin sibling;
     * skipped with a warning when the post-landing preflight still finds
     * anything that would be lost.
     */
    @Parameter(property = "deleteSibling", defaultValue = "false")
    boolean deleteSibling;

    /** Creates this goal instance. */
    public FeatureFinishSquashPublishMojo() {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        confirmWorkingSetLease();
        publish = true;
        WorkspaceReportSpec spec = super.runGoal();
        PostMutationSync.refresh(workspaceRoot(), getLog());
        if (deleteSibling) {
            spec = deleteSiblingAfterLanding(spec);
        }
        return spec;
    }

    /**
     * The delete-on-finish tail: re-assess, then remove the sibling or
     * explain why it stays.
     *
     * <p>When the sibling is deleted, the framework's own receipt write
     * (into the now-gone sibling root) fails silently by design; the
     * receipt written here into the parent is the durable record.
     *
     * @param spec the finish's report so far
     * @return the report, extended with the removal (or kept) section
     */
    private WorkspaceReportSpec deleteSiblingAfterLanding(
            WorkspaceReportSpec spec) {
        File root = workspaceRoot();
        Optional<File> parent = SiblingFinish.localParent(root);
        boolean named = root.getName()
                .contains(FeatureName.SIBLING_SEPARATOR);
        if (!named || parent.isEmpty()) {
            getLog().warn("  -DdeleteSibling: '" + root.getName()
                    + "' is not a local-origin sibling; nothing is deleted.");
            return spec;
        }

        SiblingRemoval.Target target = SiblingRemoval.assessKnown(root, true);
        if (!SiblingRemoval.clean(target)) {
            getLog().warn("  -DdeleteSibling: the sibling still holds "
                    + "something the landing did not carry — kept in place.");
            for (String[] row : SiblingRemoval.preflightRows(target)) {
                if ("✗".equals(row[0])) {
                    getLog().warn("    " + row[1] + ": " + row[2]);
                }
            }
            return new WorkspaceReportSpec(spec.goal(), spec.content()
                    + "\n## Sibling kept\n\nThe post-landing check still "
                    + "found work that would die with the tree (see the "
                    + "warnings above). Review with `"
                    + WsGoal.SIBLING_REMOVE_DRAFT.qualified()
                    + " -Dfeature=" + target.sibling().feature()
                    + "`; discard deliberately with `"
                    + WsGoal.SIBLING_REMOVE_PUBLISH.qualified()
                    + " -Dforce=true`.\n");
        }

        String removedNote = "\n## Sibling removed\n\n`"
                + root.getAbsolutePath() + "` was deleted after the landing "
                + "(-DdeleteSibling=true): every member was clean and "
                + "landed. The sync layer propagates the deletion; "
                + "staggered file versioning is the net. This receipt "
                + "lives in the parent because the sibling is gone.\n";
        WorkspaceReportSpec extended = new WorkspaceReportSpec(spec.goal(),
                spec.content() + removedNote);
        // The parent copy first: after the deletion there is nowhere else
        // for this record to live.
        WorkspaceReport.write(parent.get().toPath(),
                extended.goal().qualified(), extended.content(), getLog());
        Path gcRecord = SiblingRemoval.delete(target);
        getLog().info("  Sibling removed: " + root.getAbsolutePath());
        if (gcRecord != null) {
            getLog().info("  Lease record GC'd: " + gcRecord.getFileName());
        }
        return extended;
    }
}
