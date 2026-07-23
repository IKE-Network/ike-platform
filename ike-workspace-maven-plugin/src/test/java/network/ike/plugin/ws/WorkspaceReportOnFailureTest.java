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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for IKE-Network/ike-issues#935 — a {@code ws:*} goal that
 * fails <em>after</em> gathering findings (e.g. {@code ws:scaffold-draft} with a
 * per-subproject failure) must still write its workspace report, so a stale
 * prior report cannot masquerade as the current one. Goals that fail with a
 * plain {@link MojoException} keep the default "no report on failure" contract.
 */
class WorkspaceReportOnFailureTest {

    @TempDir
    Path tempDir;

    @Test
    void failingGoalWithReport_stillWritesReport() throws Exception {
        writeWorkspace();

        FailingReportMojo mojo = new FailingReportMojo();
        TestLog.injectInto(mojo);
        setManifest(mojo, tempDir.resolve("workspace.yaml").toFile());

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(WorkspaceReportException.class)
                .hasMessageContaining("boom");

        Path report = WorkspaceReport.reportPath(tempDir,
                WsGoal.SCAFFOLD_DRAFT.qualified());
        assertThat(Files.exists(report))
                .as("a failing goal must still persist its report (#935)")
                .isTrue();
        assertThat(Files.readString(report, StandardCharsets.UTF_8))
                .as("report reflects the failing run, banner included")
                .contains("Run failed")
                .contains("findings-from-the-failing-run");
    }

    @Test
    void plainMojoException_writesNoReport() throws Exception {
        writeWorkspace();

        AbstractWorkspaceMojo mojo = new AbstractWorkspaceMojo() {
            @Override
            protected WorkspaceReportSpec runGoal() {
                throw new MojoException("plain failure");
            }
        };
        TestLog.injectInto(mojo);
        setManifest(mojo, tempDir.resolve("workspace.yaml").toFile());

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class);

        Path report = WorkspaceReport.reportPath(tempDir,
                WsGoal.SCAFFOLD_DRAFT.qualified());
        assertThat(Files.exists(report))
                .as("a plain throw keeps the default 'no report on failure' contract")
                .isFalse();
    }

    private void writeWorkspace() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.1"
                generated: "2026-07-23"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>local.test</groupId>
                    <artifactId>report-on-failure-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
    }

    private void setManifest(Object mojo, File value) throws Exception {
        Field f = AbstractWorkspaceMojo.class.getDeclaredField("manifest");
        f.setAccessible(true);
        f.set(mojo, value);
    }

    /** A goal that fails after producing a report — the #935 scenario. */
    private static final class FailingReportMojo extends AbstractWorkspaceMojo {
        @Override
        protected WorkspaceReportSpec runGoal() {
            String content = "> ⚠️ **Run failed** — 1 of 1 target(s) failed.\n\n"
                    + "findings-from-the-failing-run\n";
            throw new WorkspaceReportException("boom",
                    new WorkspaceReportSpec(WsGoal.SCAFFOLD_DRAFT, content));
        }
    }
}
