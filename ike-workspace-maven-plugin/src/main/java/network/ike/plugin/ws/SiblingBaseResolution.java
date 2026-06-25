package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;

/**
 * Shared base-branch resolution and guard for the sibling feature-start
 * goals ({@link FeatureStartSiblingPublishMojo} and
 * {@link FeatureStartSiblingDraftMojo}), reshaped in
 * IKE-Network/ike-issues#770.
 *
 * <p>A sibling clone is cut from a <em>base branch</em>. By default that is
 * the primary's current branch, but cutting a feature off whatever branch
 * the primary happens to sit on is a footgun: the intent is almost always
 * "branch from the manifest base" ({@code defaults.branch}, else
 * {@code main}). So when {@code -Dfrom} is not given and the primary is not
 * on the manifest base, the goal refuses with a copy-pasteable remediation
 * rather than silently basing the feature on the wrong branch. Passing
 * {@code -Dfrom=<branch>} is the deliberate opt-out that bases the sibling on
 * exactly that branch, guard skipped.
 */
final class SiblingBaseResolution {

    private SiblingBaseResolution() {}

    /**
     * Resolve the base branch a sibling should be cut from, guarding against
     * an unexpected primary branch.
     *
     * <ul>
     *   <li>If {@code from} is non-blank, it is returned verbatim — the
     *       guard is skipped (the caller deliberately chose the base).</li>
     *   <li>Otherwise the {@code currentBranch} of the primary is the base.
     *       If that differs from {@code manifestBase}, a {@link MojoException}
     *       is thrown with remediation; if it matches, it is returned.</li>
     * </ul>
     *
     * @param from          the explicit {@code -Dfrom} base branch, or
     *                      {@code null}/blank when unset
     * @param currentBranch the primary workspace root's (or single repo's)
     *                      current git branch
     * @param manifestBase  the manifest's base branch
     *                      ({@code defaults.branch}, else {@code main})
     * @return the resolved base branch to clone from
     * @throws MojoException if {@code from} is unset and {@code currentBranch}
     *                       is not {@code manifestBase}
     */
    static String resolveAndGuard(String from, String currentBranch,
                                  String manifestBase) {
        if (from != null && !from.isBlank()) {
            return from.trim();
        }
        String base = currentBranch;
        if (!base.equals(manifestBase)) {
            throw new MojoException(
                    "feature-start-sibling: the primary is on '" + base
                    + "', not the base branch '" + manifestBase + "'. Cut a"
                    + " sibling from '" + manifestBase + "' (switch the primary"
                    + " to it / run ws:refresh-main), or pass -Dfrom=" + base
                    + " to deliberately base the sibling on '" + base + "'.");
        }
        return base;
    }
}
