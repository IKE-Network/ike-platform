package network.ike.plugin.ws;

import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression tests for {@link FeatureStartSupport#commitIfStaged}.
 *
 * <p>A feature-start cascade step stages a file then commits; in a BOM-managed
 * workspace a step can legitimately stage nothing, and an unguarded
 * {@code git commit} then fails with "nothing to commit" (exit 1), aborting the
 * whole goal before the aggregator is qualified (ike-issues#820). The guard must
 * skip an empty commit instead of failing, while still committing real staged
 * changes.
 */
class FeatureStartSupportCommitGuardTest {

    @TempDir
    Path repo;

    private final Log log = new TestLog();
    private final FeatureStartSupport support = new FeatureStartSupport(log);

    @BeforeEach
    void initRepo() throws Exception {
        exec("git", "init", "-b", "main");
        exec("git", "config", "user.email", "test@ike.test");
        exec("git", "config", "user.name", "Test");
        exec("git", "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("pom.xml"),
                "<project><version>1-SNAPSHOT</version></project>",
                StandardCharsets.UTF_8);
        exec("git", "add", ".");
        exec("git", "commit", "-m", "init");
    }

    @Test
    void skipsCommitWhenNothingStaged() throws Exception {
        int before = commitCount();
        // Nothing staged — the guard must skip, not throw, and add no commit.
        assertThatCode(() -> support.commitIfStaged(repo.toFile(), "noop"))
                .doesNotThrowAnyException();
        assertThat(commitCount()).isEqualTo(before);
    }

    @Test
    void commitsWhenSomethingIsStaged() throws Exception {
        int before = commitCount();
        Files.writeString(repo.resolve("pom.xml"),
                "<project><version>1-feature-SNAPSHOT</version></project>",
                StandardCharsets.UTF_8);
        exec("git", "add", "pom.xml");
        support.commitIfStaged(repo.toFile(), "feature: bump");
        assertThat(commitCount()).isEqualTo(before + 1);
        assertThat(execCapture("git", "log", "-1", "--pretty=%s"))
                .isEqualTo("feature: bump");
    }

    private int commitCount() throws Exception {
        return Integer.parseInt(execCapture("git", "rev-list", "--count", "HEAD"));
    }

    private void exec(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
    }

    private String execCapture(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(repo.toFile())
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
