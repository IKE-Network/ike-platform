package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for IKE-Network/ike-issues#537 — {@code
 * ws:checkpoint-publish} used to self-trip by running its internal
 * auto-alignment step (which dirties the working tree) and then
 * failing its own preflight on that same dirtiness.
 *
 * <p>The fix runs preflight up front against user state, then
 * auto-commits the alignment output under a goal-owned message before
 * the checkpoint preflight runs again. These tests exercise both
 * halves: the happy path (alignment dirties the tree, goal recovers,
 * checkpoint completes) and the user-state guard (uncommitted user
 * work fails fast before any alignment touches the workspace).
 */
class CheckpointPublishSelfTripTest {

    @TempDir
    Path tempDir;

    @Test
    void alignmentSideEffects_areAutoCommittedSoPreflightPasses()
            throws Exception {
        scaffoldGitWorkspace();

        DirtyingCheckpointPublishMojo mojo =
                new DirtyingCheckpointPublishMojo(
                        tempDir.resolve(".gitignore"),
                        "*.log\nbuild/\n");
        TestLog.injectInto(mojo);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "name", "self-trip-fix");
        setField(mojo, "issueRepo", "");

        // Pre-fix this would throw because the preflight inside
        // super.runGoal() trips on the .gitignore modification produced
        // by the (overridden) autoAlign step.
        mojo.execute();

        // The checkpoint file lands — i.e. the goal got past its own
        // preflight after auto-committing the alignment output.
        Path checkpointFile = tempDir.resolve("checkpoints")
                .resolve("checkpoint-self-trip-fix.yaml");
        assertThat(Files.exists(checkpointFile))
                .as("checkpoint should be written despite alignment side effects")
                .isTrue();

        // The alignment's .gitignore modification is now in history
        // under the goal-owned message.
        String log = execCapture(tempDir, "git", "log", "--format=%s");
        assertThat(log)
                .as("goal-owned commit must record the alignment")
                .contains(WsCheckpointPublishMojo.ALIGNMENT_COMMIT_MESSAGE);

        // The alignment-stage modification to .gitignore is no longer
        // sitting uncommitted — the goal-owned commit absorbed it. (A
        // separate, harmless .gitignore touch from WorkspaceReport's
        // report-write self-heal runs AFTER the goal completes and can
        // appear in `git status`; that's unrelated to #537.)
        String gitignoreBlame = execCapture(tempDir, "git", "log", "-1",
                "--format=%s", "--", ".gitignore");
        assertThat(gitignoreBlame)
                .as("last commit touching .gitignore should be the goal-owned one")
                .contains(WsCheckpointPublishMojo.ALIGNMENT_COMMIT_MESSAGE);
    }

    @Test
    void uncommittedUserWork_failsFastBeforeAlignmentRuns() throws Exception {
        scaffoldGitWorkspace();

        // User has uncommitted work — this is exactly what the preflight
        // should catch BEFORE we run alignment, otherwise the user's
        // changes would be silently swept into the goal-owned commit.
        Files.writeString(tempDir.resolve(".gitignore"),
                "user-edit\n", StandardCharsets.UTF_8);

        DirtyingCheckpointPublishMojo mojo =
                new DirtyingCheckpointPublishMojo(
                        tempDir.resolve("should-not-be-touched.txt"),
                        "alignment-side-effect\n");
        TestLog.injectInto(mojo);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "name", "user-state-guard");
        setField(mojo, "issueRepo", "");

        assertThatThrownBy(mojo::execute)
                .as("uncommitted user work must abort the goal early")
                .isInstanceOf(MojoException.class);

        // autoAlign() must not have run — its side effect file is absent.
        assertThat(Files.exists(tempDir.resolve("should-not-be-touched.txt")))
                .as("alignment must not run when preflight fails")
                .isFalse();

        // User's edit is still there as an uncommitted change — we did
        // not sweep it into a commit.
        String status = execCapture(tempDir, "git", "status", "--porcelain");
        assertThat(status)
                .as("user's uncommitted work must be left untouched")
                .contains(".gitignore");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void scaffoldGitWorkspace() throws Exception {
        // Empty hooks directory so the global IKE git hooks don't fire
        // — see VcsBridgeIntegrationTest for the same rationale.
        Path noHooks = tempDir.resolve("no-hooks");
        Files.createDirectories(noHooks);

        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.1"
                generated: "2026-05-28"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>checkpoint-self-trip-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".gitignore"), "*.log\n",
                StandardCharsets.UTF_8);

        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "config", "commit.gpgsign", "false");
        exec(tempDir, "git", "add", ".");
        exec(tempDir, "git", "commit", "-m", "initial");
    }

    private static void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit " + exitCode
                    + "): " + String.join(" ", command));
        }
    }

    private static String execCapture(Path workDir, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit " + exitCode
                    + "): " + String.join(" ", command) + "\n" + out);
        }
        return out;
    }

    private static void setField(Object target, String fieldName, Object value,
                                 Class<?> declaringClass) throws Exception {
        Field f = declaringClass.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
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

    /**
     * Test stand-in for {@link WsCheckpointPublishMojo} that replaces
     * the real {@code ws:align-publish} subprocess with a direct write
     * to a tracked file. Captures the essential pre-fix self-trip
     * scenario — alignment produces uncommitted state — without
     * spawning a nested Maven build.
     */
    private static final class DirtyingCheckpointPublishMojo
            extends WsCheckpointPublishMojo {
        private final Path fileToWrite;
        private final String content;

        DirtyingCheckpointPublishMojo(Path fileToWrite, String content) {
            this.fileToWrite = fileToWrite;
            this.content = content;
        }

        @Override
        protected void autoAlign() {
            try {
                Files.writeString(fileToWrite, content, StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
