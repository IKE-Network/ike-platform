package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.FeatureName;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Discovery and state assessment for a primary's sibling working sets —
 * the shared substrate of {@code ws:sibling-list} (IKE-Network/ike-issues#599)
 * and {@code ws:sibling-remove} (IKE-Network/ike-issues#600).
 *
 * <p>A sibling is named {@code <primary>꞉<feature>} and lives beside its
 * primary (IKE-Network/ike-issues#992). Discovery scans the primary's
 * parent directory for that pattern and classifies each candidate:
 * <em>conformant</em> when {@link SiblingFinish#localParent} confirms the
 * chain back to this primary (recorded parent, or derivation confirmed
 * against a local origin), <em>legacy</em> otherwise — the shape a sibling
 * created before the local-origin model still has until repaired
 * (IKE-Network/ike-issues#1057).
 *
 * <p>Assessment reports, per member repository, exactly the things that
 * die with a deleted tree: uncommitted changes, <b>stashes</b> (the one
 * thing no upstream comparison ever reveals), and unlanded branches. A
 * branch counts as landed when its commits are contained in origin's refs
 * <em>or its tree equals an origin tip's tree</em> — the squash finish
 * never preserves SHAs, and the tree is the medium of exchange.
 */
final class SiblingInventory {

    /** Directories never descended into while discovering member repos. */
    private static final List<String> SKIPPED_DIRECTORIES =
            List.of(".git", ".ike", ".idea", ".stversions", "target",
                    "checkpoints");

    /** How deep member discovery looks below the sibling root. */
    private static final int MEMBER_DISCOVERY_DEPTH = 3;

    private SiblingInventory() {}

    /**
     * One discovered sibling.
     *
     * @param name       the sibling directory name ({@code <primary>꞉<feature>})
     * @param root       the sibling's directory
     * @param feature    the feature half of the name
     * @param conformant {@code true} when the local-origin chain back to
     *                   the primary is confirmed
     */
    record Sibling(String name, File root, String feature,
                   boolean conformant) {}

    /**
     * One member repository's removable-state assessment.
     *
     * @param path        the member path relative to the sibling root, or
     *                    {@code "(root)"} for the sibling root itself
     * @param bare        tree present but no git state — unverifiable
     * @param branch      the current branch, or {@code "—"} when bare
     * @param uncommitted count of uncommitted paths ({@code status --porcelain})
     * @param stashes     count of stash entries
     * @param unlanded    names of local branches whose work is neither
     *                    contained in origin's refs nor tree-equal to an
     *                    origin tip; {@code null} when it could not be
     *                    determined (no origin, or the fetch failed)
     * @param originLocal {@code true} when origin is a local path — the
     *                    #992 invariant; {@code false} for a legacy remote
     *                    remote or a missing origin
     */
    record MemberState(String path, boolean bare, String branch,
                       int uncommitted, int stashes, List<String> unlanded,
                       boolean originLocal) {

        /** @return {@code true} when nothing in this member blocks removal */
        boolean removable() {
            return !bare && uncommitted == 0 && stashes == 0
                    && unlanded != null && unlanded.isEmpty();
        }
    }

    /**
     * Discovers the primary's siblings: entries beside the primary named
     * {@code <primary>꞉*}, classified by local-origin conformance.
     *
     * @param primaryRoot the primary working set's root directory
     * @param baseName    the primary's directory name
     * @return the siblings, name-ordered; empty when the primary has none
     */
    static List<Sibling> discover(File primaryRoot, String baseName) {
        File parent = primaryRoot.getParentFile();
        if (parent == null) {
            return List.of();
        }
        String prefix = baseName + FeatureName.SIBLING_SEPARATOR;
        File[] entries = parent.listFiles(File::isDirectory);
        if (entries == null) {
            return List.of();
        }
        List<Sibling> siblings = new ArrayList<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (!name.startsWith(prefix)
                    || name.length() == prefix.length()) {
                continue;
            }
            boolean conformant = SiblingFinish.localParent(entry)
                    .map(found -> sameDirectory(found, primaryRoot))
                    .orElse(false);
            siblings.add(new Sibling(name, entry,
                    name.substring(prefix.length()), conformant));
        }
        siblings.sort(Comparator.comparing(Sibling::name));
        return siblings;
    }

    /**
     * Assesses every member repository of a sibling.
     *
     * @param siblingRoot    the sibling's directory
     * @param fetchLocalOnly when {@code true}, origins that are remote URLs
     *                       are not fetched (a listing must not touch the
     *                       network); when {@code false} every origin is
     *                       fetched first, so the landed test is current
     * @return one state per member, the root first
     */
    static List<MemberState> assess(File siblingRoot, boolean fetchLocalOnly) {
        List<MemberState> states = new ArrayList<>();
        for (File member : memberDirectories(siblingRoot)) {
            String label = member.equals(siblingRoot) ? "(root)"
                    : siblingRoot.toPath().relativize(member.toPath())
                            .toString();
            if (!new File(member, ".git").exists()) {
                states.add(new MemberState(label, true, "—", 0, 0, null,
                        false));
                continue;
            }
            states.add(assessRepo(label, member, fetchLocalOnly));
        }
        return states;
    }

    private static MemberState assessRepo(String label, File repo,
                                          boolean fetchLocalOnly) {
        String branch = capture(repo, "rev-parse", "--abbrev-ref", "HEAD")
                .orElse("—");
        int uncommitted = lineCount(capture(repo, "status", "--porcelain"));
        int stashes = lineCount(capture(repo, "stash", "list"));
        Optional<String> origin = capture(repo, "remote", "get-url", "origin");
        boolean originLocal = origin.map(SiblingInventory::isLocalPath)
                .orElse(false);
        List<String> unlanded = origin.isEmpty() ? null
                : unlandedBranches(repo, originLocal || !fetchLocalOnly);
        return new MemberState(label, false, branch, uncommitted, stashes,
                unlanded, originLocal);
    }

    /**
     * Counts local branches whose work has not landed at origin. Fetches
     * first when allowed, so the answer is current; a branch is landed
     * when its commits are contained in origin's refs, or when its tree
     * equals some origin tip's tree — the shape a squash finish leaves.
     */
    private static List<String> unlandedBranches(File repo, boolean fetch) {
        if (fetch && !run(repo, "fetch", "--quiet", "origin")) {
            return null;
        }
        Optional<String> branches = capture(repo, "for-each-ref",
                "refs/heads", "--format=%(refname:short)");
        if (branches.isEmpty()) {
            return List.of();       // no local branches: nothing to lose
        }
        List<String> originTrees = new ArrayList<>();
        capture(repo, "for-each-ref", "refs/remotes/origin",
                "--format=%(refname)").orElse("").lines()
                .filter(ref -> !ref.isBlank())
                .forEach(ref -> capture(repo, "rev-parse", ref + "^{tree}")
                        .ifPresent(originTrees::add));
        List<String> unlanded = new ArrayList<>();
        for (String branch : branches.get().lines()
                .filter(line -> !line.isBlank()).toList()) {
            String contained = capture(repo, "rev-list", "--count", branch,
                    "--not", "--remotes=origin").orElse("?");
            if ("0".equals(contained)) {
                continue;
            }
            String tree = capture(repo, "rev-parse", branch + "^{tree}")
                    .orElse("?");
            if (originTrees.contains(tree)) {
                continue;
            }
            if (onlyGoalAuthoredCommits(repo, branch)) {
                continue;
            }
            unlanded.add(branch);
        }
        return unlanded;
    }

    /**
     * Reports whether every commit the branch holds beyond origin is
     * goal-authored bookkeeping. The {@code ws:} machinery commits its own
     * manifest and version bookkeeping under the {@code "ws: "} subject
     * namespace ({@code GoalAuthoredChanges.commitAuthored}, #780) — the
     * finish deliberately leaves such commits sibling-local (a sibling's
     * derived depends-on edges are its own), and they must not force a
     * {@code -Dforce} on every finished workspace sibling. A hand-written
     * commit inside that namespace would ride along; the namespace is the
     * machinery's, and the draft report names the branch either way.
     */
    private static boolean onlyGoalAuthoredCommits(File repo, String branch) {
        Optional<String> subjects = capture(repo, "log", "--format=%s",
                branch, "--not", "--remotes=origin");
        return subjects.isPresent()
                && subjects.get().lines()
                        .filter(line -> !line.isBlank())
                        .allMatch(subject -> subject.startsWith("ws: "));
    }

    /**
     * Classifies a git origin as a local filesystem path or a remote URL —
     * the same predicate the materializer core applies
     * (IKE-Network/ike-issues#1057).
     *
     * @param origin the configured origin
     * @return {@code true} for filesystem paths ({@code file://} included)
     */
    static boolean isLocalPath(String origin) {
        if (origin.startsWith("file://")) {
            return true;
        }
        if (origin.contains("://")) {
            return false;
        }
        return !origin.matches("^[^/@]+@[^/:]+:.*");
    }

    /**
     * The sibling root plus every git repository below it, without
     * descending into repositories, hidden directories, or build output.
     */
    private static List<File> memberDirectories(File siblingRoot) {
        List<File> members = new ArrayList<>();
        members.add(siblingRoot);
        collectMembers(siblingRoot, 0, members);
        return members;
    }

    private static void collectMembers(File directory, int depth,
                                       List<File> members) {
        if (depth >= MEMBER_DISCOVERY_DEPTH) {
            return;
        }
        File[] children = directory.listFiles(File::isDirectory);
        if (children == null) {
            return;
        }
        List<File> sorted = new ArrayList<>(List.of(children));
        sorted.sort(Comparator.comparing(File::getName));
        for (File child : sorted) {
            String name = child.getName();
            if (name.startsWith(".") || SKIPPED_DIRECTORIES.contains(name)) {
                continue;
            }
            if (new File(child, ".git").exists()) {
                members.add(child);
            } else {
                collectMembers(child, depth + 1, members);
            }
        }
    }

    private static boolean sameDirectory(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (java.io.IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    private static Optional<String> capture(File repo, String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            String out = ReleaseSupport.execCapture(repo, command);
            return Optional.ofNullable(out).map(String::trim);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean run(File repo, String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            ReleaseSupport.execCapture(repo, command);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int lineCount(Optional<String> output) {
        return (int) output.stream()
                .flatMap(String::lines)
                .filter(line -> !line.isBlank())
                .count();
    }
}
