package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.ReleaseRecord;
import network.ike.workspace.ReleaseRecordFile;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes one checkpoint-shaped release mission of a working set — the
 * reactor-pass release model (IKE-Network/ike-issues#997, settled
 * 2026-08-11/12): one operation from the workspace root, with the
 * reactor as the coherence mechanism, replacing the per-member
 * delegation loop that imported standalone-repository release
 * assumptions into working sets.
 *
 * <p>The phases, in order:
 * <ol>
 *   <li><b>Version pass</b> — every releasing member's own
 *       {@code <version>} de-qualifies to its release value, and every
 *       plan-tracked reference site (version properties, parent blocks,
 *       literal dependency versions) moves to the referenced artifact's
 *       release value — all committed per repository as
 *       {@code release: set version to <V>} (a release-cadence subject,
 *       so retries never count these commits as source changes). The
 *       workspace root's release commit also carries the mission's
 *       {@code releases/release-<mission>.yaml} record, so the tagged
 *       root tree contains the mission record.</li>
 *   <li><b>Reactor verify</b> — one build of the whole working set at
 *       release versions ({@code clean install -P release}), replacing
 *       N per-member subprocess builds.</li>
 *   <li><b>Scoped deploy</b> — {@code deploy} limited to the releasing
 *       members plus the root ({@code -pl} selectors matching the
 *       version pass exactly; the verify pass installed everything, so
 *       no {@code -am} is needed and bystanders are never deployed).</li>
 *   <li><b>Tags</b> — an annotated {@code v<version>} tag per releasing
 *       repository and the root, at the release commits.</li>
 *   <li><b>Post-bump</b> — own versions move to the next development
 *       {@code -SNAPSHOT}s ({@code post-release: bump to <V>}); reference
 *       sites settle at the released values (the plan's post semantics —
 *       development builds resolve released upstreams from Nexus until
 *       alignment deliberately moves them forward).</li>
 *   <li><b>Push</b> — every touched repository's branch and tag.</li>
 * </ol>
 *
 * <p><b>First-mission-complete scope:</b> references from releasing
 * members to members OUTSIDE the release set are not rewritten by this
 * class; the release-set-aware SNAPSHOT preflight (ike-issues#981)
 * refuses such missions with remediation. The first mission — where every
 * member is release-pending — has no such references by construction;
 * bystander backward-pinning for later partial missions is the named
 * second increment on the design issue.
 *
 * <p>Subprocess Maven invocations go through the {@link Runner} seam so
 * tests can assert the exact command lines without executing builds
 * (the house pattern from the bare-mode release tests); git operations
 * run for real against the working set's repositories.
 */
final class WorkspaceReleaseMission {

    /**
     * One repository releasing in this mission.
     *
     * @param name           the working-set member name; the root uses
     *                       its artifactId
     * @param dir            the repository directory
     * @param artifactId     the Maven artifactId used for the
     *                       {@code -pl :artifactId} deploy selector
     * @param preVersion     the development version before the mission
     *                       (must end in {@code -SNAPSHOT})
     * @param releaseVersion the version this mission releases
     * @param postVersion    the next development version after the mission
     */
    record ReleasingRepo(String name, File dir, String artifactId,
                         String preVersion, String releaseVersion,
                         String postVersion) {}

    /**
     * Subprocess seam: runs one command in a directory, failing the
     * mission on a non-zero exit.
     */
    interface Runner {
        /**
         * Run the command, streaming output to the build log.
         *
         * @param dir     working directory
         * @param command the command and its arguments
         * @throws MojoException on non-zero exit
         */
        void run(File dir, String... command) throws MojoException;
    }

    private final File root;
    private final List<ReleasingRepo> members;
    private final ReleasingRepo rootRepo;
    private final ReleasePlan plan;
    private final String missionLabel;
    private final String recordDate;
    private final ReleaseTagStyle tagStyle;
    private final Log log;
    private final Runner runner;

    /**
     * Create a mission over the given releasing set.
     *
     * @param root       the workspace root directory
     * @param members    the releasing members, dependency order
     * @param rootRepo   the workspace root's own release identity
     * @param plan       the computed release plan (version truth for
     *                   every artifact and tracking property)
     * @param missionLabel the mission label naming the record file
     * @param recordDate the date recorded on the mission rows (caller
     *                   supplies; typically today's ISO date)
     * @param log        Maven logger
     * @param runner     subprocess seam for the verify/deploy builds
     */
    WorkspaceReleaseMission(File root, List<ReleasingRepo> members,
                          ReleasingRepo rootRepo, ReleasePlan plan,
                          String missionLabel, String recordDate,
                          ReleaseTagStyle tagStyle,
                          Log log, Runner runner) {
        this.root = root;
        this.tagStyle = tagStyle;
        this.members = List.copyOf(members);
        this.rootRepo = rootRepo;
        this.plan = plan;
        this.missionLabel = missionLabel;
        this.recordDate = recordDate;
        this.log = log;
        this.runner = runner;
    }

    /**
     * Execute the full mission.
     *
     * @param mvn       the Maven launcher for the reactor builds
     * @param skipTests whether the verify pass skips tests
     * @return released name → release version, in execution order,
     *         including the workspace root
     * @throws MojoException on any phase failure; earlier phases'
     *                       commits are release-cadence subjects, so a
     *                       re-run resumes rather than double-counting
     */
    Map<String, String> execute(String mvn, boolean skipTests)
            throws MojoException {
        guardNotInFlight();
        applyReleaseVersions();
        verifyReactor(mvn, skipTests);
        deployReleasingSet(mvn);
        tagReleases();
        postBump();
        pushAll();

        Map<String, String> released = new LinkedHashMap<>();
        for (ReleasingRepo m : members) {
            released.put(m.name(), m.releaseVersion());
        }
        released.put(rootRepo.name(), rootRepo.releaseVersion());
        return released;
    }

    /**
     * Describe the mission's phases for the draft report, without
     * mutating anything.
     *
     * @param mvn the Maven launcher the publish would use
     * @return human-readable phase lines
     */
    List<String> describe(String mvn) {
        List<String> lines = new ArrayList<>();
        lines.add("Reactor-pass mission `" + missionLabel + "` — one version"
                + " pass, one reactor verify, deploy scoped to the"
                + " releasing set:");
        for (ReleasingRepo m : allRepos()) {
            lines.add("  " + m.name() + ": " + m.preVersion() + " → "
                    + m.releaseVersion() + " (tag "
                    + tagStyle.tagFor(m.releaseVersion())
                    + "), then " + m.postVersion());
        }
        lines.add("  verify: " + String.join(" ", verifyCommand(mvn, false)));
        lines.add("  deploy: " + String.join(" ", deployCommand(mvn)));
        lines.add("  record: " + rootRelativeRecordPath());
        lines.add("  notes:  " + rootRelativeNotesPath());
        return lines;
    }

    // ── Phases ───────────────────────────────────────────────────────

    /**
     * Refuse to start when a prior mission is in flight: a releasing
     * repository whose POM already carries a non-SNAPSHOT version was
     * left mid-mission (crash between version pass and post-bump).
     */
    private void guardNotInFlight() {
        for (ReleasingRepo m : allRepos()) {
            if (!m.preVersion().endsWith("-SNAPSHOT")) {
                throw new MojoException("In-flight release mission detected: "
                        + m.name() + " is at " + m.preVersion()
                        + " (not a -SNAPSHOT). Recover the interrupted mission"
                        + " first — roll forward per IKE-RELEASE-RECOVERY.md"
                        + " — then re-run.");
            }
            if (tagExists(m.dir(), tagStyle.tagFor(m.releaseVersion()))) {
                throw new MojoException("Tag v" + m.releaseVersion()
                        + " already exists in " + m.name()
                        + " — its version was not bumped after the last"
                        + " release. Fix the version line, then re-run.");
            }
        }
    }

    /**
     * The version pass: self-versions to release values, plan-tracked
     * reference sites to the referenced artifacts' release values, the
     * mission record into the root — one release-cadence commit per
     * repository.
     */
    private void applyReleaseVersions() {
        log.info("");
        log.info("  Version pass — " + allRepos().size()
                + " repositories de-qualify together");

        // Self-versions — the repository root POM and every sub-module
        // beneath it that names the repository's own version.
        for (ReleasingRepo m : allRepos()) {
            File pom = new File(m.dir(), "pom.xml");
            try {
                ReleaseSupport.setPomVersion(pom, m.preVersion(),
                        m.releaseVersion());
            } catch (Exception e) {
                throw new MojoException("Version pass failed setting "
                        + m.name() + " to " + m.releaseVersion() + ": "
                        + e.getMessage(), e);
            }
            retargetOwnCoordinates(m, m.preVersion(), m.releaseVersion());
        }

        // Tracking properties settle at the referenced release values.
        for (ReleasePlan.PropertyReleasePlan p : plan.properties()) {
            rewrite(p.declaringPomPath(), content -> PomModel.updateProperty(
                    content, p.propertyName(), p.releaseValue()),
                    "property " + p.propertyName());
        }

        // Parent blocks and literal dependency sites (properties cover
        // the ${…}-routed sites).
        for (ReleasePlan.ArtifactReleasePlan a : plan.artifacts().values()) {
            for (ReleasePlan.ReferenceSite site : a.referenceSites()) {
                if (site.textAtSite() == null
                        || site.textAtSite().startsWith("${")) {
                    continue;
                }
                switch (site.kind()) {
                    case PARENT -> rewrite(site.pomPath(),
                            content -> PomModel.updateParentVersion(content,
                                    site.targetGa().groupId(),
                                    site.targetGa().artifactId(),
                                    a.releaseValue()),
                            "parent " + site.targetGa());
                    case DEPENDENCY -> rewrite(site.pomPath(),
                            content -> PomModel.updateDependencyVersion(content,
                                    site.targetGa().groupId(),
                                    site.targetGa().artifactId(),
                                    a.releaseValue()),
                            "dependency " + site.targetGa());
                    case PLUGIN -> rewrite(site.pomPath(),
                            content -> PomModel.updatePluginVersion(content,
                                    site.targetGa().groupId(),
                                    site.targetGa().artifactId(),
                                    a.releaseValue()),
                            "plugin " + site.targetGa());
                }
            }
        }

        // Members commit first, so their release commits exist to be
        // pinned and recorded; the root commits last, carrying both the
        // record and the pins into the tree its tag will name.
        for (ReleasingRepo m : members) {
            commitRepo(m.dir(), "release: set version to "
                    + m.releaseVersion());
            log.info(Ansi.green("  ✓ ") + m.name() + " → "
                    + m.releaseVersion());
        }

        pinWorkingSetState(workingSetDirs());
        writeMissionRecord();
        writeMissionNotes();

        commitRepo(rootRepo.dir(), "release: set version to "
                + rootRepo.releaseVersion());
        log.info(Ansi.green("  ✓ ") + rootRepo.name() + " → "
                + rootRepo.releaseVersion());
    }

    /**
     * Every member's directory by name — the whole working set, not
     * only what is releasing, since the manifest speaks for all of it.
     *
     * @return member name → directory, for each checked-out member
     */
    private Map<String, File> workingSetDirs() {
        Map<String, File> dirs = new LinkedHashMap<>();
        File[] entries = root.listFiles();
        if (entries == null) return dirs;
        for (File entry : entries) {
            if (entry.isDirectory() && new File(entry, ".git").exists()) {
                dirs.put(entry.getName(), entry);
            }
        }
        return dirs;
    }

    /** One reactor build of the whole working set at release versions. */
    private void verifyReactor(String mvn, boolean skipTests) {
        log.info("");
        log.info("  Reactor verify — one build, everything at release"
                + " versions");
        runner.run(root, verifyCommand(mvn, skipTests));
    }

    /** Deploy exactly the releasing set from the verified reactor. */
    private void deployReleasingSet(String mvn) {
        log.info("");
        log.info("  Deploy — scoped to the releasing set");
        runner.run(root, deployCommand(mvn));
    }

    /** Annotated v-tags at the release commits, one per repository. */
    private void tagReleases() {
        for (ReleasingRepo m : allRepos()) {
            String tag = tagStyle.tagFor(m.releaseVersion());
            ReleaseSupport.exec(m.dir(), log, "git", "tag", "-a", tag,
                    "-m", "release: " + m.name() + " " + m.releaseVersion()
                            + " (mission " + missionLabel + ")");
            log.info(Ansi.green("  ✓ ") + "tagged " + m.name() + " " + tag);
        }
    }

    /**
     * Own versions move to the next development SNAPSHOTs; reference
     * sites stay at the released values (the plan's settle semantics).
     */
    private void postBump() {
        for (ReleasingRepo m : allRepos()) {
            File pom = new File(m.dir(), "pom.xml");
            try {
                ReleaseSupport.setPomVersion(pom, m.releaseVersion(),
                        m.postVersion());
            } catch (Exception e) {
                throw new MojoException("Post-bump failed for " + m.name()
                        + ": " + e.getMessage(), e);
            }
            retargetOwnCoordinates(m, m.releaseVersion(), m.postVersion());
            commitRepo(m.dir(), "post-release: bump to " + m.postVersion());
        }
    }

    /** Push every repository's branch and release tag. */
    private void pushAll() {
        for (ReleasingRepo m : allRepos()) {
            String branch = ReleaseSupport.currentBranch(m.dir());
            ReleaseSupport.exec(m.dir(), log, "git", "push", "origin",
                    branch);
            ReleaseSupport.exec(m.dir(), log, "git", "push", "origin",
                    tagStyle.tagFor(m.releaseVersion()));
            log.info(Ansi.green("  ✓ ") + "pushed " + m.name());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private List<ReleasingRepo> allRepos() {
        List<ReleasingRepo> all = new ArrayList<>(members);
        all.add(rootRepo);
        return all;
    }

    /** Content-transform one POM file in place. */
    /**
     * Move a releasing repository's own version wherever its sub-modules
     * name it: a module declaring its own {@code <version>}, and a
     * module whose {@code <parent>} names an aggregator inside the same
     * repository. Maven carries the rest by inheritance, but a POM that
     * spells the version out is left behind by a root-only rewrite —
     * and one straggler at the old version breaks the whole reactor,
     * since its siblings' release-version dependencies on it resolve
     * nowhere (IKE-Network/ike-issues#1011).
     *
     * <p>Only the repository's own coordinates move. The match is the
     * version string itself: a {@code <parent>} or self {@code <version>}
     * equal to this repository's {@code from} value is by construction
     * this repository's own, since an external parent carries an
     * unrelated version. Dependency versions are never touched here —
     * cross-repository references belong to the release plan, which
     * knows the target's release value.
     *
     * @param repo the releasing repository whose modules to retarget
     * @param from the version to move away from
     * @param to   the version to move to
     */
    private void retargetOwnCoordinates(ReleasingRepo repo, String from,
                                        String to) {
        Path repoRoot = repo.dir().toPath();
        List<Path> modulePoms;
        try (java.util.stream.Stream<Path> tree = Files.walk(repoRoot)) {
            modulePoms = tree
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.equals(repoRoot.resolve("pom.xml")))
                    .filter(p -> !p.toString().contains(File.separator
                            + "target" + File.separator))
                    // Never cross into another repository. A working
                    // set's members live INSIDE the root's directory,
                    // so walking the root's tree reaches every member's
                    // POM — and a member whose version string happened
                    // to match the root's was rewritten as if it were
                    // one of the root's own modules. It is not: it is a
                    // separate repository with its own release line.
                    .filter(p -> !crossesRepositoryBoundary(repoRoot, p))
                    .toList();
        } catch (IOException e) {
            throw new MojoException("Version pass could not walk "
                    + repo.name() + ": " + e.getMessage(), e);
        }
        int retargeted = 0;
        for (Path pom : modulePoms) {
            boolean changed = false;

            // A <parent> naming an aggregator inside this repository.
            try {
                PomParentSupport.ParentInfo parent =
                        PomParentSupport.readParent(pom);
                if (parent != null && from.equals(parent.version())) {
                    rewrite(pom, content -> PomModel.updateParentVersion(
                            content, parent.groupId(), parent.artifactId(),
                            to), "parent " + parent.artifactId());
                    changed = true;
                }
            } catch (IOException e) {
                throw new MojoException("Version pass could not read the"
                        + " parent block of " + pom + ": " + e.getMessage(), e);
            }

            // A module spelling out its own version.
            if (declaresOwnVersion(pom, from)) {
                ReleaseSupport.setPomVersion(pom.toFile(), from, to);
                changed = true;
            }
            if (changed) retargeted++;
        }
        if (retargeted > 0) {
            log.info("    " + repo.name() + ": " + retargeted
                    + " sub-module POM(s) retargeted to " + to);
        }
    }

    /**
     * Whether this POM declares its own {@code <version>} at the given
     * value — the first {@code <version>} after any {@code <parent>}
     * block, which is the same element
     * {@link ReleaseSupport#setPomVersion} moves. Modules that inherit
     * their version declare none, and must be left alone.
     *
     * @param pom     the POM to inspect
     * @param version the version to look for
     * @return {@code true} when the POM spells out that version itself
     */
    /**
     * Whether a POM lies inside a repository nested under this one —
     * a working-set member, which owns its own versions and must not be
     * touched by another repository's version pass.
     *
     * @param repoRoot the repository whose tree is being walked
     * @param pom      a POM found beneath it
     * @return {@code true} when some directory between the two is
     *         itself a repository
     */
    private static boolean crossesRepositoryBoundary(Path repoRoot, Path pom) {
        Path dir = pom.getParent();
        while (dir != null && !dir.equals(repoRoot)) {
            if (Files.exists(dir.resolve(".git"))) return true;
            dir = dir.getParent();
        }
        return false;
    }

    private static boolean declaresOwnVersion(Path pom, String version) {
        try {
            // The model's own version is null when the module inherits
            // it. Asking the model rather than the text matters: an
            // inheriting module whose dependency happens to carry the
            // same version string would otherwise look like a
            // declaration, and the rewrite would land on that
            // dependency — an unrelated coordinate.
            return version.equals(PomModel.parse(pom).model().getVersion());
        } catch (IOException e) {
            return false;
        }
    }

    private void rewrite(Path pomPath,
                         java.util.function.UnaryOperator<String> transform,
                         String what) {
        try {
            String before = Files.readString(pomPath, StandardCharsets.UTF_8);
            String after = transform.apply(before);
            if (!after.equals(before)) {
                Files.writeString(pomPath, after, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new MojoException("Version pass failed rewriting " + what
                    + " in " + pomPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Whether an exact tag exists in the repository.
     *
     * @param dir the repository directory
     * @param tag the tag name
     * @return true when the tag exists
     */
    private static boolean tagExists(File dir, String tag) {
        try {
            String out = ReleaseSupport.execCapture(dir,
                    "git", "tag", "-l", tag);
            return out != null && !out.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Commit a mission repository's pending changes. The version pass and
     * post-bump author every tracked change (preflight guaranteed clean
     * trees), staged with {@code git add -u}; the root additionally
     * stages the mission's (new, untracked) record file by exact path.
     */
    private void commitRepo(File dir, String message) {
        ReleaseSupport.exec(dir, log, "git", "add", "-u");
        if (dir.equals(root)) {
            ReleaseSupport.exec(dir, log, "git", "add", "--",
                    rootRelativeRecordPath(), rootRelativeNotesPath());
        }
        ReleaseSupport.exec(dir, log, "git", "commit", "-m", message);
    }

    private String rootRelativeRecordPath() {
        return root.toPath().relativize(
                ReleaseRecordFile.pathFor(root.toPath(), missionLabel))
                .toString();
    }

    /**
     * The mission's what-changed notes file, beside the record
     * ({@code releases/release-<mission>-notes.md}).
     */
    private Path notesPath() {
        Path record = ReleaseRecordFile.pathFor(root.toPath(), missionLabel);
        return record.resolveSibling(record.getFileName().toString()
                .replace(".yaml", "-notes.md"));
    }

    private String rootRelativeNotesPath() {
        return root.toPath().relativize(notesPath()).toString();
    }

    /**
     * Pin every member of the working set at the commit it holds right
     * now — the state being released — so the manifest carried in the
     * tagged root tree describes a coherent, buildable working set.
     *
     * <p>All members, not only the releasing ones. The mission record
     * names what was released, which is a smaller set by design
     * (#997); the installers are built from the whole working set, so
     * something has to say what the whole set was. Before this, a
     * release tag carried released {@code version:} fields beside
     * {@code sha:} pins left over from the last checkpoint — versions
     * from the release, commits from days earlier — and anything that
     * materialised from those pins built a state that never existed as
     * a release (IKE-Network/ike-issues#1017).
     *
     * @param memberDirs every member's directory, by member name
     */
    private void pinWorkingSetState(Map<String, File> memberDirs) {
        Map<String, String> pins = new LinkedHashMap<>();
        for (Map.Entry<String, File> member : memberDirs.entrySet()) {
            try {
                pins.put(member.getKey(), ReleaseSupport.execCapture(
                        member.getValue(), "git", "rev-parse", "HEAD").trim());
            } catch (Exception e) {
                throw new MojoException("Cannot read the released commit of "
                        + member.getKey() + ": " + e.getMessage(), e);
            }
        }
        try {
            ManifestWriter.updateShas(root.toPath().resolve("workspace.yaml"),
                    pins);
        } catch (Exception e) {
            throw new MojoException("Cannot pin the working set state: "
                    + e.getMessage(), e);
        }
        log.info("    pinned " + pins.size()
                + " member(s) at their released commits");
    }

    /** Write the mission's record with a row per releasing repository. */
    private void writeMissionRecord() {
        try {
            Path recordPath = ReleaseRecordFile.pathFor(root.toPath(),
                    missionLabel);
            ReleaseRecord record = Files.exists(recordPath)
                    ? ReleaseRecordFile.read(recordPath)
                    : ReleaseRecord.start(missionLabel, recordDate);
            for (ReleasingRepo m : allRepos()) {
                String sha = ReleaseSupport.execCapture(m.dir(),
                        "git", "rev-parse", "HEAD").trim();
                record = record.withMember(m.name(),
                        new ReleaseRecord.MemberRelease(m.releaseVersion(),
                                tagStyle.tagFor(m.releaseVersion()), sha, recordDate));
            }
            ReleaseRecordFile.write(recordPath, record);
        } catch (Exception e) {
            throw new MojoException("Mission record write failed: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Write the mission's what-changed notes beside the record
     * (IKE-Network/ike-issues#1016): for each releasing repository, the
     * non-mechanical commits between its previous release tag and the
     * release commit — subjects with their bodies, goal-authored cadence
     * and hygiene commits filtered — so the delivery chain publishes a
     * narrative from the tagged tree instead of a bare member table.
     * Repositories whose only movement is version alignment are listed
     * on one summary line; a repository with no previous release tag is
     * noted as a first release rather than dumping its whole history.
     *
     * <p>A change made across the whole working set — a parent adoption,
     * a hygiene sweep — lands as the same commit in every member, and
     * reproducing it under all fifteen headings buried the members' own
     * news (mission 7's notes were 31&nbsp;KB, mostly one repeated
     * adoption). Entries common to every diffed repository are therefore
     * hoisted into a single <em>Across the working set</em> section and
     * dropped from the per-member ones.
     */
    private void writeMissionNotes() {
        StringBuilder md = new StringBuilder();
        md.append("# What changed — mission ").append(missionLabel)
                .append(" (").append(recordDate).append(")\n");

        // Diff every repository first: the shared entries can only be
        // known once every member's entries are in hand.
        Map<String, String> previousTags = new LinkedHashMap<>();
        Map<String, List<String>> entriesByRepo = new LinkedHashMap<>();
        for (ReleasingRepo m : allRepos()) {
            String previousTag =
                    WsReleaseDraftMojo.latestReleaseTag(m.dir(), tagStyle);
            if (previousTag == null) {
                continue;
            }
            previousTags.put(m.name(), previousTag);
            entriesByRepo.put(m.name(), notesEntriesFor(m.dir(), previousTag));
        }
        List<String> shared = sharedByEveryMember(entriesByRepo);
        if (!shared.isEmpty()) {
            md.append("\n## Across the working set\n\n");
            for (String entry : shared) {
                md.append(entry).append('\n');
            }
        }

        List<String> alignmentOnly = new ArrayList<>();
        for (ReleasingRepo m : allRepos()) {
            String previousTag = previousTags.get(m.name());
            if (previousTag == null) {
                md.append("\n## ").append(m.name()).append(' ')
                        .append(m.releaseVersion()).append("\n\n")
                        .append("- _First release of this member._\n");
                continue;
            }
            List<String> entries = new ArrayList<>(entriesByRepo.get(m.name()));
            entries.removeAll(shared);
            if (entries.isEmpty()) {
                alignmentOnly.add(m.name() + " " + m.releaseVersion());
            } else {
                md.append("\n## ").append(m.name()).append(' ')
                        .append(m.releaseVersion()).append("  ·  ")
                        .append(previousTag).append(" → ")
                        .append(tagStyle.tagFor(m.releaseVersion()))
                        .append("\n\n");
                for (String entry : entries) {
                    md.append(entry).append('\n');
                }
            }
        }
        if (!alignmentOnly.isEmpty()) {
            // These members did carry the shared change — by
            // construction it is in every diffed repository — so claiming
            // they moved for version alignment alone would be false.
            md.append(shared.isEmpty()
                            ? "\nMoved for version alignment only: "
                            : "\nCarried only the change made across the working set: ")
                    .append(String.join(", ", alignmentOnly)).append(".\n");
        }
        try {
            Files.writeString(notesPath(), md.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Mission notes write failed: "
                    + e.getMessage(), e);
        }
    }

    /**
     * The entries every diffed repository shares — a change made across
     * the whole working set, such as a parent adoption or a hygiene
     * sweep, which git records once per member.
     *
     * <p>Membership is exact: an entry qualifies only when it appears in
     * <em>every</em> repository that was diffed, which admits no judgment
     * and cannot mistake a change shared by several members for one made
     * to all. A single diffed repository yields nothing — its entries are
     * its own news, not the working set's. Order follows the first
     * repository's, so the hoisted section reads in commit order.
     *
     * <p>Static and pure for direct unit testing.
     *
     * @param entriesByRepo formatted entries per repository name, in
     *                      release order
     * @return the shared entries, first repository's order; empty when
     *         fewer than two repositories were diffed or nothing is common
     */
    static List<String> sharedByEveryMember(
            Map<String, List<String>> entriesByRepo) {
        if (entriesByRepo.size() < 2) {
            return List.of();
        }
        Iterator<List<String>> repos = entriesByRepo.values().iterator();
        List<String> shared = new ArrayList<>(repos.next());
        while (repos.hasNext()) {
            shared.retainAll(repos.next());
        }
        return shared;
    }

    /**
     * The narrative entries for one repository: commits between the
     * previous release tag and HEAD, formatted by
     * {@link #formatNotesEntries(String)}.
     *
     * @param dir         the repository directory
     * @param previousTag the repository's previous release tag
     * @return formatted entries, oldest last; empty when every commit
     *         since the tag is mechanical
     */
    private List<String> notesEntriesFor(File dir, String previousTag) {
        String raw;
        try {
            raw = ReleaseSupport.execCapture(dir, "git", "log",
                    previousTag + "..HEAD", "--no-merges",
                    "--pretty=format:%s%x1f%b%x1e");
        } catch (Exception e) {
            throw new MojoException("Mission notes: cannot read "
                    + dir.getName() + " history since " + previousTag
                    + ": " + e.getMessage(), e);
        }
        return formatNotesEntries(raw);
    }

    /**
     * Goal-authored hygiene subjects filtered from the notes on top of
     * the release-cadence patterns: checkpoints, scaffold
     * reconciliation, workspace alignment, and post-release manifest
     * sync say nothing about what a release means to its consumers.
     */
    private static final java.util.regex.Pattern NOTES_MECHANICAL_PATTERN =
            java.util.regex.Pattern.compile(
                    "^(checkpoint: .*"
                            + "|scaffold: .*"
                            + "|ws:scaffold.*"
                            + "|workspace: .*"
                            + "|chore: align upstream versions.*"
                            + "|post-release: .*)$");

    /** Trailer lines dropped from commit bodies in the notes. */
    private static final java.util.regex.Pattern NOTES_TRAILER_PATTERN =
            java.util.regex.Pattern.compile(
                    "^(Refs:|Fixes:|Co-[Aa]uthored-[Bb]y:|Signed-off-by:).*");

    /**
     * Format raw {@code git log} output (subject {@code US} body
     * {@code RS}, as produced with
     * {@code --pretty=format:%s%x1f%b%x1e}) into Markdown notes
     * entries: one {@code - **subject**} bullet per non-mechanical
     * commit, body lines indented beneath it with trailers and blank
     * lines dropped. Static and pure for direct unit testing.
     *
     * @param raw the raw log output; {@code null} or blank yields no
     *            entries
     * @return formatted entries in log order
     */
    static List<String> formatNotesEntries(String raw) {
        List<String> entries = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return entries;
        }
        for (String commit : raw.split("\u001e")) {
            String[] parts = commit.split("\u001f", 2);
            String subject = parts[0].strip();
            if (subject.isEmpty()
                    || WsReleaseDraftMojo.isReleaseCadenceCommit(subject)
                    || NOTES_MECHANICAL_PATTERN.matcher(subject).matches()) {
                continue;
            }
            StringBuilder entry = new StringBuilder("- **")
                    .append(subject).append("**");
            if (parts.length > 1) {
                for (String line : parts[1].split("\n")) {
                    String stripped = line.strip();
                    if (stripped.isEmpty()
                            || NOTES_TRAILER_PATTERN.matcher(stripped)
                                    .matches()) {
                        continue;
                    }
                    // A quoted body's own headings would nest inside the
                    // member sections — a merge commit's body did exactly
                    // that in mission 7, splitting one member's news across
                    // headings of its own. The text stays; only the heading
                    // markers go.
                    entry.append("\n  ")
                            .append(stripped.replaceFirst("^#{1,6}\\s*", ""));
                }
            }
            entries.add(entry.toString());
        }
        return entries;
    }

    private String[] verifyCommand(String mvn, boolean skipTests) {
        // The release-mode signal for the workspace extension's
        // intra-set version resolution (ike-issues#1019): bystanders
        // bind to their released versions, version-passed members to
        // reactor-current.
        List<String> cmd = new ArrayList<>(List.of(mvn, "-B", "-ntp",
                "clean", "install", "-P", "release",
                "-Dike.workspace.release=true", "-T", "1"));
        if (skipTests) {
            cmd.add("-DskipTests");
        }
        return cmd.toArray(new String[0]);
    }

    private String[] deployCommand(String mvn) {
        Set<String> selectors = new LinkedHashSet<>();
        for (ReleasingRepo m : allRepos()) {
            selectors.add(":" + m.artifactId());
        }
        return new String[] {mvn, "-B", "-ntp", "deploy",
                "-pl", String.join(",", selectors),
                "-P", "release,signArtifacts",
                "-Dike.workspace.release=true", "-T", "1", "-DskipTests"};
    }
}
