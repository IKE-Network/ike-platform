package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Open one review pull request per subproject carrying a feature branch,
 * cross-linked so a reviewer can see the whole change set.
 *
 * <p>A workspace feature routinely spans several repositories, and GitHub has
 * no notion of a pull request that covers more than one. Reviewing such a
 * change means finding the sibling PRs by hand — and a reviewer who misses one
 * approves an incomplete picture. This goal opens the PRs as a set and writes
 * the sibling links into each body, so any one of them leads to all the
 * others.
 *
 * <p>It sits <em>beside</em> {@code ws:feature-finish-squash-*} rather than
 * replacing it: that goal still performs the local squash-merge when review is
 * done. Nothing about the existing finish flow changes.
 *
 * <p><strong>Per subproject on {@code feature/<name>}:</strong>
 * <ol>
 *   <li>Skip subprojects on another branch — the feature branch defines the
 *       set, exactly as {@code ws:feature-finish-*} determines it</li>
 *   <li>Push the branch when the remote has no copy yet (a PR cannot be
 *       opened for a branch GitHub has never seen)</li>
 *   <li>Reuse an existing open PR for the same head branch rather than
 *       failing or opening a duplicate — this goal is safe to re-run</li>
 *   <li>Otherwise {@code gh pr create}</li>
 * </ol>
 *
 * <p>Then a second pass rewrites each body with the collected sibling URLs.
 * The two passes are unavoidable: a PR's URL does not exist until it is
 * created, so the cross-links cannot be part of the initial body.
 *
 * <p>Requires the {@code gh} CLI, authenticated — the same dependency
 * {@code ws:release-publish} already has for GitHub Releases.
 *
 * <pre>{@code
 * mvn ws:feature-pr-draft   -Dfeature=grpc_plugin
 * mvn ws:feature-pr-publish -Dfeature=grpc_plugin
 * mvn ws:feature-pr-publish -Dfeature=grpc_plugin -Dreviewer=alice,bob
 * mvn ws:feature-pr-publish -Dfeature=grpc_plugin -Dtitle="gRPC datastore provider"
 * }</pre>
 *
 * @see FeatureTrackDraftMojo for adopting the branch in the first place
 * @see FeatureFinishSquashDraftMojo for the merge that follows review
 */
@Mojo(name = "feature-pr-draft", projectRequired = false, aggregator = true)
public class FeaturePrDraftMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public FeaturePrDraftMojo() {}

    /** Feature name without the {@code feature/} prefix. */
    @Parameter(property = "feature")
    String feature;

    /**
     * Restrict to these subprojects (comma-separated). Omitted, every
     * subproject currently on the feature branch is included.
     */
    @Parameter(property = "affected")
    String affected;

    /** Base branch the pull requests target. */
    @Parameter(property = "base", defaultValue = "main")
    String base;

    /**
     * Pull request title. Defaults to the feature name, which keeps the set
     * visually identifiable in a review queue.
     */
    @Parameter(property = "title")
    String title;

    /**
     * Body prepended to every pull request, before the generated
     * cross-links.
     */
    @Parameter(property = "body")
    String body;

    /** Comma-separated GitHub logins to request review from. */
    @Parameter(property = "reviewer")
    String reviewer;

    /** Remote to push to and open pull requests against. */
    @Parameter(property = "remote", defaultValue = "origin")
    String remote;

    /** Open the pull requests. Default is draft (preview only). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        if (!isWorkspaceMode()) {
            throw new MojoException(
                    "ws:feature-pr requires a workspace (workspace.yaml). "
                    + "For a single repository use 'gh pr create'.");
        }

        boolean draft = !publish;
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        feature = requireParam(feature, "feature", "Feature name");
        String branch = FeatureScope.branchFor(
                validateFeatureName(feature).value());
        String prTitle = (title == null || title.isBlank()) ? feature : title;

        Set<String> known = graph.manifest().subprojects().keySet();
        Set<String> scope = FeatureScope.resolveAffected(affected, known);
        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(scope));

        getLog().info("");
        getLog().info(header("Feature PR"));
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branch + " → " + base);
        getLog().info("  Title:   " + prTitle);
        if (draft) getLog().info("  Mode:    DRAFT");
        getLog().info("");

        Map<String, String> effects = new LinkedHashMap<>();
        // Ordered so the cross-link list reads in dependency order.
        Map<String, String> prUrls = new LinkedHashMap<>();
        List<String> members = new ArrayList<>();
        List<String> created = new ArrayList<>();
        List<String> reused = new ArrayList<>();
        List<String> needPush = new ArrayList<>();
        int skipped = 0;

        for (String name : sorted) {
            File dir = new File(root, name);
            if (!new File(dir, ".git").exists()) {
                effects.put(name, "skipped (not cloned)");
                skipped++;
                continue;
            }
            if (!branch.equals(gitBranch(dir))) {
                getLog().info("  · " + name + " — on " + gitBranch(dir)
                        + ", not " + branch + ", skipping");
                effects.put(name, "skipped (not on `" + branch + "`)");
                skipped++;
                continue;
            }

            Optional<String> slug = FeatureScope.githubSlug(dir, remote);
            if (slug.isEmpty()) {
                getLog().warn("  " + Ansi.yellow("⚠ ") + name
                        + " — no GitHub " + remote + " remote, skipping");
                effects.put(name, "skipped (no GitHub `" + remote
                        + "` remote)");
                skipped++;
                continue;
            }
            members.add(name);

            boolean onRemote =
                    VcsOperations.remoteTrackingExists(dir, remote, branch);
            if (!onRemote) needPush.add(name);

            Optional<String> existing = draft
                    ? Optional.empty()
                    : findOpenPr(dir, slug.get(), branch);
            if (existing.isPresent()) {
                getLog().info("  " + Ansi.green("✓ ") + name
                        + " — PR already open: " + existing.get());
                effects.put(name, "reused existing PR " + existing.get());
                prUrls.put(name, existing.get());
                reused.add(name);
                continue;
            }

            if (draft) {
                getLog().info("  [draft] " + name + " — would open PR on "
                        + slug.get() + " (" + branch + " → " + base + ")"
                        + (onRemote ? "" : " after pushing the branch"));
                effects.put(name, "would open PR on `" + slug.get() + "`"
                        + (onRemote ? "" : " — push required first"));
                created.add(name);
                continue;
            }

            // A PR cannot reference a branch the remote has never seen.
            if (!onRemote) {
                getLog().info("  " + Ansi.cyan("→ ") + name
                        + ": pushing " + branch + " to " + remote);
                VcsOperations.pushWithUpstream(dir, getLog(), remote, branch);
            }

            String url = createPr(dir, slug.get(), branch, prTitle,
                    baseBody(name, members.size()));
            getLog().info("  " + Ansi.cyan("→ ") + name + ": opened " + url);
            effects.put(name, "opened PR " + url);
            prUrls.put(name, url);
            created.add(name);
        }

        // ── Second pass: cross-link ──
        // A PR's URL does not exist until it is created, so the sibling
        // links cannot be written in the first pass.
        int linked = 0;
        if (!draft && prUrls.size() > 1) {
            for (Map.Entry<String, String> e : prUrls.entrySet()) {
                File dir = new File(root, e.getKey());
                Optional<String> slug = FeatureScope.githubSlug(dir, remote);
                if (slug.isEmpty()) continue;
                try {
                    ReleaseSupport.exec(dir, getLog(), "gh", "pr", "edit",
                            e.getValue(), "--repo", slug.get(),
                            "--body", crossLinkedBody(e.getKey(), prUrls));
                    linked++;
                } catch (MojoException ex) {
                    // A failed cross-link must not invalidate PRs that were
                    // opened successfully — the review can proceed without
                    // the convenience links.
                    getLog().warn("  Could not cross-link " + e.getValue()
                            + ": " + ex.getMessage());
                }
            }
        }

        if (!draft && !prUrls.isEmpty() && reviewer != null
                && !reviewer.isBlank()) {
            requestReviews(root, prUrls);
        }

        getLog().info("");
        getLog().info("  " + (draft
                ? created.size() + " PR(s) to open, " + reused.size()
                        + " already open, " + skipped + " skipped"
                : "Opened: " + created.size() + " | Reused: " + reused.size()
                        + " | Cross-linked: " + linked + " | Skipped: "
                        + skipped));
        getLog().info("");

        // ── Report ──
        GoalReportBuilder report = new GoalReportBuilder();
        report.paragraph("**Feature:** `" + feature + "` **Branch:** `" + branch
                + "` → `" + base + "`"
                + (draft ? "  _(draft — no pull requests opened)_" : ""));

        report.section("Working set");
        for (Map.Entry<String, String> e : effects.entrySet()) {
            report.bullet("`" + e.getKey() + "` — " + e.getValue());
        }

        if (!prUrls.isEmpty()) {
            report.section("Pull requests");
            for (Map.Entry<String, String> e : prUrls.entrySet()) {
                report.bullet("`" + e.getKey() + "` — " + e.getValue());
            }
        }

        if (draft && !needPush.isEmpty()) {
            report.section("Push required");
            report.paragraph("`" + branch + "` is not on `" + remote
                    + "` for these subprojects; the publish run pushes it "
                    + "first (or run `ws:push -Dfeature=" + feature
                    + "` yourself):");
            for (String n : needPush) report.bullet("`" + n + "`");
        }

        if (members.isEmpty()) {
            report.section("Nothing to do");
            report.paragraph("No subproject is on `" + branch + "`. Adopt the "
                    + "branch with `ws:feature-track-publish -Dfeature="
                    + feature + " -Daffected=...` first.");
        } else {
            report.section("Next");
            report.paragraph("After review approves and the pull requests "
                    + "merge, bring `" + base + "` down with `ws:refresh-main`. "
                    + "`ws:feature-finish-squash-*` is unchanged and still "
                    + "available for a local squash-merge instead.");
        }

        return new WorkspaceReportSpec(
                publish ? WsGoal.FEATURE_PR_PUBLISH : WsGoal.FEATURE_PR_DRAFT,
                report.build());
    }

    /**
     * The body a pull request gets on creation, before cross-links exist.
     *
     * @param name    the subproject
     * @param ordinal its 1-based position in the set
     * @return the initial body
     */
    private String baseBody(String name, int ordinal) {
        StringBuilder b = new StringBuilder();
        if (body != null && !body.isBlank()) {
            b.append(body).append("\n\n");
        }
        b.append("Part of the `").append(feature)
                .append("` workspace feature (`").append(name)
                .append("`, component ").append(ordinal).append(").\n\n")
                .append("Opened by `ws:feature-pr-publish`. Sibling pull "
                        + "requests are linked in a follow-up edit once every "
                        + "component's pull request exists.\n");
        return b.toString();
    }

    /**
     * The final body: the base body plus a link to every sibling pull
     * request, with the current one marked so a reviewer can see where they
     * are in the set.
     *
     * @param self   the subproject this body belongs to
     * @param prUrls every pull request in the set, in dependency order
     * @return the rewritten body
     */
    private String crossLinkedBody(String self, Map<String, String> prUrls) {
        StringBuilder b = new StringBuilder();
        if (body != null && !body.isBlank()) {
            b.append(body).append("\n\n");
        }
        b.append("Part of the `").append(feature)
                .append("` workspace feature, spanning ")
                .append(prUrls.size()).append(" repositories. ")
                .append("All of them must merge for the feature to be "
                        + "complete.\n\n")
                .append("| Component | Pull request |\n")
                .append("|---|---|\n");
        for (Map.Entry<String, String> e : prUrls.entrySet()) {
            boolean isSelf = e.getKey().equals(self);
            b.append("| `").append(e.getKey()).append('`')
                    .append(isSelf ? " ← this PR" : "")
                    .append(" | ").append(e.getValue()).append(" |\n");
        }
        b.append("\nOpened by `ws:feature-pr-publish`.\n");
        return b.toString();
    }

    /**
     * The URL of an already-open pull request for {@code branch}, if any.
     *
     * <p>Makes the goal re-runnable: without this check a second run would
     * either fail or open duplicate pull requests for the same head.
     *
     * @param dir    the subproject directory
     * @param slug   the {@code owner/repo}
     * @param branch the head branch
     * @return the URL, or empty when no open pull request exists
     */
    private Optional<String> findOpenPr(File dir, String slug, String branch) {
        try {
            String out = ReleaseSupport.execCapture(dir,
                    "gh", "pr", "list", "--repo", slug, "--head", branch,
                    "--state", "open", "--json", "url",
                    "--jq", ".[0].url // empty");
            String url = out == null ? "" : out.trim();
            return url.isEmpty() ? Optional.empty() : Optional.of(url);
        } catch (MojoException e) {
            // Treat a failed lookup as "no existing PR" rather than
            // aborting: gh may be unauthenticated for this repo, and the
            // create call below will surface a clearer error.
            getLog().debug("  gh pr list failed for " + slug + ": "
                    + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Open a pull request and return its URL.
     *
     * @param dir      the subproject directory
     * @param slug     the {@code owner/repo}
     * @param branch   the head branch
     * @param prTitle  the title
     * @param prBody   the initial body
     * @return the new pull request's URL
     * @throws MojoException when {@code gh pr create} fails
     */
    private String createPr(File dir, String slug, String branch,
                            String prTitle, String prBody)
            throws MojoException {
        String out = ReleaseSupport.execCapture(dir,
                "gh", "pr", "create",
                "--repo", slug,
                "--head", branch,
                "--base", base,
                "--title", prTitle,
                "--body", prBody);
        // gh prints the PR URL on stdout; keep the last URL-looking line so
        // a leading informational line can't be mistaken for the URL.
        String url = "";
        for (String line : (out == null ? "" : out).split("\\R")) {
            String t = line.trim();
            if (t.startsWith("http")) url = t;
        }
        if (url.isEmpty()) {
            throw new MojoException(
                    "gh pr create succeeded for " + slug + " but printed no "
                    + "pull request URL; output was: " + out);
        }
        return url;
    }

    /**
     * Request review on every opened pull request, best-effort.
     *
     * <p>A reviewer who has no access to one repository in the set must not
     * fail the whole goal — the pull requests are already open and are the
     * deliverable.
     */
    private void requestReviews(File root, Map<String, String> prUrls) {
        for (Map.Entry<String, String> e : prUrls.entrySet()) {
            File dir = new File(root, e.getKey());
            Optional<String> slug = FeatureScope.githubSlug(dir, remote);
            if (slug.isEmpty()) continue;
            try {
                ReleaseSupport.exec(dir, getLog(), "gh", "pr", "edit",
                        e.getValue(), "--repo", slug.get(),
                        "--add-reviewer", reviewer);
            } catch (MojoException ex) {
                getLog().warn("  Could not add reviewer(s) '" + reviewer
                        + "' to " + e.getValue() + ": " + ex.getMessage());
            }
        }
    }
}
