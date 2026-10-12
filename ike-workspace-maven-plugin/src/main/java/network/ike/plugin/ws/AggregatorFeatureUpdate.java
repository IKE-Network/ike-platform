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
import java.util.Optional;

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
 * <p>This helper resolves that one conflict by construction. The feature
 * side's {@code branch:} lines and qualified {@code version:} lines are set
 * back to the merge base's, which removes the adjacency, and the manifest is
 * then merged three ways as text: every other edit either side made — a
 * {@code maven-version}, a {@code repo:}, prose — is kept, where taking the
 * target's file whole dropped the feature's (IKE-Network/ike-issues#1159).
 * The feature's branch fields and qualifiers are then re-applied onto the
 * result. If the text merge still conflicts, both sides changed the same
 * field and the root is reported for a human, like any other conflict: the
 * merge is aborted, because a manifest left with conflict markers would
 * break every later goal.
 *
 * <p>See IKE-Network/ike-issues#1099 and #1159.
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
                if (!resolveManifest(root, manifestPath, featureSide, featureBranch, log)) {
                    // Both sides changed the same manifest field: a real
                    // conflict, reported like any other (#1159).
                    VcsOperations.mergeAbortQuiet(root, log);
                    return new Conflicting(assessment.behind(), assessment.ahead(),
                            List.of(manifestName));
                }
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
     * Resolve the manifest conflict by construction (IKE-Network/ike-issues#1099,
     * #1159).
     *
     * <p>The merge's own three versions of the manifest are read from the
     * index: base, feature (ours) and target (theirs). On the feature side,
     * each subproject's {@code branch:} line that names the feature branch,
     * and each {@code version:} line carrying the feature qualifier, is set
     * back to the base's line, so the feature-owned edits no longer sit next
     * to the target's {@code sha:} pins. The three are then merged as text
     * with {@code git merge-file}, which keeps every other edit from either
     * side. Onto the result, the feature's {@code branch:} fields are
     * re-applied for every subproject the feature had on the feature branch,
     * and the feature qualifier onto the merged version for every subproject
     * whose feature-side version carried it. A subproject the target no
     * longer declares contributes nothing; one the target added keeps the
     * target's fields.
     *
     * @return {@code true} when resolved; {@code false} when the text merge
     *         still conflicts, meaning both sides changed the same field
     */
    private static boolean resolveManifest(File root, Path manifestPath, Manifest featureSide,
                                           String featureBranch, Log log)
            throws IOException, MojoException {
        String manifestName = manifestPath.getFileName().toString();
        String base = indexStage(root, 1, manifestName);
        String ours = indexStage(root, 2, manifestName);
        String theirs = indexStage(root, 3, manifestName);
        String neutral = withBaseFeatureFields(ours, base, featureSide, featureBranch);
        Optional<String> merged = mergeText(root, neutral, base, theirs);
        if (merged.isEmpty()) {
            log.info("    " + manifestName + " — both sides changed the same field;"
                    + " left for a human");
            return false;
        }
        Files.writeString(manifestPath, merged.get(), StandardCharsets.UTF_8);
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
        log.info("    " + manifestName + " — resolved by construction: three-way merge, "
                + branches.size() + " branch field" + (branches.size() == 1 ? "" : "s")
                + " kept on " + featureBranch
                + (versions.isEmpty() ? "" : ", " + versions.size()
                        + " version qualifier" + (versions.size() == 1 ? "" : "s")
                        + " re-applied"));
        return true;
    }

    /**
     * The feature side of the manifest with its feature-owned lines set back
     * to the base's: for each subproject, the {@code branch:} line when the
     * feature side names {@code featureBranch}, and the {@code version:} line
     * when the feature side's version carries the feature qualifier. Lines
     * are copied from the base verbatim, so they match it byte for byte and
     * a text merge sees no feature-side change there. A field the base does
     * not have is left as it is.
     *
     * @param ours          the feature side's manifest text
     * @param base          the merge base's manifest text
     * @param featureSide   the feature side, parsed
     * @param featureBranch the feature branch, such as {@code feature/x}
     * @return the feature side with those lines set to the base's
     */
    static String withBaseFeatureFields(String ours, String base, Manifest featureSide,
                                        String featureBranch) {
        Map<String, String> baseLines = subprojectFieldLines(base);
        String[] lines = ours.split("\n", -1);
        String subproject = null;
        boolean inSubprojects = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (!line.startsWith(" ")) {
                inSubprojects = line.startsWith("subprojects:");
                subproject = null;
                continue;
            }
            if (!inSubprojects) {
                continue;
            }
            java.util.regex.Matcher key = SUBPROJECT_KEY.matcher(line);
            if (key.matches()) {
                subproject = key.group(1);
                continue;
            }
            java.util.regex.Matcher field = SUBPROJECT_FIELD.matcher(line);
            if (subproject == null || !field.matches()) {
                continue;
            }
            Subproject feature = featureSide.subprojects().get(subproject);
            if (feature == null) {
                continue;
            }
            boolean featureOwned = switch (field.group(1)) {
                case "branch" -> featureBranch.equals(feature.branch());
                case "version" -> feature.version() != null
                        && VersionSupport.isBranchQualified(feature.version());
                default -> false;
            };
            String baseLine = baseLines.get(subproject + "\u0000" + field.group(1));
            if (featureOwned && baseLine != null) {
                lines[i] = baseLine;
            }
        }
        return String.join("\n", lines);
    }

    /** A subproject key under {@code subprojects:}: two spaces, the name, a colon. */
    private static final java.util.regex.Pattern SUBPROJECT_KEY =
            java.util.regex.Pattern.compile("^  ([A-Za-z0-9._-]+):\\s*$");

    /** A subproject's own field: four spaces, the field name, a colon. */
    private static final java.util.regex.Pattern SUBPROJECT_FIELD =
            java.util.regex.Pattern.compile("^    ([A-Za-z][A-Za-z0-9_-]*):.*$");

    /**
     * Each subproject field line in a manifest, keyed by subproject name and
     * field name joined with a NUL.
     */
    private static Map<String, String> subprojectFieldLines(String yaml) {
        Map<String, String> fields = new LinkedHashMap<>();
        String subproject = null;
        boolean inSubprojects = false;
        for (String line : yaml.split("\n", -1)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (!line.startsWith(" ")) {
                inSubprojects = line.startsWith("subprojects:");
                subproject = null;
                continue;
            }
            if (!inSubprojects) {
                continue;
            }
            java.util.regex.Matcher key = SUBPROJECT_KEY.matcher(line);
            if (key.matches()) {
                subproject = key.group(1);
                continue;
            }
            java.util.regex.Matcher field = SUBPROJECT_FIELD.matcher(line);
            if (subproject != null && field.matches()) {
                fields.putIfAbsent(subproject + "\u0000" + field.group(1), line);
            }
        }
        return fields;
    }

    /**
     * One version of a conflicted file from the index: stage 1 is the merge
     * base, 2 the current branch (ours), 3 the branch being merged (theirs).
     */
    private static String indexStage(File root, int stage, String file)
            throws IOException, MojoException {
        GitOutput shown = git(root, "show", ":" + stage + ":" + file);
        if (shown.exit() != 0) {
            throw new MojoException("No stage " + stage + " of " + file + " in the index");
        }
        return shown.stdout();
    }

    /**
     * A three-way text merge with {@code git merge-file}.
     *
     * @return the merged text, or empty when it conflicts
     */
    private static Optional<String> mergeText(File root, String ours, String base,
                                              String theirs) throws IOException {
        Path dir = Files.createTempDirectory("ws-manifest-merge");
        try {
            Path oursFile = Files.writeString(dir.resolve("ours"), ours, StandardCharsets.UTF_8);
            Path baseFile = Files.writeString(dir.resolve("base"), base, StandardCharsets.UTF_8);
            Path theirsFile = Files.writeString(dir.resolve("theirs"), theirs,
                    StandardCharsets.UTF_8);
            GitOutput merged = git(root, "merge-file", "-p",
                    oursFile.toString(), baseFile.toString(), theirsFile.toString());
            return merged.exit() == 0 ? Optional.of(merged.stdout()) : Optional.empty();
        } finally {
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    /** A git command's exit code and its standard output, byte-exact. */
    private record GitOutput(int exit, String stdout) {}

    private static GitOutput git(File root, String... args) throws IOException {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(root)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String stdout = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        try {
            return new GitOutput(process.waitFor(), stdout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted running git " + String.join(" ", args), e);
        }
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
