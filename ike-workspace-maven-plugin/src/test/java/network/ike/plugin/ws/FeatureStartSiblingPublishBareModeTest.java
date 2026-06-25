package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link FeatureStartSiblingPublishMojo} bare mode —
 * forking a single repo with no workspace.yaml (ike-issues#603, first slice
 * of #601; reshaped in #770).
 *
 * <p>Like {@link FeatureStartBareModeTest}, uses
 * {@code System.setProperty("user.dir", ...)} to simulate running Maven from
 * inside the repo. The repo is a clone of a bare upstream so the goal's
 * {@code git clone <origin>} has a real remote to fork from.
 */
class FeatureStartSiblingPublishBareModeTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;
    private Path repo;          // the CWD single repo
    private String upstreamUrl; // its origin

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");

        // Bare upstream with a committed pom on main.
        Path bare = tempDir.resolve(".upstreams/app.git");
        Files.createDirectories(bare);
        exec(bare, "git", "init", "--bare");

        Path work = tempDir.resolve(".upstreams/work");
        Files.createDirectories(work);
        Files.writeString(work.resolve("pom.xml"), pom("1.0.0-SNAPSHOT"),
                StandardCharsets.UTF_8);
        exec(work, "git", "init", "-b", "main");
        exec(work, "git", "add", ".");
        exec(work, "git", "commit", "-m", "init");
        exec(work, "git", "remote", "add", "origin",
                bare.toAbsolutePath().toString());
        exec(work, "git", "push", "-u", "origin", "main");
        upstreamUrl = bare.toUri().toString();

        // The CWD single repo = a clone of the upstream.
        exec(tempDir, "git", "clone", upstreamUrl, "app");
        repo = tempDir.resolve("app");

        System.setProperty("user.dir", repo.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void bareMode_forksSingleRepoOnFeatureBranch() throws Exception {
        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.feature = "solo";

        mojo.execute();

        Path sibling = tempDir.resolve("app-solo");

        // Sibling exists, is its own clone, on the feature branch.
        assertThat(sibling.resolve(".git")).isDirectory();
        assertThat(branch(sibling)).isEqualTo("feature/solo");

        // POM version branch-qualified.
        assertThat(Files.readString(sibling.resolve("pom.xml"), StandardCharsets.UTF_8))
                .contains("solo").contains("SNAPSHOT");

        // Sibling origin points at the real upstream, not the local repo.
        assertThat(execCapture(sibling, "git", "remote", "get-url", "origin"))
                .contains("app.git");

        // The primary repo is untouched — still on main, version unqualified.
        assertThat(branch(repo)).isEqualTo("main");
        assertThat(Files.readString(repo.resolve("pom.xml"), StandardCharsets.UTF_8))
                .doesNotContain("solo");
    }

    @Test
    void bareMode_skipVersion_branchesWithoutQualifying() throws Exception {
        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.feature = "docs";
        mojo.skipVersion = true;

        mojo.execute();

        Path sibling = tempDir.resolve("app-docs");
        assertThat(branch(sibling)).isEqualTo("feature/docs");
        assertThat(Files.readString(sibling.resolve("pom.xml"), StandardCharsets.UTF_8))
                .contains("1.0.0-SNAPSHOT").doesNotContain("docs");
    }

    @Test
    void bareMode_refusesWhenSiblingExists() throws Exception {
        Files.createDirectories(tempDir.resolve("app-dup"));
        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.feature = "dup";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void bareMode_refusesWhenNoOrigin() throws Exception {
        // A standalone repo with no origin remote — nothing to clone from.
        Path noRemote = tempDir.resolve("no-remote");
        Files.createDirectories(noRemote);
        Files.writeString(noRemote.resolve("pom.xml"), pom("9.9.9-SNAPSHOT"),
                StandardCharsets.UTF_8);
        exec(noRemote, "git", "init", "-b", "main");
        exec(noRemote, "git", "add", ".");
        exec(noRemote, "git", "commit", "-m", "init");

        String saved = System.getProperty("user.dir");
        System.setProperty("user.dir", noRemote.toAbsolutePath().toString());
        try {
            FeatureStartSiblingPublishMojo mojo =
                    TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
            mojo.feature = "x";
            assertThatThrownBy(mojo::execute)
                    .isInstanceOf(MojoException.class)
                    .hasMessageContaining("origin");
        } finally {
            System.setProperty("user.dir", saved);
        }
    }

    @Test
    void bareMode_onFeatureBranch_withoutFrom_refusesWithGuard() throws Exception {
        // Move the primary repo off main onto a feature branch — without
        // -Dfrom the guard must refuse (manifest base is main in bare mode).
        exec(repo, "git", "checkout", "-b", "feature/wip");

        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.feature = "solo";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("not the base branch")
                .hasMessageContaining("-Dfrom=feature/wip");

        assertThat(tempDir.resolve("app-solo")).doesNotExist();
    }

    @Test
    void bareMode_onFeatureBranch_withFrom_proceeds() throws Exception {
        // The base branch must exist upstream for `git clone -b` to resolve.
        exec(repo, "git", "checkout", "-b", "feature/wip");
        exec(repo, "git", "push", "-u", "origin", "feature/wip");

        FeatureStartSiblingPublishMojo mojo =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        mojo.feature = "solo";
        mojo.from = "feature/wip";

        mojo.execute();

        Path sibling = tempDir.resolve("app-solo");
        assertThat(sibling.resolve(".git")).isDirectory();
        assertThat(branch(sibling)).isEqualTo("feature/solo");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String pom(String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>app</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(version);
    }

    private String branch(Path dir) throws Exception {
        return execCapture(dir, "git", "rev-parse", "--abbrev-ref", "HEAD");
    }

    private void exec(Path workDir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
        return out;
    }
}
