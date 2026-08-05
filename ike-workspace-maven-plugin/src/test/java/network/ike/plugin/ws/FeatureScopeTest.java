package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FeatureScope}, the shared scope resolution behind
 * {@code ws:feature-track-*}, {@code ws:feature-pr-*}, and {@code ws:push}'s
 * {@code -Dfeature} filter.
 *
 * <p>These three goals form a pipeline (track → push → PR) and must agree
 * exactly on which subprojects are in scope: a divergence would open pull
 * requests for a different set than was pushed. The slug derivation gets the
 * same scrutiny because it becomes {@code gh --repo}, and a wrong value
 * targets somebody else's repository.
 */
class FeatureScopeTest {

    @TempDir
    Path tempDir;

    private static Set<String> known(String... names) {
        return new LinkedHashSet<>(Set.of(names));
    }

    // ── resolveAffected ──────────────────────────────────────────────

    @Test
    void unsetAffected_returnsEveryKnownSubproject() {
        Set<String> all = known("lib-a", "lib-b");

        assertThat(FeatureScope.resolveAffected(null, all)).isEqualTo(all);
        assertThat(FeatureScope.resolveAffected("   ", all)).isEqualTo(all);
    }

    @Test
    void affected_selectsNamedSubprojectsOnly() {
        Set<String> resolved = FeatureScope.resolveAffected(
                "komet,tinkar-service",
                known("komet", "tinkar-service", "tinkar-core"));

        assertThat(resolved).containsExactly("komet", "tinkar-service");
    }

    @Test
    void affected_preservesGivenOrder() {
        // Order matters: it drives the cross-link table's row order in the
        // pull request bodies.
        Set<String> resolved = FeatureScope.resolveAffected(
                "tinkar-service,komet", known("komet", "tinkar-service"));

        assertThat(resolved).containsExactly("tinkar-service", "komet");
    }

    @Test
    void affected_trimsWhitespaceAndTolleratesTrailingComma() {
        Set<String> resolved = FeatureScope.resolveAffected(
                " komet , tinkar-service ,", known("komet", "tinkar-service"));

        assertThat(resolved).containsExactly("komet", "tinkar-service");
    }

    @Test
    void affected_unknownNameFailsNamingTheOffender() {
        // Silently dropping it would run a narrower operation than asked
        // for — worse than stopping.
        assertThatThrownBy(() -> FeatureScope.resolveAffected(
                "komet,typo-here", known("komet")))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("typo-here")
                .hasMessageContaining("Known subprojects");
    }

    @Test
    void affected_allBlankTokensFailsRatherThanSilentlyWideningScope() {
        assertThatThrownBy(() -> FeatureScope.resolveAffected(
                " , , ", known("komet")))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("empty subset");
    }

    // ── branchFor ────────────────────────────────────────────────────

    @Test
    void branchFor_prefixesFeature() {
        assertThat(FeatureScope.branchFor("grpc_plugin"))
                .isEqualTo("feature/grpc_plugin");
    }

    // ── githubSlug ───────────────────────────────────────────────────

    @Test
    void githubSlug_parsesSshRemote() throws Exception {
        Path repo = repoWithRemote("git@github.com:icaglobal/tinkar-service.git");

        assertThat(FeatureScope.githubSlug(repo.toFile(), "origin"))
                .contains("icaglobal/tinkar-service");
    }

    @Test
    void githubSlug_parsesHttpsRemote() throws Exception {
        Path repo = repoWithRemote(
                "https://github.com/knowledge-graphlet/komet-claude-plugin.git");

        assertThat(FeatureScope.githubSlug(repo.toFile(), "origin"))
                .contains("knowledge-graphlet/komet-claude-plugin");
    }

    @Test
    void githubSlug_parsesHttpsRemoteWithoutDotGitAndTrailingSlash() throws Exception {
        Path repo = repoWithRemote("https://github.com/ikmdev/komet/");

        assertThat(FeatureScope.githubSlug(repo.toFile(), "origin"))
                .contains("ikmdev/komet");
    }

    @Test
    void githubSlug_parsesSshHostAlias() throws Exception {
        // Multi-account setups use github.com-<account> aliases (#916).
        Path repo = repoWithRemote("git@github.com-work:icaglobal/tinkar-service.git");

        assertThat(FeatureScope.githubSlug(repo.toFile(), "origin"))
                .contains("icaglobal/tinkar-service");
    }

    @Test
    void githubSlug_emptyWhenHostMerelyContainsGitHub() {
        // The host is matched exactly, not by substring: accepting this
        // would hand a bogus --repo to gh and address an unrelated
        // repository.
        assertThat(FeatureScope.githubSlug(
                "https://notgithub.com.example/owner/repo.git")).isEmpty();
        assertThat(FeatureScope.githubSlug(
                "git@evil-github.com.example:owner/repo.git")).isEmpty();
    }

    @Test
    void githubSlug_emptyForDegenerateInput() {
        assertThat(FeatureScope.githubSlug("")).isEmpty();
        assertThat(FeatureScope.githubSlug((String) null)).isEmpty();
        assertThat(FeatureScope.githubSlug("not a url")).isEmpty();
    }

    @Test
    void githubSlug_emptyForNonGitHubRemote() throws Exception {
        Path repo = repoWithRemote("https://gitlab.com/owner/repo.git");

        assertThat(FeatureScope.githubSlug(repo.toFile(), "origin")).isEmpty();
    }

    @Test
    void githubSlug_emptyWhenRemoteMissing() throws Exception {
        Path repo = tempDir.resolve("no-remote");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");

        assertThat(FeatureScope.githubSlug(repo.toFile(), "origin")).isEmpty();
    }

    @Test
    void githubSlug_emptyWhenPathIsNotExactlyOwnerRepo() throws Exception {
        // A deeper path must not be truncated into a plausible-looking slug —
        // that value would become `gh --repo` and could hit a real
        // repository other than the intended one.
        Path repo = repoWithRemote("https://github.com/owner/group/repo.git");

        Optional<String> slug =
                FeatureScope.githubSlug(repo.toFile(), "origin");

        assertThat(slug).isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private Path repoWithRemote(String url) throws Exception {
        Path repo = tempDir.resolve("repo-" + Math.abs(url.hashCode()));
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");
        git(repo, "remote", "add", "origin", url);
        return repo;
    }

    private static void git(Path workDir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed (" + exit + "): "
                    + output);
        }
    }
}
