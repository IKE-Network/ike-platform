package network.ike.plugin.ws;

import org.apache.maven.api.plugin.Log;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a finished feature <em>lands</em> once the working set has
 * squash- or merge-landed it on its local target branch — the one phase
 * that differs between a remote-origin working set and a local-origin
 * sibling (IKE-Network/ike-issues#992).
 *
 * <ul>
 *   <li><strong>Remote origin</strong> (a primary, or a sibling created
 *       before #992): verified pushes of the target branch to
 *       {@code origin}, the {@code #858} contract — no feature branch is
 *       deleted until every member's push is confirmed.</li>
 *   <li><strong>Local origin</strong> (a #992 sibling, whose origin is
 *       its parent's member path): the parent absorbs the landing by
 *       fast-forward, and nothing is pushed anywhere. Externalization is
 *       the parent's own explicit {@code ws:push}.</li>
 * </ul>
 *
 * <p>Either way the caller sees one uniform result: whether the landing
 * succeeded, and the message to fail with when it did not. Feature-branch
 * deletion gates on {@link Landing#failed()} in both models, so the
 * no-stranded-landing invariant holds with the parent playing the role
 * the remote plays for a primary.
 */
final class FinishLanding {

    private FinishLanding() {}

    /**
     * The outcome of the landing phase.
     *
     * @param pushed         per-member push results (remote-origin model;
     *                       empty under the local-origin model)
     * @param absorbed       per-member parent results (local-origin model;
     *                       empty under the remote-origin model)
     * @param localParent    the parent absorbed into, when this working
     *                       set is a local-origin sibling
     * @param failureMessage the message to fail the goal with, or
     *                       {@code null} when the landing succeeded
     */
    record Landing(List<FeatureFinishSupport.PushResult> pushed,
                   List<SiblingFinish.Absorbed> absorbed,
                   Optional<File> localParent,
                   String failureMessage) {

        /** Whether the landing left work stranded. */
        boolean failed() {
            return failureMessage != null;
        }

        /** Whether this working set landed into a local parent (#992). */
        boolean toLocalParent() {
            return localParent.isPresent();
        }

        /** A landing that did not run at all (draft, or nothing landed). */
        static Landing none(File root) {
            return new Landing(List.of(), List.of(),
                    SiblingFinish.localParent(root), null);
        }
    }

    /**
     * Run the landing phase for {@code members} plus the workspace root.
     *
     * @param root         the working set root the finish ran in
     * @param members      member names whose target branch must land
     * @param targetBranch the branch finished into (e.g. {@code main})
     * @param push         the {@code -Dpush} flag: {@code false} keeps the
     *                     landing local — no push, and no parent absorb —
     *                     which in both models also keeps every feature
     *                     branch
     * @param log          Maven logger
     * @return the uniform landing result
     */
    static Landing land(File root, List<String> members, String targetBranch,
                        boolean push, Log log) {
        Optional<File> localParent = SiblingFinish.localParent(root);
        if (!push) {
            return new Landing(List.of(), List.of(), localParent, null);
        }

        if (localParent.isPresent()) {
            File parent = localParent.get();
            List<SiblingFinish.Absorbed> absorbed =
                    SiblingFinish.absorbIntoParent(root, parent, members,
                            targetBranch, log);
            List<SiblingFinish.Absorbed> failures =
                    absorbed.stream().filter(a -> !a.ok()).toList();
            return new Landing(List.of(), absorbed, localParent,
                    failures.isEmpty() ? null
                            : SiblingFinish.absorbFailureMessage(
                                    failures, targetBranch, parent));
        }

        log.info("");
        log.info("  " + Ansi.cyan("→ ") + "Pushing " + targetBranch
                + " for every member (verified)...");
        Map<String, File> memberDirs = new LinkedHashMap<>();
        for (String name : members) {
            memberDirs.put(name, new File(root, name));
        }
        memberDirs.put(RefreshMainSupport.ROOT_LABEL, root);
        List<FeatureFinishSupport.PushResult> pushed =
                FeatureFinishSupport.pushTargetsVerified(
                        memberDirs, targetBranch, log);
        List<FeatureFinishSupport.PushResult> failures =
                pushed.stream().filter(r -> !r.pushed()).toList();
        return new Landing(pushed, List.of(), localParent,
                failures.isEmpty() ? null
                        : FeatureFinishSupport.pushPhaseFailureMessage(
                                failures, targetBranch));
    }

    /**
     * Preview line for the draft goals: what the landing phase would do.
     *
     * @param root         the working set root
     * @param targetBranch the branch that would be finished into
     * @param log          Maven logger
     */
    static void previewLanding(File root, String targetBranch, Log log) {
        Optional<File> localParent = SiblingFinish.localParent(root);
        if (localParent.isPresent()) {
            log.info("  Landing: " + targetBranch + " fast-forwards into the"
                    + " parent working set '" + localParent.get().getName()
                    + "' — nothing is pushed (ike-issues#992). Publish from"
                    + " the parent with " + WsGoal.PUSH.qualified() + ".");
            return;
        }
        FeatureFinishSupport.deriveParentWorkspace(root).ifPresent(
                parent -> log.info("  Parent workspace '" + parent.getName()
                        + "' will be fast-forwarded after pushes confirm"
                        + " (-DsyncParent=false to skip)."));
    }
}
