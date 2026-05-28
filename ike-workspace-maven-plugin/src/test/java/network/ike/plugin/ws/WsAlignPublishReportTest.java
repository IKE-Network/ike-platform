package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for IKE-Network/ike-issues#543 — {@code
 * ws:align-publish}'s markdown report must list per-POM version
 * changes (subproject, artifact, before → after) rather than the
 * same one-line "applied across the workspace" sentence for both a
 * 0-file and a 50-file rewrite.
 */
class WsAlignPublishReportTest {

    @TempDir
    Path tempDir;

    @Test
    void publish_withDrift_reportListsPerArtifactChanges() throws Exception {
        TestWorkspaceHelper helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // The helper sets app-c's dependency on lib-b to a hard-coded
        // 1.0.0-SNAPSHOT while lib-b is actually published as
        // 2.0.0-SNAPSHOT — that's the drift the align step rewrites.
        WsAlignPublishMojo mojo = TestLog.createMojo(
                WsAlignPublishMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.scope = "poms";
        mojo.execute();

        String report = readReport("align-publish");

        // The report should explicitly count what was applied rather
        // than emit the same sentence regardless of work done.
        assertThat(report)
                .as("the applied count must appear")
                .contains("applied")
                .contains("change");

        // And it must name the specific drift that got rewritten
        // (app-c's stale lib-b coordinate).
        assertThat(report)
                .as("per-artifact detail must list the rewritten version")
                .contains("app-c")
                .contains("lib-b")
                .contains("→");
    }

    @Test
    void publish_alreadyAligned_reportSaysNothingToApply() throws Exception {
        // Workspace where every version is already consistent — the
        // align-publish run should not pretend it rewrote anything.
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                  solo:
                    type: software
                    repo: https://example.com/solo.git
                    branch: main
                    version: "1.0.0-SNAPSHOT"
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>aligned-ws</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);

        Path soloDir = tempDir.resolve("solo");
        Files.createDirectories(soloDir);
        Files.writeString(soloDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>solo</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);

        WsAlignPublishMojo mojo = TestLog.createMojo(
                WsAlignPublishMojo.class);
        mojo.manifest = tempDir.resolve("workspace.yaml").toFile();
        mojo.scope = "poms";
        mojo.execute();

        String report = readReport("align-publish");
        assertThat(report)
                .as("no-op path must say so explicitly, not fake a result")
                .containsAnyOf("nothing to apply", "already current");
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
}
