package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WsCommitPublishMojo}.
 *
 * <p>Covers the formatting helpers (ike-issues#231) and the
 * staged-plus-unstaged mix regression (ike-issues#536).
 */
class WsCommitPublishMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void suffix_unstaged_only_lists_tracked_paths() {
        String suffix = WsCommitPublishMojo.formatUncommittedSuffix(
                "src/main/java/A.java, src/main/java/B.java",
                List.of());
        assertThat(suffix).isEqualTo(
                "unstaged: src/main/java/A.java, src/main/java/B.java");
    }

    @Test
    void suffix_untracked_only_lists_new_file_paths() {
        String suffix = WsCommitPublishMojo.formatUncommittedSuffix(
                "",
                List.of("src/main/java/Foo.java", "src/main/java/Bar.java"));
        assertThat(suffix).isEqualTo(
                "untracked: src/main/java/Foo.java, src/main/java/Bar.java");
    }

    @Test
    void suffix_both_kinds_shows_both_lists_separated_by_semicolon() {
        String suffix = WsCommitPublishMojo.formatUncommittedSuffix(
                "src/main/java/Existing.java",
                List.of("src/main/java/New.java"));
        assertThat(suffix).isEqualTo(
                "unstaged: src/main/java/Existing.java; untracked: src/main/java/New.java");
    }

    @Test
    void suffix_neither_emits_uncommitted_placeholder() {
        // Defensive fallback — if the caller invoked us with empty
        // inputs (shouldn't happen in practice), don't emit empty parens.
        assertThat(invokeSuffix("", List.of())).isEqualTo("uncommitted");
    }

    @Test
    void suffix_handles_null_inputs_defensively() {
        assertThat(invokeSuffix(null, null)).isEqualTo("uncommitted");
        assertThat(invokeSuffix(null, List.of("x"))).isEqualTo("untracked: x");
        assertThat(invokeSuffix("y", null)).isEqualTo("unstaged: y");
    }

    /** Test-internal alias to keep the test names readable. */
    private static String invokeSuffix(String unstaged, List<String> newFiles) {
        return WsCommitPublishMojo.formatUncommittedSuffix(unstaged, newFiles);
    }

    // ── ike-issues#536: mixed staged + unstaged regression ──────────

    /**
     * Regression for ike-issues#536. With one tracked file staged and
     * another tracked file modified-but-unstaged, the resulting
     * workspace-root commit must include both — the goal's reported
     * "N modified" count, the {@code -A} stage step, and the commit
     * contents must all agree. The earlier split-condition form of
     * {@code commitOne}'s pre-commit add ran {@code git add -A} only
     * when there were no staged changes already, so the mix case fell
     * through and silently dropped the unstaged half.
     */
    @Test
    void workspaceRoot_mixedStagedAndUnstaged_commitsBoth() throws Exception {
        // Empty hooks dir — see VcsBridgeIntegrationTest for the
        // rationale (the global IKE hooks would otherwise interfere).
        Path noHooks = tempDir.resolve("no-hooks");
        Files.createDirectories(noHooks);

        // Workspace root is itself the git repo (matches the issue's
        // reproducer where the dirty files were at the workspace root).
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "config", "commit.gpgsign", "false");

        // Minimal workspace files so isWorkspaceMode() returns true.
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
                    <artifactId>commit-mix-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);

        // Two tracked files in the initial commit; both will be modified
        // afterward, one staged and one not.
        Files.writeString(tempDir.resolve("a.txt"), "original\n",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("b.txt"), "original\n",
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "workspace.yaml", "pom.xml",
                "a.txt", "b.txt");
        exec(tempDir, "git", "commit", "-m", "initial");

        // Stage a modification to a.txt.
        Files.writeString(tempDir.resolve("a.txt"), "changed-a\n",
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "a.txt");

        // Modify b.txt — leave it unstaged. This is the file the pre-fix
        // code would silently drop because hasStagedChanges() is true
        // (a.txt is staged), so the else-if for "modCount > 0 &&
        // !hasStagedChanges" never fires.
        Files.writeString(tempDir.resolve("b.txt"), "changed-b\n",
                StandardCharsets.UTF_8);

        String preStatus = execCapture(tempDir, "git", "status", "--porcelain");
        assertThat(preStatus)
                .as("test setup must reproduce the bug's mixed state")
                .contains("M  a.txt")
                .contains(" M b.txt");

        // Run the mojo. skipLint avoids the .mvn/jvm.config preflight,
        // which is irrelevant to this test.
        WsCommitPublishMojo mojo = TestLog.createMojo(WsCommitPublishMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "message", "mixed-state commit");
        setField(mojo, "skipLint", true);
        mojo.execute();

        // HEAD must include BOTH files. Pre-fix, only a.txt (the staged
        // half) landed.
        String show = execCapture(tempDir, "git", "show",
                "--name-status", "--format=", "HEAD");
        assertThat(show)
                .as("HEAD commit should contain both files (issue #536)")
                .contains("M\ta.txt")
                .contains("M\tb.txt");

        // Neither a.txt nor b.txt should remain in the working tree
        // afterward — both must have landed in the commit. Pre-fix,
        // b.txt was still sitting unstaged after BUILD SUCCESS. Other
        // post-mutation side effects (e.g., a generated .gitignore from
        // YamlDepsSync) are unrelated to this bug.
        String postStatus = execCapture(tempDir, "git", "status", "--porcelain");
        assertThat(postStatus)
                .as("a.txt and b.txt must not remain uncommitted")
                .doesNotContain("a.txt")
                .doesNotContain("b.txt");
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

    private static void setField(Object target, String fieldName, Object value,
                                 Class<?> declaringClass) throws Exception {
        Field f = declaringClass.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
