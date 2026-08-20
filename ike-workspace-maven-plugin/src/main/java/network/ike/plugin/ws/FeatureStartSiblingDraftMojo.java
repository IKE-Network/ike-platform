package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;

import network.ike.workspace.Defaults;
import network.ike.workspace.FeatureName;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkingSet;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Preview a sibling-clone feature start — the read-only {@code -draft}
 * counterpart of {@link FeatureStartSiblingPublishMojo}
 * (IKE-Network/ike-issues#770).
 *
 * <p>No cloning, no branching, no mutation. The goal resolves the base
 * branch and applies the <em>same guard</em> the publish mojo does (it
 * refuses to plan a sibling off a non-base branch unless {@code -Dfrom} is
 * given), then writes a {@code ws꞉feature-start-sibling-draft.md} report
 * covering:
 * <ul>
 *   <li>the plan — sibling directory {@code <baseName>-<feature>}, the
 *       feature branch, the base branch, and the members that would be
 *       cloned and version-qualified;</li>
 *   <li>preflight checks, each with copy-pasteable remediation when failing:
 *       the sibling dir must not already exist; the primary root and every
 *       member with a repo URL must be materialized locally — the sibling
 *       chains through its local parent (IKE-Network/ike-issues#992); the
 *       base branch must exist locally in the primary;</li>
 *   <li>a clone-cost note — members clone from the primary's local paths
 *       (origin = the parent member): offline, no transport or credential
 *       machinery, objects hardlinked where the filesystem allows, and the
 *       sibling stays {@code rm -rf}-safe.</li>
 * </ul>
 *
 * <pre>{@code
 * mvn ws:feature-start-sibling-draft   -Dfeature=jira-456
 * mvn ws:feature-start-sibling-publish -Dfeature=jira-456
 * }</pre>
 *
 * @see FeatureStartSiblingPublishMojo for the executing counterpart
 * @see SiblingBaseResolution for the shared base-branch resolution + guard
 */
@Mojo(name = "feature-start-sibling-draft", projectRequired = false, aggregator = true)
public class FeatureStartSiblingDraftMojo extends AbstractWorkspaceMojo {

    /** Feature name. Branch will be {@code feature/<name>}. Prompted if omitted. */
    @Parameter(property = "feature")
    String feature;

    /**
     * Skip POM version qualification in the plan. Mirrors the publish flag —
     * a document workspace whose subprojects have no versioned artifacts.
     */
    @Parameter(property = "skipVersion", defaultValue = "false")
    boolean skipVersion;

    /**
     * Explicit base branch to cut the sibling from. When unset, the sibling
     * is based on the primary's current branch — guarded so a sibling is
     * never silently planned off a non-base branch (see
     * {@link SiblingBaseResolution}).
     */
    @Parameter(property = "from")
    String from;

    /** Creates this goal instance. */
    public FeatureStartSiblingDraftMojo() {}

    /** A single preflight check row: the check, pass/fail, and remediation. */
    private record Preflight(String check, boolean ok, String detail) {}

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        feature = requireParam(feature, "feature",
                "Feature name (without feature/ prefix)");
        FeatureName featureName = validateFeatureName(feature);
        String branchName = "feature/" + feature;

        if (!isWorkspaceMode()) {
            return previewBareMode(featureName, branchName);
        }

        WorkspaceGraph graph = loadGraph();
        File primaryRoot = workspaceRoot();
        Defaults defaults = graph.manifest().defaults();
        String manifestBase = (defaults != null && defaults.branch() != null)
                ? defaults.branch() : "main";

        // Resolve base + apply the SAME guard the publish mojo does. A guard
        // violation aborts the preview with the remediation message.
        String base = SiblingBaseResolution.resolveAndGuard(
                from, gitBranch(primaryRoot), manifestBase);

        String baseName = resolveWorkingSet().baseName();
        File parent = primaryRoot.getParentFile();
        if (parent == null) {
            throw new MojoException(
                    "Cannot resolve the parent of the workspace root " + primaryRoot
                    + "; sibling clones live alongside the primary.");
        }
        String siblingName = featureName.siblingDirectoryName(baseName);
        File siblingRoot = new File(parent, siblingName);

        List<String> sorted = graph.topologicalSort();

        getLog().info("");
        getLog().info(header("Feature Start (sibling) — DRAFT"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName);
        getLog().info("  Base:    " + base);
        getLog().info("  Sibling: " + siblingRoot.getAbsolutePath());
        getLog().info("");

        // --- Plan rows: aggregator + each subproject that would be cloned. ---
        List<String[]> planRows = new ArrayList<>();
        planRows.add(new String[]{baseName + " (aggregator)",
                "clone + branch", currentVersionLabel(primaryRoot, branchName)});

        // --- Preflight checks. ---
        List<Preflight> preflight = new ArrayList<>();
        preflight.add(new Preflight(
                "Sibling dir does not already exist",
                !siblingRoot.exists(),
                siblingRoot.exists()
                        ? "`" + siblingRoot.getAbsolutePath() + "` exists — "
                                + "remove it: `rm -rf " + siblingRoot.getAbsolutePath() + "`"
                        : "`" + siblingRoot.getAbsolutePath() + "`"));

        boolean rootMaterialized = new File(primaryRoot, ".git").exists();
        preflight.add(new Preflight(
                "Primary root is a git repository (the sibling chains to it)",
                rootMaterialized,
                rootMaterialized ? "`" + primaryRoot.getAbsolutePath() + "`"
                        : "materialize it first: `git clone <url> "
                                + primaryRoot.getAbsolutePath() + "`"));

        // base branch exists locally in the primary — the clone source
        // under the local-origin model (ike-issues#992).
        preflight.add(baseBranchPreflight(primaryRoot, base));

        List<String> unmaterialized = new ArrayList<>();
        for (String name : sorted) {
            Subproject sub = graph.manifest().subprojects().get(name);
            File comp = new File(primaryRoot, name);
            String versionLabel = skipVersion
                    ? "—"
                    : currentVersionLabel(comp, branchName);
            // Each member clones from the primary's local path — origin in
            // the sibling is the parent member (ike-issues#992). A member
            // the primary never materialized either skips (deliberately
            // unclonable: no repo URL) or fails the preflight below.
            if (!new File(comp, ".git").exists()) {
                String remote = sub.repo();
                if (remote == null || remote.isEmpty()) {
                    planRows.add(new String[]{name,
                            "skip (no repo URL, no local clone)", "—"});
                    continue;
                }
                unmaterialized.add(name);
            }
            planRows.add(new String[]{name, "clone + branch", versionLabel});
        }
        preflight.add(new Preflight(
                "Every member is materialized in the primary",
                unmaterialized.isEmpty(),
                unmaterialized.isEmpty()
                        ? "all clone sources present"
                        : "missing local clone(s): "
                                + String.join(", ", unmaterialized)
                                + " — run `mvn ws:scaffold-init` in the primary"));

        return new WorkspaceReportSpec(WsGoal.FEATURE_START_SIBLING_DRAFT,
                buildReport(siblingName, siblingRoot, branchName, base,
                        planRows, preflight));
    }

    /**
     * Bare-mode preview: a single-repo working set, base resolved + guarded
     * with {@code main} as the manifest base (there is no manifest).
     */
    private WorkspaceReportSpec previewBareMode(FeatureName featureName,
                                                String branchName)
            throws MojoException {
        WorkingSet workingSet = resolveWorkingSet();
        File repo = workingSet.members().getFirst().directory().toFile();
        String base = SiblingBaseResolution.resolveAndGuard(
                from, gitBranch(repo), "main");
        File parent = repo.getParentFile();
        String siblingName = featureName.siblingDirectoryName(workingSet.baseName());
        File siblingRoot = (parent != null)
                ? new File(parent, siblingName)
                : new File(siblingName);

        getLog().info("");
        getLog().info(header("Feature Start (sibling) — DRAFT"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName);
        getLog().info("  Base:    " + base);
        getLog().info("  Sibling: " + siblingRoot.getAbsolutePath());
        getLog().info("  Mode:    single repo (no workspace.yaml)");
        getLog().info("");

        List<String[]> planRows = new ArrayList<>();
        planRows.add(new String[]{workingSet.baseName() + " (aggregator)",
                "clone + branch",
                skipVersion ? "—" : currentVersionLabel(repo, branchName)});

        List<Preflight> preflight = new ArrayList<>();
        preflight.add(new Preflight(
                "Sibling dir does not already exist",
                !siblingRoot.exists(),
                siblingRoot.exists()
                        ? "`" + siblingRoot.getAbsolutePath() + "` exists — "
                                + "remove it: `rm -rf " + siblingRoot.getAbsolutePath() + "`"
                        : "`" + siblingRoot.getAbsolutePath() + "`"));
        boolean repoMaterialized = new File(repo, ".git").exists();
        preflight.add(new Preflight(
                "Repo is a git repository (the sibling chains to it)",
                repoMaterialized,
                repoMaterialized ? "`" + repo.getAbsolutePath() + "`"
                        : "not a git repository — nothing to fork"));
        preflight.add(baseBranchPreflight(repo, base));

        return new WorkspaceReportSpec(WsGoal.FEATURE_START_SIBLING_DRAFT,
                buildReport(siblingName, siblingRoot, branchName, base,
                        planRows, preflight));
    }

    /**
     * Preflight that the base branch exists <em>locally</em> in {@code dir}
     * — the clone source under the local-origin model
     * (IKE-Network/ike-issues#992); no remote is consulted.
     */
    private Preflight baseBranchPreflight(File dir, String base) {
        boolean exists;
        try {
            String out = ReleaseSupport.execCapture(dir, "git", "rev-parse",
                    "--verify", "--quiet", "refs/heads/" + base);
            exists = out != null && !out.isBlank();
        } catch (Exception e) {
            exists = false;
        }
        String detail = exists
                ? "local branch `" + base + "` resolves in the clone source"
                : "no local branch `" + base + "` there — check it out once,"
                        + " or pass `-Dfrom=<existing-branch>`";
        return new Preflight(
                "Base branch `" + base + "` exists in the clone source",
                exists, detail);
    }

    /**
     * Label a member's current POM version and the version it would be
     * qualified to on {@code branchName}, for the plan table.
     */
    private String currentVersionLabel(File dir, String branchName) {
        File pom = new File(dir, "pom.xml");
        if (!pom.exists()) {
            return "—";
        }
        try {
            String version = ReleaseSupport.readPomVersion(pom);
            if (version == null || version.isEmpty()) {
                return "—";
            }
            String qualified = network.ike.workspace.VersionSupport
                    .branchQualifiedVersion(version, branchName);
            return qualified.equals(version)
                    ? version
                    : version + " → " + qualified;
        } catch (MojoException e) {
            getLog().debug("Could not read POM version for " + dir + ": "
                    + e.getMessage());
            return "—";
        }
    }

    /**
     * Render the preview report: the plan, the preflight table, the
     * clone-cost note, and the next-step commands.
     */
    private String buildReport(String siblingName, File siblingRoot,
                               String branchName, String base,
                               List<String[]> planRows,
                               List<Preflight> preflight) {
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Preview — no clone is made.**")
                .paragraph("**Sibling:** `" + siblingName + "`")
                .paragraph("**Branch:** `" + branchName + "`")
                .paragraph("**Base:** `" + base + "`")
                .paragraph("**Location:** `" + siblingRoot.getAbsolutePath() + "`");

        report.section("Plan")
                .table(List.of("Member", "Action", "Version"), planRows);

        boolean allOk = true;
        List<String[]> checkRows = new ArrayList<>();
        for (Preflight p : preflight) {
            allOk = allOk && p.ok();
            checkRows.add(new String[]{
                    p.ok() ? "✓" : "✗", p.check(), p.detail()});
        }
        report.section("Preflight")
                .table(List.of("", "Check", "Detail"), checkRows);
        report.paragraph(allOk
                ? "All preflight checks pass — `ws:feature-start-sibling-publish"
                        + " -Dfeature=" + feature + "` should proceed."
                : "**Resolve the ✗ checks above, then run** "
                        + "`ws:feature-start-sibling-publish -Dfeature="
                        + feature + "`.");

        report.section("Clone cost")
                .paragraph("Each component is cloned from the primary's local "
                        + "member path, which becomes the sibling's `origin` — "
                        + "the sibling chains sibling → parent → GitHub "
                        + "(ike-issues#992). The clone is offline (no "
                        + "transport, no credentials), objects are hardlinked "
                        + "where the filesystem allows, so creation is fast "
                        + "and disk cost stays far below a full object DB "
                        + "until histories diverge. Deleting the sibling "
                        + "(`rm -rf`) never affects the primary.");

        report.section("Next")
                .paragraph("```bash\nmvn ws:feature-start-sibling-publish"
                        + " -Dfeature=" + feature
                        + (from != null && !from.isBlank()
                                ? " -Dfrom=" + from : "")
                        + "\n```");
        return report.build();
    }
}
