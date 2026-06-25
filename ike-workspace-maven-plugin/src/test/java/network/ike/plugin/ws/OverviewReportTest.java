package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report test for {@code ws:overview} — the canonical #763 fix under
 * epic #764 / issue #767. The Status section migrated from a
 * subproject-only table to the shared {@link WorkingSetReportTable}, so
 * the workspace-root <em>aggregator</em> is now a first-class member row.
 *
 * <p>A subproject-only loop hid the root: when it was left on a stale
 * {@code 1-<feature>-SNAPSHOT} while the subprojects were reset, nothing
 * in the report surfaced it. Iterating {@code resolveWorkingSet().members()}
 * (subprojects THEN the aggregator) and reading the root pom version the
 * same way as a subproject is the fix — and the aggregator row carries the
 * {@code aggregator} kind label, distinct from the {@code subproject} rows.
 */
class OverviewReportTest {

    @TempDir
    Path tempDir;

    @Test
    void overview_statusTable_includesAggregatorRowWithRootPomVersion()
            throws Exception {
        // Three subprojects (lib-a → lib-b → app-c) plus a workspace-root
        // aggregator pom carrying a deliberately distinct version so the
        // #763 surfacing is unambiguous in the assertions.
        TestWorkspaceHelper helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();
        writeAggregatorRoot("1-overview-aggregator-SNAPSHOT");

        OverviewWorkspaceMojo mojo =
                TestLog.createMojo(OverviewWorkspaceMojo.class);
        setField(mojo, "manifest", tempDir.resolve("workspace.yaml").toFile(),
                AbstractWorkspaceMojo.class);
        setField(mojo, "format", "overview");
        // Air-gapped: emit the raw DOT block, no Kroki image URL.
        setField(mojo, "krokiUrl", "");
        mojo.execute();

        String report = readReport("overview");

        // The migrated Status table uses the shared working-set columns —
        // Member · Kind · Version · Branch · SHA · Status (read-only goal).
        assertThat(report)
                .as("working-set table headers replace the old "
                        + "Subproject-keyed columns")
                .contains("Member")
                .contains("Kind")
                .contains("Status");

        // The subprojects are still rows, now labeled by kind.
        assertThat(report)
                .as("subprojects remain rows, labeled subproject")
                .contains("lib-a")
                .contains("lib-b")
                .contains("app-c")
                .contains("subproject");

        // The #763 fix: the workspace-root aggregator is a row, labeled
        // "aggregator", named by the workspace-root directory.
        String rootName = tempDir.getFileName().toString();
        assertThat(report)
                .as("the aggregator (workspace root) appears as a row, "
                        + "labeled aggregator")
                .contains(rootName)
                .contains("aggregator");

        // And — the load-bearing #763 assertion — the root pom's version
        // is surfaced, exactly what the subproject-only table could not do.
        assertThat(report)
                .as("the aggregator row carries the root pom version (#763)")
                .contains("1-overview-aggregator-SNAPSHOT");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Give the workspace root a real aggregator: a {@code pom.xml} with the
     * given version and an initialised git repo, so {@code resolveWorkingSet()}
     * iterates it as a clean, version-bearing aggregator member.
     */
    private void writeAggregatorRoot(String version) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <packaging>pom</packaging>
                </project>
                """.formatted(tempDir.getFileName().toString(), version),
                StandardCharsets.UTF_8);

        exec(tempDir, "git", "init", "-b", "main");
        exec(tempDir, "git", "config", "commit.gpgsign", "false");
        exec(tempDir, "git", "config", "user.email", "root@example.com");
        exec(tempDir, "git", "config", "user.name", "Test Root");
        // Commit only the aggregator pom and manifest; the subproject dirs
        // are their own git repos and must not be absorbed into the root's
        // index (keeps the aggregator's working tree clean for the report).
        Files.writeString(tempDir.resolve(".gitignore"),
                "lib-a/\nlib-b/\napp-c/\nws꞉*.md\n", StandardCharsets.UTF_8);
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
