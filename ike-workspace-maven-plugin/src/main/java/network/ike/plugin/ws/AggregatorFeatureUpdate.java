package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.Subproject;
import network.ike.workspace.VersionSupport;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings the working-set root (the aggregator) up to date with the target
 * branch during {@code ws:update-feature}, the way the goal does for every
 * subproject — with the one structural difference the manifest forces.
 *
 * <p>The root's feature branch carries the manifest with the subproject
 * {@code branch:} fields pointing at the feature and, when feature-start
 * qualified them, {@code version:} fields carrying the feature qualifier.
 * The target branch rewrites the {@code sha:} pins on the adjacent lines at
 * every checkpoint. Git sees two sides editing adjacent lines and reports a
 * conflict, although the sides own disjoint fields: the feature owns the
 * branch fields and the version qualifiers, the target owns everything else
 * — the pins, the versions' numeric base, the derived edges, the prose.
 *
 * <p>This helper resolves that one conflict by construction: the target's
 * manifest, with the feature's branch fields re-applied and the feature
 * qualifier re-applied onto the target's versions. Any other conflict in
 * the root is a real one — the merge is aborted, because a manifest left
 * with conflict markers would break every later goal, and the files are
 * reported for a human.
 *
 * <p>See IKE-Network/ike-issues#1099.
 */
final class AggregatorFeatureUpdate {

    private AggregatorFeatureUpdate() {}

    /** What updating the root's feature branch from the target involves. */
    sealed interface Assessment
            permits UpToDate, ConflictFreeMerge, ManifestResolvable, Conflicting {

        /** Commits on the target that the feature branch lacks. */
        int behind();

        /** Commits on the feature branch that the target lacks. */
        int ahead();
    }

    /** Nothing to merge: every target commit is already on the feature branch. */
    record UpToDate(int behind, int ahead) implements Assessment {}

    /** The merge applies without a conflict. */
    record ConflictFreeMerge(int behind, int ahead) implements Assessment {}

    /**
     * The manifest is the only conflicting file; its conflict is the
     * adjacency of feature-owned and target-owned fields and is resolved by
     * construction.
     */
    record ManifestResolvable(int behind, int ahead, String manifestName)
            implements Assessment {}

    /** Files beyond the manifest conflict; the update needs a human. */
    record Conflicting(int behind, int ahead, List<String> files)
            implements Assessment {}

    /**
     * Assess the root without touching it: how far its feature branch is
     * behind and ahead of the target, and whether merging would conflict —
     * in the manifest alone (resolvable by construction) or elsewhere.
     *
     * @param root          the working-set root, its own git repository
     * @param manifestName  the manifest's file name at the root
     * @param featureBranch the feature branch the root is on
     * @param targetRef     the ref to merge in — the local target branch, or
     *                      its remote-tracking ref for a read-only preview
     * @return the assessment
     * @throws MojoException if git cannot answer
     */
    static Assessment assess(File root, String manifestName, String featureBranch,
                             String targetRef) throws MojoException {
        int behind = VcsOperations.commitLog(root, featureBranch, targetRef).size();
        int ahead = VcsOperations.commitLog(root, targetRef, featureBranch).size();
        if (behind == 0) {
            return new UpToDate(0, ahead);
        }
        List<String> predicted = VcsOperations.predictConflicts(root, featureBranch, targetRef);
        if (predicted.isEmpty()) {
            return new ConflictFreeMerge(behind, ahead);
        }
        if (predicted.size() == 1 && predicted.get(0).equals(manifestName)) {
            return new ManifestResolvable(behind, ahead, manifestName);
        }
        return new Conflicting(behind, ahead, predicted);
    }

    /**
     * Merge {@code targetBranch} into the root's checked-out feature branch,
     * resolving a manifest-only conflict by construction. A conflict beyond
     * the manifest is never started, or is aborted if the prediction missed
     * it, so the root is left exactly as found.
     *
     * @param root          the working-set root, its own git repository, on
     *                      {@code featureBranch} with no uncommitted changes
     * @param manifestPath  the manifest inside {@code root}
     * @param featureSide   the manifest as the feature branch has it, read
     *                      before the merge
     * @param featureBranch the feature branch the root is on
     * @param targetBranch  the branch merged in, already refreshed
     * @param log           Maven logger
     * @return what was done
     * @throws MojoException on a git or manifest failure other than the
     *                       merge conflict itself
     */
    static Assessment apply(File root, Path manifestPath, Manifest featureSide,
                            String featureBranch, String targetBranch, Log log)
            throws MojoException {
        String manifestName = manifestPath.getFileName().toString();
        Assessment assessment = assess(root, manifestName, featureBranch, targetBranch);
        if (assessment instanceof UpToDate || assessment instanceof Conflicting) {
            return assessment;
        }

        String message = "update: merge " + targetBranch + " into " + featureBranch;
        try {
            VcsOperations.mergeNoFf(root, log, targetBranch, message);
            return new ConflictFreeMerge(assessment.behind(), assessment.ahead());
        } catch (MojoException mergeStopped) {
            List<String> conflicts = VcsOperations.conflictingFiles(root);
            if (conflicts.size() != 1 || !conflicts.get(0).equals(manifestName)) {
                // The prediction missed something, or git failed outright.
                // Leave the root as found: a half-merged root — its manifest
                // holding conflict markers — would break every later goal.
                VcsOperations.mergeAbortQuiet(root, log);
                if (conflicts.isEmpty()) {
                    throw mergeStopped;
                }
                return new Conflicting(assessment.behind(), assessment.ahead(), conflicts);
            }
            try {
                resolveManifest(root, manifestPath, featureSide, featureBranch, log);
                ReleaseSupport.exec(root, log, "git", "add", "--", manifestName);
                if (!VcsOperations.conflictingFiles(root).isEmpty()) {
                    throw new MojoException(manifestName
                            + " still reads as conflicted after the resolution");
                }
                VcsOperations.commit(root, log, message);
            } catch (IOException | ManifestException | MojoException e) {
                VcsOperations.mergeAbortQuiet(root, log);
                throw new MojoException("Could not resolve the " + manifestName
                        + " conflict in the workspace root: " + e.getMessage(), e);
            }
            return new ManifestResolvable(assessment.behind(), assessment.ahead(),
                    manifestName);
        }
    }

    /**
     * Resolve the manifest conflict by construction. The target's manifest
     * is taken whole — it owns the pins, the versions' numeric base, the
     * derived edges and the prose — then the feature's {@code branch:}
     * fields are re-applied for every subproject the feature had on the
     * feature branch, and the feature qualifier is re-applied onto the
     * target's version for every subproject whose feature-side version
     * carried it. A subproject the target no longer declares contributes
     * nothing; a subproject the target added keeps the target's fields.
     */
    private static void resolveManifest(File root, Path manifestPath, Manifest featureSide,
                                        String featureBranch, Log log)
            throws IOException, MojoException {
        String manifestName = manifestPath.getFileName().toString();
        ReleaseSupport.exec(root, log, "git", "checkout", "--theirs", "--", manifestName);
        Manifest targetSide = ManifestReader.read(manifestPath);

        Map<String, String> branches = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        for (Subproject feature : featureSide.subprojects().values()) {
            Subproject target = targetSide.subprojects().get(feature.name());
            if (target == null) {
                continue;
            }
            if (featureBranch.equals(feature.branch())) {
                branches.put(feature.name(), featureBranch);
            }
            if (feature.version() != null && target.version() != null
                    && VersionSupport.isBranchQualified(feature.version())) {
                String qualified = VersionSupport.branchQualifiedVersion(
                        target.version(), featureBranch);
                if (!qualified.equals(target.version())) {
                    versions.put(feature.name(), qualified);
                }
            }
        }

        ManifestWriter.updateBranches(manifestPath, branches);
        if (!versions.isEmpty()) {
            String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : versions.entrySet()) {
                content = ManifestWriter.updateSubprojectField(
                        content, entry.getKey(), "version", entry.getValue());
            }
            Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
        }
        log.info("    " + manifestName + " — resolved by construction: target's file, "
                + branches.size() + " branch field" + (branches.size() == 1 ? "" : "s")
                + " kept on " + featureBranch
                + (versions.isEmpty() ? "" : ", " + versions.size()
                        + " version qualifier" + (versions.size() == 1 ? "" : "s")
                        + " re-applied"));
    }

    /**
     * The one-line effect for the working-set report and the goal log.
     *
     * @param assessment   the assessment
     * @param preview      {@code true} for a draft, which phrases the effect
     *                     as planned rather than applied
     * @param targetBranch the target branch name, for the wording
     * @return the effect text
     */
    static String describe(Assessment assessment, boolean preview, String targetBranch) {
        return switch (assessment) {
            case UpToDate u -> "up to date with `" + targetBranch + "`";
            case ConflictFreeMerge c -> preview
                    ? "conflict-free update expected (" + c.behind() + " behind, " + c.ahead() + " ahead)"
                    : "merged `" + targetBranch + "` (" + c.behind() + " behind, "
                            + c.ahead() + " ahead)";
            case ManifestResolvable m -> preview
                    ? "update expected; `" + m.manifestName() + "` conflicts by adjacency"
                            + " and is resolved by construction, branch fields kept ("
                            + m.behind() + " behind, " + m.ahead() + " ahead)"
                    : "merged `" + targetBranch + "`; `" + m.manifestName()
                            + "` resolved by construction, branch fields kept ("
                            + m.behind() + " behind, " + m.ahead() + " ahead)";
            case Conflicting x -> (preview ? "" : "merge not applied — ")
                    + x.files().size() + " conflict" + (x.files().size() == 1 ? "" : "s")
                    + (preview ? " expected: " : ": ") + String.join(", ", x.files());
        };
    }
}
