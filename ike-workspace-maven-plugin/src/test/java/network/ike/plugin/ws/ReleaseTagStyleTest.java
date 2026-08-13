package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@link ReleaseTagStyle} and the detection it drives —
 * the working set tags its members the way those members already tag
 * themselves (IKE-Network/ike-issues#1000). The repository fixtures
 * carry the same tag namespaces the real members do: bare version
 * tags beside checkpoint tags and dated legacy tags.
 */
class ReleaseTagStyleTest {

    @TempDir
    Path tempDir;

    @Test
    void v_prefixed_style_names_and_recognizes_ike_tags() {
        ReleaseTagStyle style = ReleaseTagStyle.V_PREFIXED;
        assertThat(style.tagFor("160")).isEqualTo("v160");
        assertThat(style.versionOf("v160")).isEqualTo("160");
        assertThat(style.isReleaseTag("v160")).isTrue();
        assertThat(style.isReleaseTag("v1.127.2")).isTrue();
        assertThat(style.isReleaseTag("1.127.2")).isFalse();
        assertThat(style.isReleaseTag("checkpoint/main-20260812")).isFalse();
    }

    @Test
    void bare_style_names_and_recognizes_ikmdev_tags() {
        ReleaseTagStyle style = ReleaseTagStyle.BARE;
        assertThat(style.tagFor("1.127.2")).isEqualTo("1.127.2");
        assertThat(style.versionOf("1.127.2")).isEqualTo("1.127.2");
        assertThat(style.isReleaseTag("1.127.2")).isTrue();
        assertThat(style.isReleaseTag("v1.127.2")).isFalse();
        // The neighbours that share a repository with real releases.
        assertThat(style.isReleaseTag("19-sep-2023-0542pm")).isFalse();
        assertThat(style.isReleaseTag("dev_tag_1.0")).isFalse();
        assertThat(style.isReleaseTag("checkpoint/main-20260812-145652"))
                .isFalse();
    }

    @Test
    void detection_picks_the_newest_release_past_legacy_lookalikes()
            throws Exception {
        File repo = repoWithTags("1.42.0", "1.43.0", "19-sep-2023-0542pm",
                "dev_tag_1.0", "checkpoint/main-20260812-145652");

        // Bare: 19-sep-... sorts ABOVE 1.43.0 under version:refname and
        // shares its glob, so only the pattern keeps detection honest.
        assertThat(WsReleaseDraftMojo.latestReleaseTag(repo,
                ReleaseTagStyle.BARE)).isEqualTo("1.43.0");

        // The same repository has no v-prefixed releases at all.
        assertThat(WsReleaseDraftMojo.latestReleaseTag(repo,
                ReleaseTagStyle.V_PREFIXED)).isNull();
    }

    @Test
    void detection_orders_by_version_not_by_string() throws Exception {
        File repo = repoWithTags("1.9.0", "1.43.0", "1.127.1");
        assertThat(WsReleaseDraftMojo.latestReleaseTag(repo,
                ReleaseTagStyle.BARE)).isEqualTo("1.127.1");
    }

    private File repoWithTags(String... tags) throws Exception {
        Path dir = Files.createDirectories(
                tempDir.resolve("repo-" + tags.length + "-" + tags[0]));
        Files.writeString(dir.resolve("file.txt"), "x",
                StandardCharsets.UTF_8);
        File repo = dir.toFile();
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec(repo, "git", "init", "-b", "main");
        exec(repo, "git", "config", "core.hooksPath", noHooks.toString());
        exec(repo, "git", "config", "commit.gpgsign", "false");
        exec(repo, "git", "config", "tag.gpgsign", "false");
        exec(repo, "git", "config", "user.email", "test@test");
        exec(repo, "git", "config", "user.name", "Test");
        exec(repo, "git", "add", ".");
        exec(repo, "git", "commit", "-m", "init");
        for (String tag : tags) {
            exec(repo, "git", "tag", tag);
        }
        return repo;
    }

    private void exec(File dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(dir)
                .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command)
                    + " failed:\n" + out);
        }
    }
}
