package network.ike.plugin.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bare-repo sibling finishes by landing in its local parent
 * (IKE-Network/ike-issues#992), the way a workspace sibling does: the
 * parent's target branch fast-forwards to the squash and nothing is
 * pushed to the parent's own origin. Before this the bare-mode finish
 * pushed {@code main} at its origin, which is the parent checked out on
 * {@code main}, and git refused the push.
 *
 * <p>The fixture is {@link FeatureStartSiblingPublishBareModeTest}'s: a
 * bare upstream, a single repo cloned from it, and a sibling the start
 * goal cuts from that repo and chains to it as {@code origin}.
 */
class FeatureFinishBareSiblingLandingTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;
    private Path upstream;
    private Path parent;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");

        upstream = tempDir.resolve(".upstreams/app.git");
        Files.createDirectories(upstream);
        exec(upstream, "git", "init", "--bare", "-b", "main");
        Path work = tempDir.resolve(".upstreams/work");
        Files.createDirectories(work);
        Files.writeString(work.resolve("pom.xml"), pom("1.0.0-SNAPSHOT"),
                StandardCharsets.UTF_8);
        exec(work, "git", "init", "-b", "main");
        exec(work, "git", "config", "user.email", "test@example.com");
        exec(work, "git", "config", "user.name", "Test");
        exec(work, "git", "add", ".");
        exec(work, "git", "commit", "-m", "init");
        exec(work, "git", "remote", "add", "origin",
                upstream.toAbsolutePath().toString());
        exec(work, "git", "push", "-u", "origin", "main");

        exec(tempDir, "git", "clone", upstream.toUri().toString(), "app");
        parent = tempDir.resolve("app");
        exec(parent, "git", "config", "user.email", "test@example.com");
        exec(parent, "git", "config", "user.name", "Test");
        System.setProperty("user.dir", parent.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void publish_bareSibling_fastForwardsParentAndPushesNothing() throws Exception {
        FeatureStartSiblingPublishMojo start =
                TestLog.createMojo(FeatureStartSiblingPublishMojo.class);
        start.feature = "solo";
        start.execute();

        Path sibling = tempDir.resolve("app꞉solo");
        exec(sibling, "git", "config", "user.email", "test@example.com");
        exec(sibling, "git", "config", "user.name", "Test");
        Files.writeString(sibling.resolve("feature-work.txt"),
                "actual feature content", StandardCharsets.UTF_8);
        exec(sibling, "git", "add", "feature-work.txt");
        exec(sibling, "git", "commit", "-m", "feature: add feature work");
        System.setProperty("user.dir", sibling.toAbsolutePath().toString());

        FeatureFinishSquashPublishMojo finish =
                TestLog.createMojo(FeatureFinishSquashPublishMojo.class);
        finish.feature = "solo";
        finish.targetBranch = "main";
        finish.message = "Squash: land solo";
        finish.push = true;   // Maven's default; the harness applies none
        finish.execute();

        // The sibling finished onto its own main ...
        assertThat(execCapture(sibling, "git", "rev-parse", "--abbrev-ref", "HEAD"))
                .isEqualTo("main");
        String siblingMain = execCapture(sibling, "git", "rev-parse", "main");
        // ... and the parent's main fast-forwarded to the very same commit.
        assertThat(execCapture(parent, "git", "rev-parse", "main"))
                .isEqualTo(siblingMain);
        assertThat(execCapture(parent, "git", "log", "--oneline", "-2"))
                .contains("Squash: land solo");
        assertThat(parent.resolve("feature-work.txt")).exists();
        // Nothing went past the parent: its origin still holds only "init".
        assertThat(execCapture(upstream, "git", "log", "--oneline", "main"))
                .doesNotContain("Squash: land solo");
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
