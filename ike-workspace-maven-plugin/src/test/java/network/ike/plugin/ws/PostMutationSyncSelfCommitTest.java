package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link PostMutationSync}'s self-committing behaviour
 * (IKE-Network/ike-issues#774).
 *
 * <p>The reported failure: {@code ws:commit} re-derived {@code workspace.yaml}
 * {@code depends-on} edges <em>after</em> its commit loop, stranding the
 * rewrite as an uncommitted change that the next {@code ws:push}/{@code ws:sync}
 * clean-tree preflight rejected. The fix commits the re-derivation in
 * isolation when it is the sole pending change, while leaving a caller's own
 * concurrent manifest edit (e.g. {@code ws:add}) untouched.
 *
 * <p>The workspace has {@code lib-b} whose POM depends on {@code lib-a} but
 * whose {@code workspace.yaml} entry starts with {@code depends-on: []} — a
 * real drift that {@link YamlDepsSync} resolves by adding the {@code lib-a}
 * edge.
 */
class PostMutationSyncSelfCommitTest {

    @TempDir Path tempDir;
    private final Log log = new TestLog();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
        // lib-a is a leaf; lib-b's POM depends on com.example.lib:lib-a, but
        // its manifest entry has depends-on: [] — the drift under test.
        createSubprojectDir("lib-a", "com.example.lib", null, null);
        addToManifest("lib-a", "com.example.lib");
        createSubprojectDir("lib-b", "com.example.app", "com.example.lib", "lib-a");
        addToManifest("lib-b", "com.example.app");
    }

    @Test
    void commits_rederivation_when_it_is_the_sole_pending_change()
            throws Exception {
        gitInitAndCommitAll("baseline");
        String headBefore = head();

        boolean committed = PostMutationSync.refresh(tempDir.toFile(), log);

        assertThat(committed).as("refresh committed the re-derivation").isTrue();
        // The edge was written and is now committed, not left dangling.
        assertThat(manifest()).contains("subproject: lib-a");
        assertThat(VcsOperations.isClean(tempDir.toFile()))
                .as("working tree is clean after refresh").isTrue();
        // A distinct, honest commit on top of the baseline.
        assertThat(head()).isNotEqualTo(headBefore);
        assertThat(headSubject()).contains("re-derive depends-on edges");
        assertThat(headBody()).contains("ike-issues#774");
        // The commit body enumerates the edges it changed (#964) —
        // a re-derivation is never a silent generic commit.
        assertThat(headBody()).contains("lib-b depends-on (+1");
        assertThat(headBody()).contains("added [lib-a]");
    }

    @Test
    void is_idempotent_on_a_second_run() throws Exception {
        gitInitAndCommitAll("baseline");

        assertThat(PostMutationSync.refresh(tempDir.toFile(), log)).isTrue();
        String headAfterFirst = head();

        // POMs now match the YAML — nothing to do, no new commit.
        assertThat(PostMutationSync.refresh(tempDir.toFile(), log)).isFalse();
        assertThat(head()).isEqualTo(headAfterFirst);
        assertThat(VcsOperations.isClean(tempDir.toFile())).isTrue();
    }

    @Test
    void leaves_a_callers_own_uncommitted_manifest_edit_alone()
            throws Exception {
        gitInitAndCommitAll("baseline");
        String headBefore = head();

        // Simulate a caller (e.g. ws:add) that has edited the manifest and
        // deliberately left it staged/unstaged for its own commit policy.
        Files.writeString(tempDir.resolve("workspace.yaml"),
                manifest() + "\n# caller's pending edit\n",
                StandardCharsets.UTF_8);

        boolean committed = PostMutationSync.refresh(tempDir.toFile(), log);

        assertThat(committed)
                .as("must not commit when the manifest already carries an edit")
                .isFalse();
        // No self-commit was made; HEAD is untouched.
        assertThat(head()).isEqualTo(headBefore);
        // The manifest is still uncommitted (the caller will commit it).
        assertThat(VcsOperations.isPathClean(tempDir.toFile(), "workspace.yaml"))
                .isFalse();
    }

    @Test
    void returns_false_and_does_not_throw_without_a_git_repo()
            throws Exception {
        // No git init — refresh re-derives on disk but cannot commit.
        boolean committed = PostMutationSync.refresh(tempDir.toFile(), log);

        assertThat(committed).isFalse();
        assertThat(Files.exists(tempDir.resolve(".git"))).isFalse();
        // The re-derivation still landed on disk for a later bootstrap.
        assertThat(manifest()).contains("subproject: lib-a");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String manifest() throws Exception {
        return Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
    }

    private void createSubprojectDir(String name, String groupId,
                                     String depGroupId, String depArtifact)
            throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project>\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n");
        pom.append("    <groupId>").append(groupId).append("</groupId>\n");
        pom.append("    <artifactId>").append(name).append("</artifactId>\n");
        pom.append("    <version>1.0.0-SNAPSHOT</version>\n");
        if (depGroupId != null && depArtifact != null) {
            pom.append("    <dependencies>\n");
            pom.append("        <dependency>\n");
            pom.append("            <groupId>").append(depGroupId).append("</groupId>\n");
            pom.append("            <artifactId>").append(depArtifact).append("</artifactId>\n");
            pom.append("            <version>1.0.0</version>\n");
            pom.append("        </dependency>\n");
            pom.append("    </dependencies>\n");
        }
        pom.append("</project>\n");
        Files.writeString(dir.resolve("pom.xml"), pom.toString(),
                StandardCharsets.UTF_8);
    }

    private void addToManifest(String name, String groupId) throws Exception {
        String entry = "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + "    groupId: " + groupId + "\n"
                + "    depends-on: []\n";
        Files.writeString(tempDir.resolve("workspace.yaml"),
                manifest() + entry, StandardCharsets.UTF_8);
    }

    private void gitInitAndCommitAll(String message) throws Exception {
        exec("git", "init", "-b", "main");
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec("git", "config", "core.hooksPath", noHooks.toAbsolutePath().toString());
        exec("git", "config", "commit.gpgsign", "false");
        exec("git", "config", "user.email", "test@example.com");
        exec("git", "config", "user.name", "Test");
        exec("git", "add", "-A");
        exec("git", "commit", "-m", message);
    }

    private String head() throws Exception {
        return capture("git", "rev-parse", "HEAD");
    }

    private String headSubject() throws Exception {
        return capture("git", "log", "-1", "--format=%s");
    }

    private String headBody() throws Exception {
        return capture("git", "log", "-1", "--format=%b");
    }

    private void exec(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
    }

    private String capture(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
        return out.trim();
    }
}
