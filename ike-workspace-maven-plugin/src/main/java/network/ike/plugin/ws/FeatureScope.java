package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared scope resolution for the feature-branch goals that operate on a
 * named subset of the workspace — {@link FeatureTrackDraftMojo},
 * {@link FeaturePrDraftMojo}, and {@code ws:push}'s {@code -Dfeature}
 * filter.
 *
 * <p>Extracted rather than duplicated because these goals form a pipeline
 * (track → push → PR) that must agree exactly on which subprojects are in
 * scope; a divergence would open PRs for a different set than was pushed.
 * {@link FeatureStartDraftMojo} keeps its own private copy of the
 * {@code --affected} parsing, which predates this class.
 */
final class FeatureScope {

    private FeatureScope() {}

    /**
     * Resolve a comma-separated {@code --affected} list into a concrete
     * subset of subproject names, preserving the order given.
     *
     * <p>Returns {@code known} unchanged when unset. Blank tokens are
     * discarded so a trailing comma doesn't trip the goal. An unknown name
     * fails the goal naming the offender — silently dropping it would
     * produce a narrower operation than the caller asked for, which is
     * worse than stopping.
     *
     * @param affected the raw parameter value, may be {@code null}
     * @param known    every subproject declared in {@code workspace.yaml}
     * @return the resolved subset, never empty
     * @throws MojoException on an unknown name, or when the value resolves
     *                       to nothing
     */
    static Set<String> resolveAffected(String affected, Set<String> known) {
        if (affected == null || affected.isBlank()) {
            return known;
        }
        Set<String> selected = new LinkedHashSet<>();
        for (String raw : affected.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!known.contains(name)) {
                throw new MojoException(
                        "Unknown subproject in --affected: '" + name
                        + "'. Known subprojects: " + known);
            }
            selected.add(name);
        }
        if (selected.isEmpty()) {
            throw new MojoException(
                    "--affected was set but resolved to an empty subset; "
                    + "supply at least one subproject name or omit the "
                    + "parameter.");
        }
        return selected;
    }

    /**
     * The {@code feature/}-prefixed branch name for a bare feature name.
     *
     * @param feature the name without prefix
     * @return the full branch name
     */
    static String branchFor(String feature) {
        return "feature/" + feature;
    }

    /**
     * Derive an {@code owner/repo} slug from a repository's remote URL,
     * handling both SSH ({@code git@github.com:owner/repo.git}) and HTTPS
     * ({@code https://github.com/owner/repo.git}) forms.
     *
     * <p>Needed because {@code gh} is invoked with an explicit
     * {@code --repo}: relying on {@code gh}'s own cwd detection would make
     * the goal's behavior depend on which subproject directory the process
     * happened to be in.
     *
     * @param dir    the repository root directory
     * @param remote the remote name
     * @return the slug, or empty when the remote is absent or not GitHub
     */
    static Optional<String> githubSlug(File dir, String remote) {
        return VcsOperations.remoteUrl(dir, remote)
                .flatMap(url -> githubSlug(url.trim()));
    }

    /**
     * Derive an {@code owner/repo} slug from a remote URL.
     *
     * <p>Recognizes URL remotes
     * ({@code scheme://[user@]host/owner/repo(.git)}) and scp-style remotes
     * ({@code [user@]host:owner/repo(.git)}), including the
     * {@code github.com-<account>} SSH host aliases multi-account setups use
     * (#916).
     *
     * <p>The host is matched <em>exactly</em> (or as a {@code github.com-}
     * alias) rather than by substring: a URL whose host merely contains
     * {@code github.com} — {@code notgithub.com.example/owner/repo} — is not
     * GitHub, and accepting it would hand a bogus value to
     * {@code gh --repo}, addressing a repository nobody asked for.
     *
     * <p>This is the canonical parser;
     * {@link WsScaffoldInitMojo#parseGitHubOrg} delegates to it for the org
     * segment alone.
     *
     * @param url the trimmed remote URL
     * @return the {@code owner/repo} slug, or empty when the URL is not a
     *         recognized GitHub form
     */
    static Optional<String> githubSlug(String url) {
        if (url == null || url.isEmpty()) {
            return Optional.empty();
        }
        String authority;
        String path;
        int schemeIdx = url.indexOf("://");
        if (schemeIdx >= 0) {
            String rest = url.substring(schemeIdx + 3);
            int slash = rest.indexOf('/');
            if (slash < 0) {
                return Optional.empty();
            }
            authority = rest.substring(0, slash);
            path = rest.substring(slash + 1);
        } else {
            int colon = url.indexOf(':');
            if (colon < 0) {
                return Optional.empty();
            }
            authority = url.substring(0, colon);
            path = url.substring(colon + 1);
        }
        int at = authority.indexOf('@');
        String host = at >= 0 ? authority.substring(at + 1) : authority;
        if (!host.equals("github.com") && !host.startsWith("github.com-")) {
            return Optional.empty();
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        path = path.replaceAll("/+$", "");
        // Exactly owner/repo. A deeper or shorter path must not be
        // truncated into a plausible-looking slug.
        List<String> parts = List.of(path.split("/"));
        if (parts.size() != 2 || parts.get(0).isBlank()
                || parts.get(1).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parts.get(0) + "/" + parts.get(1));
    }
}
