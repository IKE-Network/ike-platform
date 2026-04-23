package network.ike.plugin.ws;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure inference logic for {@link WsReleaseStatusMojo} — given a
 * snapshot of git observations for one subproject, classifies the
 * subproject's release state.
 *
 * <p>This class is git-only and side-effect free. The mojo is
 * responsible for collecting an {@link Observation} from the live
 * repository (via {@code git} subprocesses); this class encapsulates
 * the rules that turn that observation into a {@link Finding}.
 *
 * <p>The split exists so that the classification rules can be
 * exercised without building a real git repository on disk for every
 * scenario — see {@code WsReleaseStatusInspectorTest}. End-to-end
 * coverage that spans real {@code git tag}, {@code git branch}, and
 * remote interaction lives in {@code WsReleaseStatusIntegrationTest}.
 *
 * <p>See issue #187.
 */
public final class ReleaseStatusInspector {

    private ReleaseStatusInspector() {}

    /**
     * High-level summary of a subproject's release state, derived from
     * git artifacts alone. Cycle 2+ of #187 will refine these states
     * once a {@code .ike/release-state.json} file is also available.
     */
    public enum Status {
        /**
         * No in-flight release artifacts. The subproject is either
         * fully released or has never been released.
         */
        CLEAN("✓", "clean"),

        /**
         * The subproject has at least one of: a {@code release/*}
         * branch left behind by an interrupted release, a local
         * {@code v*} tag that was never pushed to {@code origin}.
         * A previous release attempt did not run to completion.
         */
        IN_FLIGHT("⚠", "in-flight"),

        /**
         * Contradictory signals: a {@code release/*} branch is still
         * present locally, AND the corresponding {@code v*} tag is
         * already on {@code origin}. The release likely completed on
         * another machine (or via a manual recovery), and the local
         * branch is stale debris.
         */
        DIVERGED("✗", "diverged"),

        /**
         * The subproject directory is not present in the workspace
         * checkout. No inference possible.
         */
        ABSENT("─", "not checked out");

        private final String badge;
        private final String label;

        Status(String badge, String label) {
            this.badge = badge;
            this.label = label;
        }

        /** Single-character glyph suitable for status-line output. */
        public String badge() {
            return badge;
        }

        /** Lowercase human label. */
        public String label() {
            return label;
        }
    }

    /**
     * Raw git observations for a single subproject. Built by the mojo
     * from real {@code git} subprocesses (or by a test fixture).
     *
     * @param subprojectName        the subproject name from {@code workspace.yaml}
     * @param checkedOut            whether the subproject directory exists
     *                              and contains a git repository
     * @param currentVersion        the {@code <version>} value read from
     *                              the subproject's root POM, or
     *                              {@code "unknown"} if it cannot be read
     * @param currentBranch         the current branch name, or
     *                              {@code "unknown"}
     * @param releaseBranches       local branch names matching
     *                              {@code release/*} (typically empty
     *                              when no release is in flight)
     * @param localTags             local tags matching {@code v*}
     * @param remoteTags            tags present on {@code origin}
     *                              matching {@code v*} (best-effort —
     *                              empty when {@code origin} cannot be
     *                              reached)
     * @param remoteReachable       whether the {@code origin} remote
     *                              could be queried; {@code false}
     *                              suppresses the local-only-tag
     *                              warning to avoid false positives
     */
    public record Observation(
            String subprojectName,
            boolean checkedOut,
            String currentVersion,
            String currentBranch,
            List<String> releaseBranches,
            Set<String> localTags,
            Set<String> remoteTags,
            boolean remoteReachable) {}

    /**
     * Classification result for a single subproject. The
     * {@link #details()} list captures the raw signals that led to
     * the chosen {@link #status()}, suitable for direct rendering as
     * indented bullet lines in the goal output.
     *
     * @param subprojectName            the subproject name
     * @param status                    classification verdict
     * @param currentVersion            the {@code <version>} from POM
     * @param currentBranch             the checked-out branch
     * @param inFlightReleaseBranches   {@code release/*} branches still
     *                                  present locally
     * @param localOnlyTags             local {@code v*} tags not yet on
     *                                  {@code origin}
     * @param details                   ordered diagnostic lines
     *                                  describing the inputs that led
     *                                  to {@code status}
     */
    public record Finding(
            String subprojectName,
            Status status,
            String currentVersion,
            String currentBranch,
            List<String> inFlightReleaseBranches,
            List<String> localOnlyTags,
            List<String> details) {}

    /**
     * Apply the classification rules to a single observation.
     *
     * <p>Rules, in order of precedence:
     * <ol>
     *   <li>If the subproject is not checked out, return
     *       {@link Status#ABSENT}.</li>
     *   <li>If a {@code release/*} branch is present locally <em>and</em>
     *       the corresponding {@code v<version>} tag is already on
     *       {@code origin}, return {@link Status#DIVERGED} — the
     *       release happened elsewhere; the local branch is debris.</li>
     *   <li>If any {@code release/*} branch exists locally, or any
     *       local {@code v*} tag is missing from the remote (and the
     *       remote was reachable), return {@link Status#IN_FLIGHT}.</li>
     *   <li>Otherwise return {@link Status#CLEAN}.</li>
     * </ol>
     *
     * @param obs the git observation snapshot for one subproject
     * @return the classification result
     */
    public static Finding classify(Observation obs) {
        if (!obs.checkedOut()) {
            return new Finding(
                    obs.subprojectName(),
                    Status.ABSENT,
                    obs.currentVersion(),
                    obs.currentBranch(),
                    List.of(),
                    List.of(),
                    List.of("Subproject directory not present."));
        }

        List<String> details = new ArrayList<>();

        // Local-only tags = local tags missing on origin (only meaningful
        // when origin was reachable; otherwise the absence is noise).
        List<String> localOnlyTags;
        if (obs.remoteReachable()) {
            localOnlyTags = new ArrayList<>();
            for (String t : obs.localTags()) {
                if (!obs.remoteTags().contains(t)) {
                    localOnlyTags.add(t);
                }
            }
        } else {
            localOnlyTags = List.of();
            details.add("origin unreachable — local-only tag check skipped.");
        }

        List<String> releaseBranches = new ArrayList<>(obs.releaseBranches());

        // DIVERGED: release branch present AND its tag is on origin.
        // The release was carried to completion elsewhere and the
        // branch debris should be cleaned up locally.
        Set<String> remoteVersions = new LinkedHashSet<>();
        for (String t : obs.remoteTags()) {
            if (t.startsWith("v")) {
                remoteVersions.add(t.substring(1));
            }
        }
        boolean diverged = false;
        for (String branch : releaseBranches) {
            // branch shape is "release/<version>"
            if (branch.startsWith("release/")) {
                String version = branch.substring("release/".length());
                if (remoteVersions.contains(version)) {
                    diverged = true;
                    details.add("Release branch '" + branch
                            + "' is local-only, but origin already has tag v"
                            + version + ".");
                }
            }
        }
        if (diverged) {
            return new Finding(
                    obs.subprojectName(),
                    Status.DIVERGED,
                    obs.currentVersion(),
                    obs.currentBranch(),
                    releaseBranches,
                    localOnlyTags,
                    details);
        }

        if (!releaseBranches.isEmpty()) {
            details.add("Release branch(es) still present locally: "
                    + String.join(", ", releaseBranches));
        }
        if (!localOnlyTags.isEmpty()) {
            details.add("Local tag(s) not on origin: "
                    + String.join(", ", localOnlyTags));
        }

        if (!releaseBranches.isEmpty() || !localOnlyTags.isEmpty()) {
            return new Finding(
                    obs.subprojectName(),
                    Status.IN_FLIGHT,
                    obs.currentVersion(),
                    obs.currentBranch(),
                    releaseBranches,
                    localOnlyTags,
                    details);
        }

        details.add("No in-flight release artifacts.");
        return new Finding(
                obs.subprojectName(),
                Status.CLEAN,
                obs.currentVersion(),
                obs.currentBranch(),
                List.of(),
                List.of(),
                details);
    }
}
