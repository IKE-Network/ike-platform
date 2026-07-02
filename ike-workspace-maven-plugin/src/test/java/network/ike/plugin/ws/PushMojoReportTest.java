package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for IKE-Network/ike-issues#540 — {@code ws:push}
 * must surface per-subproject SHA delta, commit subjects, remote URL,
 * and failure detail with a copy-pasteable retry command, rather than
 * collapsing to a single-line {@code N pushed, M failed} summary.
 */
class PushMojoReportTest {

    @TempDir
    Path tempDir;

    @Test
    void workspace_push_reportContainsPerSubprojectShaDelta() throws Exception {
        scaffoldWorkspace();

        // Make one new commit on each subproject so each push has
        // something to land. Skip lib-a's setUp commit — already on
        // origin.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path dir = tempDir.resolve(name);
            Files.writeString(dir.resolve("new.txt"),
                    "added by " + name, StandardCharsets.UTF_8);
            exec(dir, "git", "add", "new.txt");
            exec(dir, "git", "commit", "-m",
                    "feat(" + name + "): add new.txt");
        }

        PushMojo mojo = TestLog.createMojo(PushMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "remote", "origin");
        mojo.execute();

        // The harness writes the markdown report into the workspace
        // root keyed by goal. Read it and assert per-subproject detail.
        String report = readReport("push");

        // Each subproject shows up with branch + a SHA delta (or
        // new-ref marker) — not just a count.
        assertThat(report)
                .as("per-subproject rows live in the Subprojects table")
                .contains("lib-a")
                .contains("lib-b")
                .contains("app-c")
                .contains("Subproject")
                .contains("SHA delta")
                .contains("Commits");

        // Commit subjects from the pushed range surface under a
        // detail section, not silenced.
        assertThat(report)
                .as("commit subjects must appear in the Pushed commits section")
                .contains("Pushed commits")
                .contains("feat(lib-a): add new.txt")
                .contains("feat(lib-b): add new.txt")
                .contains("feat(app-c): add new.txt");
    }

    @Test
    void workspace_pushAlreadyUpToDate_recordsExplicitState()
            throws Exception {
        scaffoldWorkspace();

        // Push without making any new local commits — origin is
        // already at HEAD for every subproject, so each push must
        // be reported as up-to-date, not silently absorbed.
        PushMojo mojo = TestLog.createMojo(PushMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "remote", "origin");
        mojo.execute();

        String report = readReport("push");
        assertThat(report)
                .as("the report should expose up-to-date explicitly")
                .contains("up-to-date");
    }

    @Test
    void workspace_pushWithStaleCheckpoint_selfHealsAndNotesIt() throws Exception {
        // ike-issues#819: lib-a's .ike/vcs-state checkpoint is orphaned by a
        // rewrite (here, `commit --amend`) that already landed on origin —
        // catchUp must self-heal the checkpoint and surface a plain-language
        // note in the report, not the "may not have completed" warning.
        scaffoldWorkspace();

        Path libA = tempDir.resolve("lib-a");
        VcsOperations.writeVcsState(libA.toFile(), VcsState.Action.COMMIT);
        String staleSha = VcsState.readFrom(libA).orElseThrow().sha();

        exec(libA, "git", "commit", "--amend", "-m", "chore(lib-a): amended");
        String amendedSha = VcsOperations.headSha(libA.toFile());
        assertThat(amendedSha).isNotEqualTo(staleSha);
        exec(libA, "git", "push", "--force", "origin", "main");

        PushMojo mojo = TestLog.createMojo(PushMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "remote", "origin");
        mojo.execute();

        String report = readReport("push");
        assertThat(report)
                .as("a self-healed checkpoint must surface as a Notes entry")
                .contains("Notes")
                .contains("lib-a")
                .doesNotContain("may not have completed");
        assertThat(report)
                .as("nothing was actually missing — lib-a is still up-to-date")
                .contains("up-to-date");

        VcsState healed = VcsState.readFrom(libA).orElseThrow();
        assertThat(healed.sha()).isEqualTo(amendedSha);
        assertThat(healed.action()).isEqualTo(VcsState.Action.SYNC);
    }

    @Test
    void workspace_pushFailure_includesRetryCommand() throws Exception {
        scaffoldWorkspace();

        // Force a non-fast-forward by advancing origin past local for lib-a.
        Path libAOrigin = tempDir.resolve(".upstreams")
                .resolve("upstream-lib-a.git");
        Path libAWork = Files.createTempDirectory("lib-a-rogue-");
        exec(libAWork.getParent(), "git", "clone",
                libAOrigin.toAbsolutePath().toString(),
                libAWork.getFileName().toString());
        exec(libAWork, "git", "config", "user.email", "rogue@example.com");
        exec(libAWork, "git", "config", "user.name", "Rogue");
        Files.writeString(libAWork.resolve("rogue.txt"),
                "remote moved ahead", StandardCharsets.UTF_8);
        exec(libAWork, "git", "add", "rogue.txt");
        exec(libAWork, "git", "commit", "-m", "feat: rogue commit on origin");
        exec(libAWork, "git", "push", "origin", "main");

        // Local lib-a now has a commit that doesn't include the rogue
        // one — its push will be rejected as non-fast-forward.
        Path libA = tempDir.resolve("lib-a");
        Files.writeString(libA.resolve("local.txt"),
                "local only", StandardCharsets.UTF_8);
        exec(libA, "git", "add", "local.txt");
        exec(libA, "git", "commit", "-m", "feat: local-only commit");

        PushMojo mojo = TestLog.createMojo(PushMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "remote", "origin");
        mojo.execute();

        String report = readReport("push");
        // Failures section appears and includes a copy-pasteable
        // retry command (drafts-actionable-remediation).
        assertThat(report)
                .as("failures section must appear when any push fails")
                .contains("Failures")
                .contains("lib-a");
        assertThat(report)
                .as("retry command for lib-a must be copy-pasteable")
                .contains("(cd lib-a && git push origin main)");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void scaffoldWorkspace() throws Exception {
        TestWorkspaceHelper helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspaceWithUpstreams();
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path origin = tempDir.resolve(".upstreams")
                    .resolve("upstream-" + name + ".git");
            Path dir = tempDir.resolve(name);
            // Clone each upstream into the subproject dir so the push
            // has a real origin to talk to.
            Path tmpClone = Files.createTempDirectory("ws-init-" + name + "-");
            exec(tmpClone.getParent(), "git", "clone",
                    origin.toAbsolutePath().toString(),
                    tmpClone.getFileName().toString());
            // Move the clone's contents into dir, replacing the empty
            // bare-init created by TestWorkspaceHelper.
            Files.createDirectories(dir);
            // Delete the existing dir contents (TestWorkspaceHelper's
            // createSubproject made these for the non-upstream path).
            deleteRecursively(dir);
            Files.move(tmpClone, dir);
            exec(dir, "git", "config", "user.email", name + "@example.com");
            exec(dir, "git", "config", "user.name", "Test " + name);
        }
    }

    private static void deleteRecursively(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch (java.io.IOException ignored) {}
                    });
        }
    }

    private String readReport(String goalStem) throws Exception {
        // WorkspaceReport names files "ws<COLON>push.md" — find the
        // first .md report file in the workspace root so this test
        // tolerates the colon-substitution char without hard-coding it.
        try (java.util.stream.Stream<Path> stream = Files.list(tempDir)) {
            Path reportFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getFileName().toString().contains(goalStem))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No report file matching '" + goalStem
                                    + "' in " + tempDir));
            return Files.readString(reportFile, StandardCharsets.UTF_8);
        }
    }

    private static void exec(Path workDir, String... command) throws Exception {
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
}
