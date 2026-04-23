package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link CheckpointSupport}.
 *
 * <p>Checkpoints only tag current HEAD — no POM changes, no builds.
 * These tests verify tagging and draft behavior.
 */
class CheckpointMojoTest {

    @TempDir
    Path tempDir;

    // ── Dry-run ────────────────────────────────────────────────────

    @Test
    void dryRun_completesWithoutChanges() throws Exception {
        createCheckpointProject(tempDir);

        String headBefore = execCapture(tempDir, "git", "rev-parse", "HEAD");
        String tagsBefore = execCapture(tempDir, "git", "tag", "-l");
        String pomBefore = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);

        assertThatCode(() -> CheckpointSupport.preview(
                tempDir.toFile(), "checkpoint/test-run",
                new TestLog()))
                .doesNotThrowAnyException();

        // No commits, tags, or POM changes
        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD"))
                .isEqualTo(headBefore);
        assertThat(execCapture(tempDir, "git", "tag", "-l"))
                .isEqualTo(tagsBefore);
        assertThat(Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .isEqualTo(pomBefore);
    }

    @Test
    void dryRun_withCustomLabel() throws Exception {
        createCheckpointProject(tempDir);

        assertThatCode(() -> CheckpointSupport.preview(
                tempDir.toFile(), "checkpoint/sprint-42",
                new TestLog()))
                .doesNotThrowAnyException();
    }

    // ── Tag creation ───────────────────────────────────────────────

    @Test
    void checkpoint_createsTag() throws Exception {
        createCheckpointProject(tempDir);

        CheckpointSupport.checkpoint(
                tempDir.toFile(), "checkpoint/test-tag",
                new TestLog());

        String tags = execCapture(tempDir, "git", "tag", "-l");
        assertThat(tags).contains("checkpoint/test-tag");
    }

    @Test
    void checkpoint_doesNotModifyPom() throws Exception {
        createCheckpointProject(tempDir);

        String pomBefore = Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8);

        CheckpointSupport.checkpoint(
                tempDir.toFile(), "checkpoint/no-pom-change",
                new TestLog());

        assertThat(Files.readString(tempDir.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .isEqualTo(pomBefore);
    }

    @Test
    void checkpoint_doesNotCreateCommits() throws Exception {
        createCheckpointProject(tempDir);

        String headBefore = execCapture(tempDir, "git", "rev-parse", "HEAD");

        CheckpointSupport.checkpoint(
                tempDir.toFile(), "checkpoint/no-commits",
                new TestLog());

        // HEAD should not change — tag is on existing commit
        assertThat(execCapture(tempDir, "git", "rev-parse", "HEAD"))
                .isEqualTo(headBefore);
    }

    // ── YAML generation ────────────────────────────────────────────

    @Test
    void buildCheckpointYaml_includesComponentData() {
        var snapshots = java.util.List.of(
                new SubprojectSnapshot(
                        "tinkar-core", "abc123full", "abc123", "main",
                        "1.0.0-SNAPSHOT", false, "software", false));

        String yaml = WsCheckpointDraftMojo.buildCheckpointYaml(
                "test", "2026-04-08T00:00:00Z", "tester", "1.0",
                snapshots, java.util.List.of());

        assertThat(yaml)
                .contains("name: \"test\"")
                .contains("tinkar-core:")
                .contains("sha: \"abc123full\"")
                .contains("version: \"1.0.0-SNAPSHOT\"")
                .contains("branch: \"main\"");
    }

    @Test
    void buildCheckpointYaml_includesAbsentComponents() {
        String yaml = WsCheckpointDraftMojo.buildCheckpointYaml(
                "test", "2026-04-08T00:00:00Z", "tester", "1.0",
                java.util.List.of(), java.util.List.of("missing-repo"));

        assertThat(yaml)
                .contains("missing-repo:")
                .contains("status: absent");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void createCheckpointProject(Path dir) throws Exception {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>checkpoint-project</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                </project>
                """;
        Files.writeString(dir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);

        exec(dir, "git", "init", "-b", "main");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
        exec(dir, "git", "add", ".");
        exec(dir, "git", "commit", "-m", "Initial commit");
    }

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
