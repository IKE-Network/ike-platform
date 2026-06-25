package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for IKE-Network/ike-issues#541 — {@code ws:pull}
 * must include per-subproject SHA delta, the commit subjects merged
 * in, and an explicit up-to-date state, rather than emit a one-line
 * "N pulled, M skipped, K failed" summary.
 */
class PullWorkspaceMojoReportTest {

    @TempDir
    Path tempDir;

    @Test
    void workspace_pull_reportContainsPerSubprojectShaDeltaAndSubjects()
            throws Exception {
        scaffoldWorkspaceWithUpstreams();

        // Advance origin/main for each subproject so the local pull has
        // something to merge in. We do this from a fresh sibling clone
        // of each bare upstream so the local subproject dir stays clean
        // for the pull preflight.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path origin = tempDir.resolve(".upstreams")
                    .resolve("upstream-" + name + ".git");
            Path advanceClone = Files.createTempDirectory("advance-" + name + "-");
            exec(advanceClone.getParent(), "git", "clone",
                    origin.toAbsolutePath().toString(),
                    advanceClone.getFileName().toString());
            exec(advanceClone, "git", "config", "user.email", name + "@example.com");
            exec(advanceClone, "git", "config", "user.name", "Adv " + name);
            Files.writeString(advanceClone.resolve("upstream-only.txt"),
                    "remote update for " + name, StandardCharsets.UTF_8);
            exec(advanceClone, "git", "add", "upstream-only.txt");
            exec(advanceClone, "git", "commit", "-m",
                    "feat(" + name + "): upstream-only commit");
            exec(advanceClone, "git", "push", "origin", "main");
        }

        PullWorkspaceMojo mojo = TestLog.createMojo(PullWorkspaceMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        mojo.execute();

        String report = readReport("pull");
        assertThat(report)
                .as("Subprojects table must appear with SHA delta + Commits columns")
                .contains("Subprojects")
                .contains("SHA delta")
                .contains("Commits");
        assertThat(report)
                .as("each subproject row carries its name")
                .contains("lib-a")
                .contains("lib-b")
                .contains("app-c");
        assertThat(report)
                .as("the merged commit subjects appear under a detail section")
                .contains("Merged commits")
                .contains("feat(lib-a): upstream-only commit")
                .contains("feat(lib-b): upstream-only commit")
                .contains("feat(app-c): upstream-only commit");
    }

    @Test
    void workspace_pullAlreadyUpToDate_recordsExplicitState() throws Exception {
        scaffoldWorkspaceWithUpstreams();

        PullWorkspaceMojo mojo = TestLog.createMojo(PullWorkspaceMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        mojo.execute();

        String report = readReport("pull");
        assertThat(report)
                .as("the report must distinguish up-to-date from a generic skip")
                .contains("up-to-date");
    }

    @Test
    void workspace_pullWithoutUpstream_emitsCopyPastableRecoveryCommand()
            throws Exception {
        scaffoldWorkspaceWithUpstreams();

        // Drop lib-a's branch upstream so pull cannot proceed there.
        // git pull --rebase against a branch without tracking will
        // exit non-zero with "There is no tracking information…".
        Path libA = tempDir.resolve("lib-a");
        exec(libA, "git", "branch", "--unset-upstream", "main");

        PullWorkspaceMojo mojo = TestLog.createMojo(PullWorkspaceMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        mojo.execute();

        String report = readReport("pull");
        assertThat(report)
                .as("no-upstream cases must surface explicitly")
                .contains("No upstream configured")
                .contains("lib-a");
        assertThat(report)
                .as("recovery block must include the exact git push -u command")
                .contains("(cd lib-a && git push -u origin main)");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void scaffoldWorkspaceWithUpstreams() throws Exception {
        TestWorkspaceHelper helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspaceWithUpstreams();
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path origin = tempDir.resolve(".upstreams")
                    .resolve("upstream-" + name + ".git");
            Path dir = tempDir.resolve(name);
            Path tmpClone = Files.createTempDirectory("ws-init-" + name + "-");
            exec(tmpClone.getParent(), "git", "clone",
                    origin.toAbsolutePath().toString(),
                    tmpClone.getFileName().toString());
            Files.createDirectories(dir);
            deleteRecursively(dir);
            Files.move(tmpClone, dir);
            exec(dir, "git", "config", "user.email", name + "@example.com");
            exec(dir, "git", "config", "user.name", "Test " + name);
        }
    }

    private static void deleteRecursively(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch (java.io.IOException ignored) {}
                    });
        }
    }

    private String readReport(String goalStem) throws Exception {
        try (Stream<Path> stream = Files.list(tempDir)) {
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
}
