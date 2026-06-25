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
 * Regression tests for IKE-Network/ike-issues#542 — {@code ws:sync}
 * must compose the per-subproject pull and push detail under
 * explicit phase headers, rather than emit the two-word
 * {@code "pulled then pushed."} body that hid both halves' state.
 */
class WsSyncMojoReportTest {

    @TempDir
    Path tempDir;

    @Test
    void sync_reportCombinesPullAndPushPhases() throws Exception {
        scaffoldWorkspaceWithUpstreams();

        // Advance origin/main so the pull half has work, AND add a
        // local commit so the push half has work.
        for (String name : new String[]{"lib-a", "lib-b", "app-c"}) {
            Path origin = tempDir.resolve(".upstreams")
                    .resolve("upstream-" + name + ".git");
            Path advanceClone = Files.createTempDirectory("advance-" + name + "-");
            exec(advanceClone.getParent(), "git", "clone",
                    origin.toAbsolutePath().toString(),
                    advanceClone.getFileName().toString());
            exec(advanceClone, "git", "config", "user.email",
                    name + "@example.com");
            exec(advanceClone, "git", "config", "user.name", "Adv " + name);
            Files.writeString(advanceClone.resolve("from-remote.txt"),
                    "remote bump", StandardCharsets.UTF_8);
            exec(advanceClone, "git", "add", "from-remote.txt");
            exec(advanceClone, "git", "commit", "-m",
                    "feat(remote): " + name + " upstream-only commit");
            exec(advanceClone, "git", "push", "origin", "main");

            Path local = tempDir.resolve(name);
            Files.writeString(local.resolve("from-local.txt"),
                    "local bump", StandardCharsets.UTF_8);
            exec(local, "git", "add", "from-local.txt");
            exec(local, "git", "commit", "-m",
                    "feat(local): " + name + " local-only commit");
        }

        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "remote", "origin");
        mojo.execute();

        String report = readReport("sync");

        // Both phases must have their own header so a reviewer can
        // tell which side each state change came from.
        assertThat(report)
                .as("phase headers must appear in the sync report")
                .contains("## Pull phase")
                .contains("## Push phase");

        // The per-subproject pull detail flows through verbatim.
        assertThat(report)
                .as("pull phase carries the merged commit subjects from #541")
                .contains("feat(remote): lib-a upstream-only commit")
                .contains("feat(remote): lib-b upstream-only commit")
                .contains("feat(remote): app-c upstream-only commit");

        // The per-subproject push detail flows through verbatim.
        assertThat(report)
                .as("push phase carries the pushed commit subjects from #540")
                .contains("feat(local): lib-a local-only commit")
                .contains("feat(local): lib-b local-only commit")
                .contains("feat(local): app-c local-only commit");
    }

    @Test
    void sync_pullOnly_recordsPushSkipped() throws Exception {
        scaffoldWorkspaceWithUpstreams();

        WsSyncMojo mojo = TestLog.createMojo(WsSyncMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "remote", "origin");
        setField(mojo, "pullOnly", true);
        mojo.execute();

        String report = readReport("sync");
        assertThat(report)
                .as("push phase must be marked skipped, not silently absorbed")
                .contains("## Push phase")
                .contains("skipped")
                .contains("-DpullOnly");
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
