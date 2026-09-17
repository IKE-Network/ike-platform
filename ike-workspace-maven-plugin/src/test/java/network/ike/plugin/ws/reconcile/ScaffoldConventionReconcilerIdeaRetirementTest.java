package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.TestLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The IDE-derived files leave the curated {@code .idea/} slice
 * (IKE-Network/ike-issues#1102): their allowlist lines go, and a root that
 * still tracks them untracks them on publish while keeping the working
 * copies the IDE rewrites on every re-import.
 */
class ScaffoldConventionReconcilerIdeaRetirementTest {

    private static final String OLD_SLICE = """
            *
            !.gitignore
            !pom.xml
            !workspace.yaml
            !.idea/
            !.idea/.gitignore
            !.idea/kotlinc.xml
            !.idea/encodings.xml
            !.idea/jarRepositories.xml
            """;

    @TempDir
    Path tempDir;

    private final TestLog log = new TestLog();

    @Test
    void retiredAllowlistLinesAreDroppedAndTheRestKept() {
        ScaffoldConventionReconciler.IdeaRetirement retirement =
                ScaffoldConventionReconciler.retireIdeaDerivedFiles(tempDir, OLD_SLICE, false, log);

        assertThat(retirement.retiredLines())
                .containsExactly("!.idea/encodings.xml", "!.idea/jarRepositories.xml");
        assertThat(retirement.content())
                .contains("!.idea/\n").contains("!.idea/.gitignore\n").contains("!.idea/kotlinc.xml\n")
                .doesNotContain("encodings.xml").doesNotContain("jarRepositories.xml");
        assertThat(retirement.untracked()).as("no git repo, nothing to untrack").isEmpty();
        assertThat(retirement.changed()).isTrue();
    }

    @Test
    void aCurrentSliceIsLeftAlone() {
        String current = OLD_SLICE
                .replace("!.idea/encodings.xml\n", "")
                .replace("!.idea/jarRepositories.xml\n", "");

        ScaffoldConventionReconciler.IdeaRetirement retirement =
                ScaffoldConventionReconciler.retireIdeaDerivedFiles(tempDir, current, true, log);

        assertThat(retirement.content()).isEqualTo(current);
        assertThat(retirement.changed()).isFalse();
    }

    @Test
    void previewReportsTrackedFilesWithoutTouchingTheIndex() throws Exception {
        gitRootTracking(".idea/encodings.xml");

        ScaffoldConventionReconciler.IdeaRetirement retirement =
                ScaffoldConventionReconciler.retireIdeaDerivedFiles(tempDir, OLD_SLICE, false, log);

        assertThat(retirement.untracked()).containsExactly(".idea/encodings.xml");
        assertThat(exec("git", "ls-files", "--", ".idea/encodings.xml"))
                .as("a preview leaves the index alone").isEqualTo(".idea/encodings.xml");
    }

    @Test
    void publishUntracksTheFileAndKeepsTheWorkingCopy() throws Exception {
        gitRootTracking(".idea/encodings.xml");

        ScaffoldConventionReconciler.IdeaRetirement retirement =
                ScaffoldConventionReconciler.retireIdeaDerivedFiles(tempDir, OLD_SLICE, true, log);

        assertThat(retirement.untracked()).containsExactly(".idea/encodings.xml");
        assertThat(exec("git", "ls-files", "--", ".idea/encodings.xml"))
                .as("the file is no longer tracked").isEmpty();
        assertThat(exec("git", "diff", "--cached", "--name-status"))
                .as("the removal is staged for the goal's bookkeeping commit")
                .contains("D\t.idea/encodings.xml");
        assertThat(tempDir.resolve(".idea/encodings.xml"))
                .as("the IDE's working copy stays").exists();
        assertThat(retirement.content()).doesNotContain("encodings.xml");

        // Idempotent: a second pass finds nothing left to retire.
        ScaffoldConventionReconciler.IdeaRetirement again =
                ScaffoldConventionReconciler.retireIdeaDerivedFiles(
                        tempDir, retirement.content(), true, log);
        assertThat(again.changed()).isFalse();
    }

    // ── Sandbox ─────────────────────────────────────────────────────

    /** A git root whose index tracks {@code path} under the old slice. */
    private void gitRootTracking(String path) throws Exception {
        exec("git", "init", "-b", "main");
        exec("git", "config", "commit.gpgsign", "false");
        exec("git", "config", "user.email", "root@example.com");
        exec("git", "config", "user.name", "Root");
        exec("git", "config", "core.hooksPath",
                Files.createDirectories(tempDir.resolve(".nohooks")).toAbsolutePath().toString());
        Files.writeString(tempDir.resolve(".gitignore"), OLD_SLICE, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(path).getParent());
        Files.writeString(tempDir.resolve(path),
                "<project version=\"4\"><component name=\"Encoding\"/></project>\n",
                StandardCharsets.UTF_8);
        exec("git", "add", ".gitignore", path);
        exec("git", "commit", "-m", "root under the old slice");
    }

    private String exec(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit " + exitCode + "): "
                    + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
