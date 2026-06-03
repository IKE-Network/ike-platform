package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the read-only refresh preview (#570): {@link
 * RefreshMainSupport#previewRefresh} fetches and classifies the
 * local-main / origin-main relationship, reporting "would fast-forward",
 * but never mutates local main — while {@link
 * RefreshMainSupport#refreshOrThrow} (the publish path) still does.
 */
class RefreshMainSupportPreviewTest {

    @TempDir
    Path tempDir;

    @Test
    void previewRefresh_behindMain_reportsFastForwardButDoesNotMutate()
            throws Exception {
        // origin: a bare repo. lib: a clone whose local main we leave one
        // commit behind origin/main.
        Path origin = tempDir.resolve("origin.git");
        Path sub = tempDir.resolve("lib");

        exec(tempDir, "git", "init", "--bare", "-b", "main", origin.toString());
        exec(tempDir, "git", "clone", origin.toString(), sub.toString());
        exec(sub, "git", "config", "user.email", "test@example.com");
        exec(sub, "git", "config", "user.name", "Test");

        // Base commit, pushed to origin.
        Files.writeString(sub.resolve("a.txt"), "1", StandardCharsets.UTF_8);
        exec(sub, "git", "add", ".");
        exec(sub, "git", "commit", "-m", "base");
        exec(sub, "git", "push", "origin", "main");

        // Advance origin/main by one commit…
        Files.writeString(sub.resolve("a.txt"), "2", StandardCharsets.UTF_8);
        exec(sub, "git", "commit", "-am", "advance");
        exec(sub, "git", "push", "origin", "main");

        // …then move local main back so it is exactly 1 behind origin/main.
        exec(sub, "git", "reset", "--hard", "HEAD~1");
        String before = execCapture(sub, "git", "rev-parse", "HEAD");

        // Preview: fetches + classifies, but must NOT fast-forward.
        List<RefreshMainSupport.Outcome> outcomes =
                RefreshMainSupport.previewRefresh(
                        tempDir.toFile(), List.of("lib"), "main", new TestLog());

        assertThat(execCapture(sub, "git", "rev-parse", "HEAD"))
                .as("preview must not move local main")
                .isEqualTo(before);

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0))
                .isInstanceOf(RefreshMainSupport.FastForwarded.class);
        var ff = (RefreshMainSupport.FastForwarded) outcomes.get(0);
        assertThat(ff.commits()).isEqualTo(1);
        assertThat(RefreshMainSupport.describe(ff, true))
                .contains("would fast-forward");

        // Contrast: the publish refresh DOES fast-forward local main.
        RefreshMainSupport.refreshOrThrow(
                tempDir.toFile(), List.of("lib"), "main", new TestLog());
        assertThat(execCapture(sub, "git", "rev-parse", "HEAD"))
                .as("publish refresh fast-forwards local main")
                .isNotEqualTo(before);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
        return output;
    }
}
