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

/**
 * Report tests for {@code ws:checkpoint} (epic #764, child #767) — the
 * checkpoint markdown report must render the shared working-set table with
 * one row per member, the <em>aggregator</em> (workspace root) INCLUDED.
 *
 * <p>The motivating gap (#766): the checkpoint's per-subproject table was
 * built from {@code ctx.snapshots()}, which never carried the aggregator, so
 * the workspace root the checkpoint actually tags was missing from its own
 * report — and the root's version staleness (#763) was invisible. After the
 * migration the root is a first-class {@link WorkingSetReportTable.Row},
 * labeled {@code aggregator}, with its POM version gathered from the
 * always-present root directory (the #763 fix).
 */
class WsCheckpointReportTest {

    @TempDir
    Path tempDir;

    @Test
    void draftReport_includesAggregatorRow_withRootVersion() throws Exception {
        scaffoldGitWorkspace();

        WsCheckpointDraftMojo mojo = checkpointMojoWithSimulatedBuild();
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "name", "report-cp");
        setField(mojo, "issueRepo", "");  // skip GitHub milestone lookup

        mojo.execute();

        String body = readReport("checkpoint-draft");

        // The table is the shared working-set table, not the old
        // subproject-only one.
        assertThat(body)
                .contains("## Working set")
                .contains("Member")
                .contains("Kind")
                .contains("Effect");

        // Every subproject is a labeled row.
        assertThat(body)
                .contains("lib-a")
                .contains("lib-b")
                .contains("app-c")
                .contains("subproject");

        // #766/#763: the aggregator (workspace root) is a row, labeled — and
        // its POM version (1-SNAPSHOT) is now visible, which the subproject-
        // only table hid. The root dir name is the aggregator member's name.
        assertThat(body)
                .as("the aggregator row exposes the root version the #763 gap hid")
                .contains("aggregator")
                .contains(tempDir.getFileName().toString())
                .contains("1-SNAPSHOT");

        // The aggregator's Effect is the planned commit + tag (draft phrasing).
        assertThat(body)
                .contains("commit + tag `checkpoint/report-cp` (planned)");
    }

    @Test
    void publishReport_aggregatorEffectIsApplied() throws Exception {
        scaffoldGitWorkspace();

        WsCheckpointDraftMojo mojo = checkpointMojoWithSimulatedBuild();
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "name", "publish-report-cp");
        setField(mojo, "issueRepo", "");
        setField(mojo, "publish", true);

        mojo.execute();

        String body = readReport("checkpoint-publish");

        // The aggregator is present, labeled, with its version — and its
        // Effect reads as APPLIED (no `origin` in this hermetic scaffold).
        assertThat(body)
                .contains("## Working set")
                .contains("aggregator")
                .contains(tempDir.getFileName().toString())
                .contains("1-SNAPSHOT")
                .contains("committed + tagged");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Build a git workspace with a root POM + git repo and three git
     * subprojects, so the aggregator row has a real POM version and branch
     * to render. The root has no {@code origin}, matching a hermetic test.
     */
    private void scaffoldGitWorkspace() throws Exception {
        Path noHooks = tempDir.resolve("no-hooks");
        Files.createDirectories(noHooks);

        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                  lib-a:
                    type: software
                    repo: https://example.com/lib-a.git
                    branch: main
                    version: "1.0.0-SNAPSHOT"
                  lib-b:
                    type: software
                    repo: https://example.com/lib-b.git
                    branch: main
                    version: "2.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-a
                        relationship: build
                  app-c:
                    type: software
                    repo: https://example.com/app-c.git
                    branch: main
                    version: "3.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-b
                        relationship: build
                """, StandardCharsets.UTF_8);

        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>checkpoint-report-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);

        // The root repo ignores the subproject working trees, scaffolding
        // dirs, and generated reports — mirroring a real IKE workspace where
        // subprojects are independent repos, not tracked content. Without
        // this the publish preflight (WORKING_TREE_CLEAN) would trip on the
        // untracked subproject directories.
        // Note: checkpoints/ is intentionally NOT ignored — the publish path
        // `git add`s the checkpoint file there, so it must be trackable.
        Files.writeString(tempDir.resolve(".gitignore"), """
                lib-a/
                lib-b/
                app-c/
                no-hooks/
                *.md
                """, StandardCharsets.UTF_8);

        createGitSubproject("lib-a", "1.0.0-SNAPSHOT", null);
        createGitSubproject("lib-b", "2.0.0-SNAPSHOT", "lib-a");
        createGitSubproject("app-c", "3.0.0-SNAPSHOT", "lib-b");

        // Init the workspace root git repo last and commit everything so the
        // aggregator has a branch and a clean tree for the checkpoint.
        exec(tempDir, "git", "init", "-b", "main");
        configureHermetic(tempDir, noHooks);
        exec(tempDir, "git", "add", "workspace.yaml", "pom.xml", ".gitignore");
        exec(tempDir, "git", "commit", "-m", "workspace root");
    }

    private void createGitSubproject(String name, String version,
                                     String dependency) throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);

        StringBuilder pom = new StringBuilder();
        pom.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                """);
        pom.append("    <artifactId>").append(name).append("</artifactId>\n");
        pom.append("    <version>").append(version).append("</version>\n");
        if (dependency != null) {
            pom.append("    <dependencies>\n        <dependency>\n");
            pom.append("            <groupId>com.test</groupId>\n");
            pom.append("            <artifactId>").append(dependency)
               .append("</artifactId>\n");
            pom.append("            <version>1.0.0-SNAPSHOT</version>\n");
            pom.append("        </dependency>\n    </dependencies>\n");
        }
        pom.append("</project>\n");
        Files.writeString(dir.resolve("pom.xml"), pom.toString(),
                StandardCharsets.UTF_8);

        Path noHooks = tempDir.resolve("no-hooks");
        exec(dir, "git", "init", "-b", "main");
        configureHermetic(dir, noHooks);
        exec(dir, "git", "add", ".");
        exec(dir, "git", "commit", "-m", "Initial commit");
    }

    private void configureHermetic(Path dir, Path noHooks) throws Exception {
        exec(dir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
        exec(dir, "git", "config", "commit.gpgsign", "false");
        exec(dir, "git", "config", "tag.gpgsign", "false");
    }

    /**
     * Checkpoint mojo whose per-subproject tagging is a plain local
     * {@code git tag} — no nested Maven build, mirroring
     * {@code WorkspaceMojoIntegrationTest.checkpointMojoWithSimulatedBuild}.
     */
    private static WsCheckpointDraftMojo checkpointMojoWithSimulatedBuild() {
        WsCheckpointDraftMojo mojo = new WsCheckpointDraftMojo() {
            @Override
            protected void checkpointComponent(File dir, String checkpointLabel)
                    throws MojoException {
                network.ike.plugin.ReleaseSupport.exec(dir, getLog(),
                        "git", "tag", "-a", "checkpoint/" + checkpointLabel,
                        "-m", "Simulated checkpoint " + checkpointLabel);
            }
        };
        TestLog.injectInto(mojo);
        return mojo;
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
