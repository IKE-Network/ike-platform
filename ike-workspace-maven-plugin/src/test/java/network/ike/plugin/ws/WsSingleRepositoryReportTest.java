package network.ike.plugin.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code ws:} goal run in a single repository writes its {@code ws꞉*.md}
 * report at the repository root and leaves the tree clean
 * (IKE-Network/ike-issues#1283). Before the fix the writer resolved the
 * location through the manifest, which a working set of one has none of,
 * and wrote nothing. The fixture's {@code .gitignore} lacks the report glob
 * on purpose, so the writer has to keep the report out of git by itself.
 */
class WsSingleRepositoryReportTest {

    @TempDir
    Path tempDir;

    private String originalUserDir;

    @BeforeEach
    void rememberUserDir() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void goalInSingleRepository_writesItsReport_andLeavesTheTreeClean()
            throws Exception {
        Path repo = scaffoldRepository();

        WsCheckpointDraftMojo mojo = new WsCheckpointDraftMojo();
        TestLog.injectInto(mojo);
        System.setProperty("user.dir", repo.toAbsolutePath().toString());
        setField(mojo, "name", "report-test");
        setField(mojo, "issueRepo", "");
        mojo.execute();

        Path report = repo.resolve("ws꞉checkpoint-draft.md");
        assertThat(report).as("the report lands at the repository root").exists();
        assertThat(Files.readString(report, StandardCharsets.UTF_8))
                .contains("# ws:checkpoint-draft")
                .contains("Tag `checkpoint/report-test` would be created.");
        assertThat(Files.readString(repo.resolve(".gitignore"), StandardCharsets.UTF_8))
                .as(".gitignore is not edited")
                .doesNotContain(WorkspaceReport.GITIGNORE_PATTERN);
        assertThat(execCapture(repo, "git", "status", "--porcelain"))
                .as("the report is excluded locally; nothing else changed")
                .isBlank();
    }

    private Path scaffoldRepository() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("single-repo"));
        Files.writeString(repo.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>single-repo</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(repo.resolve(".gitignore"), ".ike/vcs-state\n",
                StandardCharsets.UTF_8);
        exec(repo, "git", "init", "-b", "main");
        exec(repo, "git", "config", "commit.gpgsign", "false");
        exec(repo, "git", "config", "tag.gpgsign", "false");
        exec(repo, "git", "config", "user.email", "test@example.com");
        exec(repo, "git", "config", "user.name", "Test");
        exec(repo, "git", "add", ".");
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

    private static void setField(Object target, String fieldName, Object value)
            throws Exception {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
