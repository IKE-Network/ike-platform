package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;

import network.ike.workspace.Defaults;
import network.ike.workspace.FeatureName;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.Subproject;
import network.ike.workspace.VersionSupport;
import network.ike.workspace.WorkingSet;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Start a feature in a <em>sibling workspace clone</em> beside the primary
 * (IKE-Network/ike-issues#201 epic, #207, reshaped into the feature-start
 * 2×2 in #770).
 *
 * <p>This is the <em>sibling</em> column of {@code ws:feature-start}: where
 * {@code ws:feature-start-publish} branches the primary in place — which
 * under Syncthing rewrites the working tree while the other machine's
 * {@code .git/HEAD} stays put, the failure mode that motivates this goal —
 * {@code ws:feature-start-sibling-publish} makes a second clone of the whole
 * workspace alongside the primary, checked out on {@code feature/<name>}
 * from inception. The primary never leaves its current branch; the feature
 * lives in its own directory and is disposable ({@code rm -rf}) after merge.
 * The same isolation makes concurrent same-machine work safe: each sibling
 * has its own working tree, so two lines of work never stage each other's
 * edits.
 *
 * <p><strong>What it does</strong> (workspace mode):
 * <ol>
 *   <li>Validates {@code feature} through {@link FeatureName} and computes
 *       the sibling directory {@code <parent>/<baseName>-<feature>/} from the
 *       resolved working set's {@link WorkingSet#baseName() baseName};
 *       refuses if it already exists.</li>
 *   <li>Resolves the <em>base branch</em> to clone from: {@code -Dfrom} when
 *       given, else the primary's current branch — guarded so a sibling is
 *       not silently cut from an unexpected branch (see
 *       {@link SiblingBaseResolution}).</li>
 *   <li>Clones the workspace root, then each subproject in topological
 *       order, into the sibling with
 *       {@code git clone --reference <primary>/<component> --dissociate -b
 *       <base> <remote> <sibling>/<component>}. {@code --reference} borrows
 *       the object database from the primary's local clone (the
 *       order-of-magnitude win for large histories like tinkar-core's
 *       492 MB); {@code --dissociate} then copies the borrowed objects so
 *       the sibling is fully self-contained.</li>
 *   <li>Creates {@code feature/<name>} in each clone and applies the same
 *       version qualification and BOM/property cascade that
 *       {@code ws:feature-start-publish} produces in-place, via the shared
 *       {@link FeatureStartSupport}.</li>
 *   <li>Rewrites the sibling's {@code workspace.yaml} branch fields and
 *       commits.</li>
 * </ol>
 *
 * <p><strong>No push.</strong> Externally visible side effects stay opt-in
 * (per {@code feedback_workspace_ops_completion}). The clones, branches,
 * version commits, and {@code workspace.yaml} update are all recoverable
 * local effects and happen by default; pushing to origin is not.
 *
 * <pre>{@code
 * mvn ws:feature-start-sibling-publish -Dfeature=jira-456
 * #   creates ../<workspace>-jira-456/ with every component on
 * #   feature/jira-456, then: cd ../<workspace>-jira-456 && work.
 * }</pre>
 *
 * @see FeatureName for the sibling-directory naming rule
 * @see FeatureStartSupport for the shared version/cascade logic
 * @see SiblingBaseResolution for the base-branch resolution + guard
 * @see FeatureStartSiblingDraftMojo for the read-only preview
 */
@Mojo(name = "feature-start-sibling-publish", projectRequired = false, aggregator = true)
public class FeatureStartSiblingPublishMojo extends AbstractWorkspaceMojo {

    /** Feature name. Branch will be {@code feature/<name>}. Prompted if omitted. */
    @Parameter(property = "feature")
    String feature;

    /**
     * Skip POM version qualification. Useful for document workspaces whose
     * subprojects have no versioned Maven artifacts. Matches the
     * {@code ws:feature-start} flag of the same name.
     */
    @Parameter(property = "skipVersion", defaultValue = "false")
    boolean skipVersion;

    /**
     * Explicit base branch to cut the sibling from. When unset, the sibling
     * is based on the primary's current branch — guarded so a sibling is
     * never silently cut from a non-base branch (see
     * {@link SiblingBaseResolution}). Pass {@code -Dfrom=<branch>} to base
     * the sibling on a branch other than the manifest's base deliberately.
     */
    @Parameter(property = "from")
    String from;

    /**
     * Opt in to building the sibling's whole reactor from its root once the
     * clone is made — proving it compiles and populating the local repository
     * with the sibling's {@code -<feature>-SNAPSHOT} artifacts. Off by default
     * (creating the sibling is the goal's effect; building is verification).
     * The dedicated {@code ws:feature-start-sibling-publish-verify} goal turns
     * this on by default. See IKE-Network/ike-issues#777.
     */
    @Parameter(property = "verify", defaultValue = "false")
    boolean verify;

    /**
     * Maven goals run from the sibling root when {@link #verify} is set (or for
     * the {@code -verify} goal). Defaults to {@code clean install -DskipTests
     * -T 1C}: {@code install} (not just {@code verify}) so every subproject's
     * {@code -<feature>-SNAPSHOT} lands in the local repository — which is what
     * lets a later partial ({@code -pl … -am}) or IDE build resolve them, the
     * gap that made an unbuilt sibling look broken (#777). A full-reactor build
     * also sidesteps the trap directly. Override for a lighter or stricter
     * check, e.g. {@code -Dws.sibling.verifyGoals="clean verify -DskipTests"}.
     */
    @Parameter(property = "ws.sibling.verifyGoals",
            defaultValue = "clean install -DskipTests -T 1C")
    String verifyGoals;

    /** Creates this goal instance. */
    public FeatureStartSiblingPublishMojo() {}

    /**
     * Whether to build the sibling reactor after creating it. The base goal
     * returns the {@code -Dverify} flag; the {@code -verify} subclass overrides
     * {@link #verifyByDefault()} so the build is on by default.
     *
     * @return {@code true} when the post-create reactor build should run
     */
    protected final boolean shouldVerify() {
        return verify || verifyByDefault();
    }

    /**
     * Default for the post-create reactor build when {@code -Dverify} is not
     * given. {@code false} here; {@link FeatureStartSiblingPublishVerifyMojo}
     * overrides it to {@code true}.
     *
     * @return {@code false} — the base goal does not build unless asked
     */
    protected boolean verifyByDefault() {
        return false;
    }

    /**
     * The goal identity used for this run's report file. The base goal reports
     * as {@link WsGoal#FEATURE_START_SIBLING_PUBLISH}; the {@code -verify}
     * subclass overrides this so its report lands in its own file.
     *
     * @return the {@link WsGoal} this invocation reports as
     */
    protected WsGoal reportGoal() {
        return WsGoal.FEATURE_START_SIBLING_PUBLISH;
    }

    /**
     * Build the sibling's whole reactor from its root, failing loud if it does
     * not build. Runs {@link #verifyGoals} (default {@code clean install
     * -DskipTests -T 1C}) as a subprocess against the freshly created sibling,
     * mirroring {@code ws:checkpoint-publish}'s pre-tag reactor gate (#689).
     *
     * <p>On failure the clone is left intact — the message says so — so the
     * user can fix the build in place rather than re-clone. Overridable so the
     * #777 coverage can drive the pass/fail outcome without a nested build.
     *
     * @param siblingRoot the sibling workspace root to build from
     * @throws MojoException if the reactor build fails, or the subprocess setup
     *                       itself fails
     */
    protected void verifySibling(File siblingRoot) throws MojoException {
        String mvn = WsReleaseDraftMojo.resolveMvnCommand(siblingRoot);
        getLog().info("");
        getLog().info("  Verifying the sibling reactor builds (" + verifyGoals
                + ") ...");
        List<String> cmd = new ArrayList<>();
        cmd.add(mvn);
        for (String token : verifyGoals.split("\\s+")) {
            if (!token.isBlank()) {
                cmd.add(token);
            }
        }
        cmd.add("-B");
        try {
            ReleaseSupport.exec(siblingRoot, getLog(), cmd.toArray(new String[0]));
            getLog().info(Ansi.green("  ✓ ") + "Sibling reactor build succeeded");
        } catch (MojoException e) {
            throw new MojoException(
                    "Sibling created at " + siblingRoot.getAbsolutePath()
                    + ", but its reactor build failed. The clone is intact — fix "
                    + "the build there, or re-run without verification. Cause: "
                    + e.getMessage(), e);
        }
    }

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        feature = requireParam(feature, "feature",
                "Feature name (without feature/ prefix)");
        FeatureName featureName = validateFeatureName(feature);
        String branchName = "feature/" + feature;

        if (!isWorkspaceMode()) {
            return executeBareMode(featureName, branchName);
        }

        WorkspaceGraph graph = loadGraph();
        File primaryRoot = workspaceRoot();
        Defaults defaults = graph.manifest().defaults();
        String manifestBase = (defaults != null && defaults.branch() != null)
                ? defaults.branch() : "main";

        // Resolve the base branch to clone from, guarding against an
        // unexpected primary branch (#770).
        String base = SiblingBaseResolution.resolveAndGuard(
                from, gitBranch(primaryRoot), manifestBase);

        // Compute the sibling directory alongside the primary workspace,
        // based on the working set's baseName (the workspace-root
        // artifactId, else the dir name) rather than the raw dir name.
        String baseName = resolveWorkingSet().baseName();
        File parent = primaryRoot.getParentFile();
        if (parent == null) {
            throw new MojoException(
                    "Cannot resolve the parent of the workspace root " + primaryRoot
                    + "; sibling clones live alongside the primary.");
        }
        String siblingName = featureName.siblingDirectoryName(baseName);
        File siblingRoot = new File(parent, siblingName);
        if (siblingRoot.exists()) {
            throw new MojoException(
                    "Sibling '" + siblingName + "' already exists at "
                    + siblingRoot.getAbsolutePath()
                    + ". Remove it (rm -rf) or pick a different feature name.");
        }

        // The workspace root must be a git repo with an origin so the sibling
        // clones from the real upstream (not the primary's local path).
        String rootRemote = gitOriginUrl(primaryRoot);
        if (rootRemote == null) {
            throw new MojoException(
                    "Workspace root '" + baseName + "' has no git 'origin' remote; "
                    + "ws:feature-start-sibling clones the root from its upstream. "
                    + "Add one (git remote add origin <url>) and try again.");
        }

        getLog().info("");
        getLog().info(header("Feature Start (sibling)"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName);
        getLog().info("  Base:    " + base);
        getLog().info("  Sibling: " + siblingRoot.getAbsolutePath());
        getLog().info("");

        List<String> sorted = graph.topologicalSort();

        // 1. Clone the workspace root into the sibling directory. Cloning the
        //    root first materializes the sibling directory itself. Every clone
        //    is cut from the same resolved base branch.
        getLog().info("  Cloning workspace root → " + siblingName);
        cloneOnFeatureBranch(parent, siblingName, primaryRoot, rootRemote,
                base, branchName);

        // 2. Clone each subproject into <sibling>/<name>, in topological order.
        List<String> branched = new ArrayList<>();
        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            String remote = sub.repo();
            if (remote == null || remote.isEmpty()) {
                getLog().warn("  ⚠ " + name
                        + " — no repo URL in workspace.yaml, skipping");
                continue;
            }
            File primaryComp = new File(primaryRoot, name);
            getLog().info("  Cloning " + name + " → " + siblingName + "/" + name);
            cloneOnFeatureBranch(siblingRoot, name, primaryComp, remote,
                    base, branchName);
            branched.add(name);
        }

        // 3. Version qualification + cascade on the sibling, producing the
        //    same POM state ws:feature-start-publish would have in-place.
        FeatureStartSupport support = new FeatureStartSupport(getLog());
        Map<String, String> versionByName = new LinkedHashMap<>();
        String rootQualified = null;
        if (!skipVersion) {
            for (String name : branched) {
                Subproject sub = graph.manifest().subprojects().get(name);
                File dir = new File(siblingRoot, name);
                String effectiveVersion = effectiveVersion(sub, dir);
                if (effectiveVersion == null || effectiveVersion.isEmpty()) {
                    continue;
                }
                String newVersion = VersionSupport.branchQualifiedVersion(
                        effectiveVersion, branchName);
                versionByName.put(name, newVersion);
                support.setPomVersion(dir, effectiveVersion, newVersion);
                ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
                ReleaseSupport.exec(dir, getLog(), "git", "commit", "-m",
                        "feature: set version " + newVersion + " for " + branchName);
                getLog().info(Ansi.green("  ✓ ") + String.format("%-24s %s → %s",
                        name, effectiveVersion, newVersion));
            }
            support.removeIntraReactorPins(siblingRoot, branched, true);
            support.cascadeVersionProperties(graph, siblingRoot, sorted, branchName);
            support.cascadeBomProperties(graph, siblingRoot, sorted, branchName);
            support.cascadeBomImports(graph, siblingRoot, sorted, branchName);

            // Qualify the aggregator's OWN POM version too — feature-start
            // branches the workspace root, so its version takes the same
            // qualifier as every subproject. The in-place path already does
            // this (FeatureStartDraftMojo.branchWorkspaceRepo, ike-issues#721);
            // the sibling path had not ported it, leaving the root pom at its
            // base version (ike-issues#777). The aggregator parents off
            // ike-parent (not itself) and subprojects parent off ike-parent
            // too, so this is a standalone <version> edit. The cascades above
            // commit their own changes, so this is its own commit, like each
            // subproject's. The merge/squash finish paths already de-qualify
            // the aggregator (FeatureFinishSupport.stripWorkspaceRootPom), so
            // qualifying it here makes the round-trip symmetric.
            File rootPom = new File(siblingRoot, "pom.xml");
            if (rootPom.exists()) {
                try {
                    String rootVersion = ReleaseSupport.readPomVersion(rootPom);
                    String qualified = VersionSupport.branchQualifiedVersion(
                            rootVersion, branchName);
                    if (!qualified.equals(rootVersion)) {
                        support.setPomVersion(siblingRoot, rootVersion, qualified);
                        ReleaseSupport.exec(siblingRoot, getLog(),
                                "git", "add", "pom.xml");
                        ReleaseSupport.exec(siblingRoot, getLog(), "git", "commit",
                                "-m", "feature: set version " + qualified
                                        + " for " + branchName);
                        rootQualified = qualified;
                        getLog().info(Ansi.green("  ✓ ") + String.format(
                                "%-24s %s → %s", baseName, rootVersion, qualified));
                    }
                } catch (MojoException e) {
                    getLog().warn("  Could not qualify aggregator root version: "
                            + e.getMessage());
                }
            }
        }

        // 4. Rewrite the sibling's workspace.yaml branch fields and commit.
        Path siblingManifest = new File(siblingRoot, "workspace.yaml").toPath();
        if (Files.exists(siblingManifest) && !branched.isEmpty()) {
            try {
                Map<String, String> branchUpdates = new LinkedHashMap<>();
                for (String name : branched) {
                    branchUpdates.put(name, branchName);
                }
                ManifestWriter.updateBranches(siblingManifest, branchUpdates);
                ReleaseSupport.exec(siblingRoot, getLog(),
                        "git", "add", "workspace.yaml");
                ReleaseSupport.exec(siblingRoot, getLog(), "git", "commit", "-m",
                        "workspace: update branches for " + branchName);
                getLog().info("  Updated workspace.yaml branches for "
                        + branched.size() + " components");
            } catch (IOException e) {
                getLog().warn("  Could not update sibling workspace.yaml: "
                        + e.getMessage());
            }
        }

        getLog().info("");
        getLog().info(Ansi.green("  ✓ ") + "Sibling ready: "
                + siblingRoot.getAbsolutePath());
        getLog().info("    cd " + siblingRoot.getAbsolutePath());
        getLog().info("    # work, then ws:commit-publish / ws:push; "
                + "rm -rf to discard");
        getLog().info("");

        boolean verified = false;
        if (shouldVerify()) {
            verifySibling(siblingRoot);
            verified = true;
        }

        // Build the report rows over the whole working set — every
        // subproject AND the aggregator (workspace root). The aggregator
        // row makes the sibling root's own clone + branch + qualified
        // version visible, the staleness a subproject-only table hid (#763).
        // Identity and kind come from the resolved working set; each row's
        // version/branch/sha are read from the corresponding SIBLING dir,
        // since the goal's effect lives in the sibling, not the primary.
        List<WorkingSetReportTable.Row> rows = new ArrayList<>();
        for (WorkingSet.Member member : resolveWorkingSet().members()) {
            File dir = member.isAggregator()
                    ? siblingRoot
                    : new File(siblingRoot, member.name());
            String effect;
            if (member.isAggregator()) {
                // The aggregator is cloned + branched, its own POM version
                // qualified like every subproject (#777), and its
                // workspace.yaml rewritten + committed.
                effect = (rootQualified != null)
                        ? "cloned + branched " + branchName + " → " + rootQualified
                        : "cloned + branched " + branchName;
            } else if (branched.contains(member.name())) {
                String qualified = versionByName.get(member.name());
                effect = (qualified != null)
                        ? "cloned + branched " + branchName
                                + " → " + qualified
                        : "cloned + branched " + branchName;
            } else {
                effect = "skipped (no repo URL)";
                rows.add(new WorkingSetReportTable.Row(member,
                        WorkingSetReportTable.NONE, WorkingSetReportTable.NONE,
                        WorkingSetReportTable.NONE, effect));
                continue;
            }
            rows.add(new WorkingSetReportTable.Row(member,
                    siblingVersion(dir), gitBranch(dir), gitShortSha(dir),
                    effect));
        }

        return new WorkspaceReportSpec(reportGoal(),
                buildReport(siblingName, siblingRoot, branchName, base, rows,
                        verified));
    }

    /**
     * Bare mode (no {@code workspace.yaml}): fork the current single repo
     * into {@code <parent>/<repo>-<feature>/} on the feature branch. The
     * single-repo case of the same operation — a working set of one
     * (ike-issues#601). No manifest to rewrite and no cascade; otherwise
     * identical to one component of the workspace flow. The sibling-dir base
     * is the repo's {@code baseName} (its dir name), and the base branch is
     * resolved + guarded the same way (manifest base is {@code main}, there
     * being no manifest).
     *
     * @param featureName the validated feature name
     * @param branchName  the {@code feature/<name>} branch to create
     * @return the goal's report
     * @throws MojoException if the repo has no origin, the sibling exists,
     *                       or a git step fails
     */
    private WorkspaceReportSpec executeBareMode(FeatureName featureName,
                                                String branchName)
            throws MojoException {
        // The single repo to fork is a working set of one (ike-issues#611) —
        // resolve it through the shared resolver, not user.dir directly.
        WorkingSet workingSet = resolveWorkingSet();
        File repo = workingSet.members().getFirst().directory().toFile();
        if (!new File(repo, ".git").exists()) {
            throw new MojoException(
                    "ws:feature-start-sibling: " + repo + " is not a git repository "
                    + "and no workspace.yaml was found — run it inside a repo or a "
                    + "workspace.");
        }
        String remote = gitOriginUrl(repo);
        if (remote == null) {
            throw new MojoException(
                    "Repository '" + repo.getName() + "' has no git 'origin' "
                    + "remote; ws:feature-start-sibling clones from the upstream. "
                    + "Add one (git remote add origin <url>) and try again.");
        }
        File parent = repo.getParentFile();
        if (parent == null) {
            throw new MojoException(
                    "Cannot resolve the parent of " + repo
                    + "; the sibling clone lives alongside it.");
        }
        // Base resolution + guard. With no manifest, the base branch is main.
        String base = SiblingBaseResolution.resolveAndGuard(
                from, gitBranch(repo), "main");

        String siblingName = featureName.siblingDirectoryName(workingSet.baseName());
        File siblingRoot = new File(parent, siblingName);
        if (siblingRoot.exists()) {
            throw new MojoException(
                    "Sibling '" + siblingName + "' already exists at "
                    + siblingRoot.getAbsolutePath()
                    + ". Remove it (rm -rf) or pick a different feature name.");
        }

        getLog().info("");
        getLog().info(header("Feature Start (sibling)"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName);
        getLog().info("  Base:    " + base);
        getLog().info("  Sibling: " + siblingRoot.getAbsolutePath());
        getLog().info("  Mode:    single repo (no workspace.yaml)");
        getLog().info("");

        getLog().info("  Cloning " + repo.getName() + " → " + siblingName);
        cloneOnFeatureBranch(parent, siblingName, repo, remote, base, branchName);

        String qualified = "—";
        if (!skipVersion) {
            File pom = new File(siblingRoot, "pom.xml");
            String effectiveVersion = null;
            if (pom.exists()) {
                try {
                    effectiveVersion = ReleaseSupport.readPomVersion(pom);
                } catch (MojoException e) {
                    getLog().debug("Could not read POM version: " + e.getMessage());
                }
            }
            if (effectiveVersion != null && !effectiveVersion.isEmpty()) {
                String newVersion = VersionSupport.branchQualifiedVersion(
                        effectiveVersion, branchName);
                qualified = newVersion;
                new FeatureStartSupport(getLog()).setPomVersion(
                        siblingRoot, effectiveVersion, newVersion);
                ReleaseSupport.exec(siblingRoot, getLog(), "git", "add", "pom.xml");
                ReleaseSupport.exec(siblingRoot, getLog(), "git", "commit", "-m",
                        "feature: set version " + newVersion + " for " + branchName);
                getLog().info(Ansi.green("  ✓ ") + String.format("%-24s %s → %s",
                        repo.getName(), effectiveVersion, newVersion));
            }
        }

        getLog().info("");
        getLog().info(Ansi.green("  ✓ ") + "Sibling ready: "
                + siblingRoot.getAbsolutePath());
        getLog().info("    cd " + siblingRoot.getAbsolutePath());
        getLog().info("    # work, then ws:commit-publish / ws:push; "
                + "rm -rf to discard");
        getLog().info("");

        boolean verified = false;
        if (shouldVerify()) {
            verifySibling(siblingRoot);
            verified = true;
        }

        // Single-repo working set: one member (the repo, resolved as the
        // aggregator), mapped to its sibling clone. The aggregator is a row
        // here too, so a bare-mode report is shaped identically to the
        // workspace one (#763/#766).
        WorkingSet.Member member = workingSet.members().getFirst();
        String effect = !"—".equals(qualified)
                ? "cloned + branched " + branchName + " → " + qualified
                : "cloned + branched " + branchName;
        List<WorkingSetReportTable.Row> rows = List.of(
                new WorkingSetReportTable.Row(member, siblingVersion(siblingRoot),
                        gitBranch(siblingRoot), gitShortSha(siblingRoot), effect));
        return new WorkspaceReportSpec(reportGoal(),
                buildReport(siblingName, siblingRoot, branchName, base, rows,
                        verified));
    }

    /**
     * Clone {@code remote} into {@code workDir/targetName} on
     * {@code baseBranch}, borrowing objects from {@code referenceDir} when it
     * is a local clone, then create and check out {@code branchName}.
     *
     * <p>When {@code referenceDir} has no {@code .git}, the borrow is skipped
     * and a full clone is performed (with a warning) so a not-yet-cloned
     * primary component doesn't fail the whole goal.
     *
     * @param workDir       directory the clone is created under
     * @param targetName    name of the directory to create under {@code workDir}
     * @param referenceDir  the primary's local clone to borrow objects from
     * @param remote        the upstream URL to clone from
     * @param baseBranch    the mainline branch to clone and branch from
     * @param branchName    the feature branch to create and check out
     * @throws MojoException if a git invocation fails
     */
    private void cloneOnFeatureBranch(File workDir, String targetName,
                                      File referenceDir, String remote,
                                      String baseBranch, String branchName)
            throws MojoException {
        List<String> args = new ArrayList<>();
        args.add("git");
        args.add("clone");
        if (new File(referenceDir, ".git").exists()) {
            args.add("--reference");
            args.add(referenceDir.getAbsolutePath());
            args.add("--dissociate");
        } else {
            getLog().warn("  ⚠ no local clone at " + referenceDir
                    + " to borrow from — full clone of " + targetName);
        }
        args.add("-b");
        args.add(baseBranch);
        args.add(remote);
        args.add(targetName);
        ReleaseSupport.exec(workDir, getLog(), args.toArray(new String[0]));

        File target = new File(workDir, targetName);
        ReleaseSupport.exec(target, getLog(), "git", "checkout", "-b", branchName);
    }

    /**
     * Resolve a subproject's effective version: the {@code workspace.yaml}
     * value first, falling back to the cloned POM's {@code <version>}.
     *
     * @param sub the subproject definition
     * @param dir the subproject's directory in the sibling
     * @return the effective version, or {@code null} if none can be resolved
     */
    private String effectiveVersion(Subproject sub, File dir) {
        String version = sub.version();
        if (version != null && !version.isEmpty()) {
            return version;
        }
        File pom = new File(dir, "pom.xml");
        if (pom.exists()) {
            try {
                return ReleaseSupport.readPomVersion(pom);
            } catch (MojoException e) {
                getLog().debug("Could not read POM version for " + sub.name()
                        + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Read the {@code origin} remote URL of a git repository.
     *
     * @param dir the repository directory
     * @return the origin URL, or {@code null} if {@code dir} is not a git
     *         repository or has no {@code origin} remote
     */
    private String gitOriginUrl(File dir) {
        if (!new File(dir, ".git").exists()) {
            return null;
        }
        try {
            String url = ReleaseSupport.execCapture(dir,
                    "git", "remote", "get-url", "origin");
            return url.isBlank() ? null : url.trim();
        } catch (MojoException e) {
            return null;
        }
    }

    /**
     * Read a sibling directory's POM {@code <version>}: the #763 fix when
     * applied to the aggregator's own clone, whose staleness a
     * subproject-only table hid.
     *
     * @param dir the sibling directory (a clone) to read
     * @return the POM version, or {@link WorkingSetReportTable#NONE} if there
     *         is no readable {@code pom.xml}
     */
    private String siblingVersion(File dir) {
        File pom = new File(dir, "pom.xml");
        if (!pom.exists()) {
            return WorkingSetReportTable.NONE;
        }
        try {
            String version = ReleaseSupport.readPomVersion(pom);
            return (version == null || version.isEmpty())
                    ? WorkingSetReportTable.NONE : version;
        } catch (MojoException e) {
            getLog().debug("Could not read sibling POM version for " + dir
                    + ": " + e.getMessage());
            return WorkingSetReportTable.NONE;
        }
    }

    /**
     * Render the sibling feature-start result as the goal's Markdown report.
     *
     * <p>The working-set table carries one row per member, the aggregator
     * (workspace root) included, so the sibling root's own clone, branch and
     * qualified version are visible (#763/#766). The final column is
     * {@code Effect} — this is a mutating goal and the clones are made on the
     * spot, so each effect is stated as <em>applied</em>.
     *
     * @param siblingName the sibling directory name
     * @param siblingRoot the sibling directory
     * @param branchName  the feature branch created in each clone
     * @param base        the base branch each clone was cut from
     * @param rows        one working-set row per member, aggregator included
     * @param verified    whether the sibling reactor was built and verified
     *                    during this run (the {@code -Dverify} flag / the
     *                    {@code -verify} goal)
     * @return the Markdown report body
     */
    private String buildReport(String siblingName, File siblingRoot,
                               String branchName, String base,
                               List<WorkingSetReportTable.Row> rows,
                               boolean verified) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Sibling:** `" + siblingName + "`")
                .paragraph("**Branch:** `" + branchName + "`")
                .paragraph("**Base:** `" + base + "`")
                .paragraph("**Location:** `" + siblingRoot.getAbsolutePath() + "`");

        WorkingSetReportTable.render(report, "Working set", rows);

        report.paragraph("Each component is a self-contained clone on `"
                + branchName + "`, cut from `" + base
                + "` with `--reference --dissociate` against the primary."
                + " The primary stays on its current branch.");

        if (verified) {
            report.paragraph("**Verified** — `" + verifyGoals
                    + "` from the sibling root succeeded, so the reactor builds"
                    + " and every `-" + branchName.substring("feature/".length())
                    + "-SNAPSHOT` artifact is now installed in the local"
                    + " repository (a later `-pl … -am` or IDE build will resolve"
                    + " them).");
        }

        report.paragraph("**No push** — clones and branches stay local."
                + " Next steps:");
        // The bootstrap build is the first step (#777): a sibling's
        // -<feature>-SNAPSHOT artifacts exist nowhere else, so a partial
        // (-pl … -am) or IDE build cannot resolve them until the whole reactor
        // has been installed once. When already verified this run, it is noted
        // as done but kept for re-running after later edits.
        String buildStep = verified
                ? "./mvnw clean install -DskipTests   "
                        + "# already run by verification — re-run after edits"
                : "./mvnw clean install -DskipTests   "
                        + "# build the whole reactor first — a sibling's\n"
                        + "                                   "
                        + "# -" + branchName.substring("feature/".length())
                        + "-SNAPSHOT artifacts exist nowhere else";
        report.paragraph("```bash\ncd " + siblingRoot.getAbsolutePath()
                + "\n" + buildStep
                + "\n# then work, then:\nmvn ws:commit-publish -Dmessage=\"…\"\n"
                + "mvn ws:push                       # publish the branch\n"
                + "mvn ws:feature-finish-merge-publish -Dfeature="
                + branchName.substring("feature/".length())
                + "   # merge back\nrm -rf " + siblingRoot.getAbsolutePath()
                + "   # discard the sibling\n```");
        return report.build();
    }
}
