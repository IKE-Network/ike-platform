package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkspaceReport} keeps its reports out of git without modifying
 * the tracked tree (IKE-Network/ike-issues#1283): a repository whose
 * {@code .gitignore} lacks the {@code ws꞉*.md} glob gets it in the local
 * {@code .git/info/exclude}, and one that has it is left alone.
 */
class WorkspaceReportExcludeTest {

    @TempDir
    Path tempDir;

    @Test
    void missingGlob_goesToLocalExclude_andTheTreeStaysClean() throws Exception {
        Path repo = initRepo("without-glob", "target/\n");

        WorkspaceReport.write(repo, "ws:checkpoint-draft", "body", null);

        assertThat(repo.resolve("ws꞉checkpoint-draft.md")).exists();
        assertThat(Files.readString(repo.resolve(".gitignore"), StandardCharsets.UTF_8))
                .as(".gitignore is not edited")
                .isEqualTo("target/\n");
        Path exclude = repo.resolve(".git").resolve("info").resolve("exclude");
        assertThat(exclude).exists();
        assertThat(Files.readString(exclude, StandardCharsets.UTF_8))
                .contains(WorkspaceReport.GITIGNORE_PATTERN);
        assertThat(execCapture(repo, "git", "status", "--porcelain"))
                .as("the report is ignored and nothing else changed")
                .isBlank();
    }

    @Test
    void secondWrite_doesNotRepeatTheExcludeLine() throws Exception {
        Path repo = initRepo("twice", "target/\n");

        WorkspaceReport.write(repo, "ws:push", "one", null);
        WorkspaceReport.write(repo, "ws:push", "two", null);

        Path exclude = repo.resolve(".git").resolve("info").resolve("exclude");
        long occurrences = Files.readAllLines(exclude, StandardCharsets.UTF_8).stream()
                .filter(l -> l.trim().equals(WorkspaceReport.GITIGNORE_PATTERN))
                .count();
        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    void presentGlob_leavesBothIgnoreFilesAlone() throws Exception {
        Path repo = initRepo("with-glob",
                "target/\n" + WorkspaceReport.GITIGNORE_PATTERN + "\n");
        Path exclude = repo.resolve(".git").resolve("info").resolve("exclude");
        String excludeBefore = Files.exists(exclude)
                ? Files.readString(exclude, StandardCharsets.UTF_8) : null;

        WorkspaceReport.write(repo, "ws:overview", "body", null);

        assertThat(repo.resolve("ws꞉overview.md")).exists();
        String excludeAfter = Files.exists(exclude)
                ? Files.readString(exclude, StandardCharsets.UTF_8) : null;
        assertThat(excludeAfter).isEqualTo(excludeBefore);
        assertThat(execCapture(repo, "git", "status", "--porcelain")).isBlank();
    }

    @Test
    void noRepository_writesTheReportAndNothingElse() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("plain"));

        WorkspaceReport.write(folder, "ws:help", "body", null);

        assertThat(folder.resolve("ws꞉help.md")).exists();
        assertThat(folder.resolve(".gitignore")).doesNotExist();
    }

    private Path initRepo(String name, String gitignore) throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve(name));
        exec(repo, "git", "init", "-b", "main");
        exec(repo, "git", "config", "commit.gpgsign", "false");
        exec(repo, "git", "config", "user.email", "test@example.com");
        exec(repo, "git", "config", "user.name", "Test");
        Files.writeString(repo.resolve(".gitignore"), gitignore,
                StandardCharsets.UTF_8);
        exec(repo, "git", "add", ".gitignore");
        exec(repo, "git", "commit", "-m", "Initial commit");
        return repo;
    }

    private static void exec(Path workDir, String... command) throws Exception {
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

    private static String execCapture(Path workDir, String... command)
            throws Exception {
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
        return out;
    }
}
