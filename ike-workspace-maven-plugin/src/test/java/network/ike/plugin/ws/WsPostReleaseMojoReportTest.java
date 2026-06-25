package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report test for {@code ws:post-release} — the working-set table migration
 * (#767, child C of epic #764). Asserts the goal's {@code ws꞉post-release.md}
 * report uses the shared {@link WorkingSetReportTable}: one row per
 * {@link network.ike.workspace.WorkingSet.Member} <em>including the
 * aggregator</em> (the workspace root), with an {@code Effect} column.
 *
 * <p>The aggregator row is the #763 fix: post-release previously iterated the
 * declared subprojects only, so the workspace root never appeared in the
 * report — its version, branch, and SHA invisible. Here the root is a real
 * committed repo, and the test asserts its row is present, labeled
 * {@code aggregator}, and carries the root POM's version string (gathered the
 * same way as a subproject's, which is what #763 restores).
 */
class WsPostReleaseMojoReportTest {

    @TempDir
    Path tempDir;

    @Test
    void postReleaseReport_usesWorkingSetTable_withAggregatorRow()
            throws Exception {
        new TestWorkspaceHelper(tempDir).buildWorkspace();
        writeManifestWithMavenVersions();
        initAggregatorRepo();

        WsPostReleaseMojo mojo = TestLog.createMojo(WsPostReleaseMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "nextVersion", "4-SNAPSHOT");
        mojo.execute();

        String report = readReport("post-release");

        // The shared working-set table — not a subprojects-only table.
        assertThat(report)
                .as("report uses the shared working-set table headers")
                .contains("Member")
                .contains("Kind")
                .contains("Effect");

        // Subprojects appear with their applied bump effect.
        assertThat(report)
                .as("each subproject is a row with a bump effect")
                .contains("lib-a")
                .contains("lib-b")
                .contains("app-c")
                .contains("subproject")
                .contains("bumped")
                .contains("4-SNAPSHOT");

        // The aggregator (workspace root) is now a row — the #763 fix.
        String aggregatorName = tempDir.getFileName().toString();
        assertThat(report)
                .as("the aggregator (workspace root) appears as a row")
                .contains(aggregatorName)
                .contains("aggregator");

        // And the root POM's version string is gathered the same way as a
        // subproject's — the staleness a subprojects-only report hid is now
        // visible. The root stays at 1-SNAPSHOT (its own bump is #768).
        assertThat(report)
                .as("the aggregator row carries the root POM version (#763)")
                .contains("1-SNAPSHOT");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Replace the helper's {@code workspace.yaml} with one that carries
     * {@code maven-version:} fields, which is what {@code post-release}
     * rewrites (via {@code ManifestWriter.updateMavenVersions}) and then
     * commits on the aggregator. The default helper manifest only declares
     * {@code version:}, so the rewrite would be a no-op and the aggregator
     * commit would have nothing to stage. Subproject membership is unchanged —
     * the three subprojects and their POMs from {@code buildWorkspace()} stay.
     */
    private void writeManifestWithMavenVersions() throws Exception {
        String yaml = """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                  lib-a:
                    type: software
                    description: Shared library A
                    repo: https://example.com/lib-a.git
                    branch: main
                    maven-version: "1.0.0-SNAPSHOT"
                  lib-b:
                    type: software
                    description: Library B (depends on A)
                    repo: https://example.com/lib-b.git
                    branch: main
                    maven-version: "2.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-a
                        relationship: build
                  app-c:
                    type: software
                    description: Application C (depends on B)
                    repo: https://example.com/app-c.git
                    branch: main
                    maven-version: "3.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-b
                        relationship: build
                """;
        Files.writeString(tempDir.resolve("workspace.yaml"), yaml,
                StandardCharsets.UTF_8);
    }

    /**
     * Make the workspace root ({@code tempDir}) a real committed git repo with
     * an aggregator {@code pom.xml}, so the working set's aggregator member has
     * a version, branch, and SHA to gather — exactly the member post-release
     * previously omitted. The version (1-SNAPSHOT) is deliberately left as the
     * development version: post-release commits the rewritten workspace.yaml on
     * the root but does not bump the root POM (#768).
     */
    private void initAggregatorRepo() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>workspace-root</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(tempDir, "git", "config", "commit.gpgsign", "false");
        exec(tempDir, "git", "config", "user.email", "root@example.com");
        exec(tempDir, "git", "config", "user.name", "Test Root");
        Files.writeString(tempDir.resolve(".gitignore"),
                "lib-a/\nlib-b/\napp-c/\n.upstreams/\n.nohooks/\n*.md\n",
                StandardCharsets.UTF_8);
        exec(tempDir, "git", "add", "pom.xml", "workspace.yaml", ".gitignore");
        exec(tempDir, "git", "commit", "-m", "Initial workspace root");
    }

    private String readReport(String goalStem) throws Exception {
        try (var stream = Files.list(tempDir)) {
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
