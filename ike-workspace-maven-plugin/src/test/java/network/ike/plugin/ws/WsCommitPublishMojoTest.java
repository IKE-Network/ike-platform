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

    // ── ike-issues#774: post-loop manifest re-derivation must not strand ──

    /**
     * Regression for ike-issues#774. When the POMs declare an
     * inter-subproject dependency that {@code workspace.yaml} has not yet
     * caught up to, {@code ws:commit-publish}'s post-loop
     * {@link PostMutationSync} re-derives the {@code depends-on} edge and
     * rewrites the manifest. Before the fix that rewrite landed
     * <em>after</em> the commit loop and was left uncommitted, so the next
     * {@code ws:push}/{@code ws:sync} clean-tree preflight rejected the
     * workspace root. The goal must now terminate with a clean tree: the
     * re-derived manifest committed in its own follow-up commit.
     */
    @Test
    void commitPublish_withDepsDrift_leavesCleanTree() throws Exception {
        Path noHooks = tempDir.resolve("no-hooks");
        Files.createDirectories(noHooks);
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(tempDir, "git", "config", "user.email", "test@example.com");
        exec(tempDir, "git", "config", "user.name", "Test");
        exec(tempDir, "git", "config", "commit.gpgsign", "false");

        // lib-b's POM depends on com.example.lib:lib-a, but its manifest
        // entry starts with depends-on: [] — the drift YamlDepsSync resolves.
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.1"
                generated: "2026-05-28"

                defaults:
                  branch: main

                subprojects:
                  lib-a:
                    type: software
                    description: lib-a
                    repo: https://example.com/lib-a.git
                    groupId: com.example.lib
                    depends-on: []
                  lib-b:
                    type: software
                    description: lib-b
                    repo: https://example.com/lib-b.git
                    groupId: com.example.app
                    depends-on: []
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>drift-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        writeSubprojectPom("lib-a", "com.example.lib", null, null);
        writeSubprojectPom("lib-b", "com.example.app", "com.example.lib", "lib-a");
        exec(tempDir, "git", "add", "-A");
        exec(tempDir, "git", "commit", "-m", "initial");

        // A real WIP change so the commit loop has something to commit —
        // mirrors a normal ws:commit invocation.
        Files.writeString(tempDir.resolve("note.txt"), "wip\n",
                StandardCharsets.UTF_8);

        WsCommitPublishMojo mojo = TestLog.createMojo(WsCommitPublishMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "message", "commit wip");
        setField(mojo, "skipLint", true);
        mojo.execute();

        // workspace.yaml must not be left uncommitted (the bug stranded it
        // here). An unrelated generated .gitignore may be untracked in this
        // hermetic sandbox — in real repos it is committed — so the
        // assertion targets the manifest specifically, not full emptiness.
        String postStatus = execCapture(tempDir, "git", "status", "--porcelain");
        assertThat(postStatus)
                .as("ws:commit-publish must not strand workspace.yaml (#774)")
                .doesNotContain("workspace.yaml");
        // The re-derived edge is committed, not dangling.
        assertThat(execCapture(tempDir, "git", "show", "HEAD:workspace.yaml"))
                .contains("subproject: lib-a");
        // It landed as its own follow-up commit, distinct from the WIP one.
        assertThat(execCapture(tempDir, "git", "log", "-1", "--format=%s"))
                .contains("re-derive depends-on edges");
    }

    private void writeSubprojectPom(String name, String groupId,
                                    String depGroupId, String depArtifact)
            throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project>\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n");
        pom.append("    <groupId>").append(groupId).append("</groupId>\n");
        pom.append("    <artifactId>").append(name).append("</artifactId>\n");
        pom.append("    <version>1.0.0-SNAPSHOT</version>\n");
        if (depGroupId != null && depArtifact != null) {
            pom.append("    <dependencies>\n        <dependency>\n");
            pom.append("            <groupId>").append(depGroupId).append("</groupId>\n");
            pom.append("            <artifactId>").append(depArtifact).append("</artifactId>\n");
            pom.append("            <version>1.0.0</version>\n");
            pom.append("        </dependency>\n    </dependencies>\n");
        }
        pom.append("</project>\n");
        Files.writeString(dir.resolve("pom.xml"), pom.toString(),
                StandardCharsets.UTF_8);
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
