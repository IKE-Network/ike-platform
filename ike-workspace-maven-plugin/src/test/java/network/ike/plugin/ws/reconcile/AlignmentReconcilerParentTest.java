package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.TestLog;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for IKE-Network/ike-issues#566 — {@code ws:align}
 * parent alignment ({@link AlignmentReconciler}) must re-validate a
 * manifest {@code parent:} edge against the POM's <em>actual</em>
 * {@code <parent>} groupId:artifactId before sourcing a version from
 * it. A stale or mis-derived edge (e.g. an external parent recorded as
 * a same-groupId sibling, #565) must be skipped with a warning — never
 * used to rewrite an unrelated {@code <parent>} version.
 */
class AlignmentReconcilerParentTest {

    @TempDir
    Path tempDir;

    /**
     * The exact #565/#566 scenario: {@code komet-claude-plugin}'s
     * manifest carries a bogus {@code parent: ike-commonmark-attributes}
     * (a same-groupId sibling), while its POM's real parent is the
     * EXTERNAL {@code network.ike.platform:ike-parent:98}. The reconciler
     * must NOT propose rewriting {@code ike-parent} to the sibling's
     * version — it must skip and warn.
     */
    @Test
    void skips_external_parent_recorded_as_same_groupId_sibling()
            throws Exception {
        writeManifest("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  ike-commonmark-attributes:
                    repo: https://example.com/ike-commonmark-attributes.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: network.ike.platform
                  komet-claude-plugin:
                    repo: https://example.com/komet-claude-plugin.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: network.ike.platform
                    parent: ike-commonmark-attributes
                """);
        writePom("ike-commonmark-attributes", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-commonmark-attributes</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);
        writePom("komet-claude-plugin", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>network.ike.platform</groupId>
                        <artifactId>ike-parent</artifactId>
                        <version>98</version>
                    </parent>
                    <artifactId>komet-claude-plugin</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        RecordingLog log = new RecordingLog();
        DriftReport report = new AlignmentReconciler().detect(context(log));

        // The destructive rewrite (parent:ike-parent 98 → 1.0.0-SNAPSHOT)
        // must NOT be proposed.
        assertThat(report.hasDrift())
                .as("an external parent must not drive a version rewrite")
                .isFalse();
        assertThat(report.detailLines())
                .noneMatch(l -> l.contains("ike-parent"));
        assertThat(log.warnings)
                .anyMatch(w -> w.contains("komet-claude-plugin")
                        && w.contains("ike-parent")
                        && w.contains("skipping parent alignment"));
    }

    /**
     * The mapped {@code parent:} subproject exists in the workspace but
     * is NOT the POM's real parent — the {@code dev.ikm.komet} collision
     * ({@code komet} vs {@code komet-bom}). The manifest says
     * {@code parent: komet-bom}, but the POM's parent is produced by
     * {@code komet}. Skip + warn, naming the true producer.
     */
    @Test
    void skips_parent_edge_pointing_at_the_wrong_sibling() throws Exception {
        writeManifest("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  komet:
                    repo: https://example.com/komet.git
                    version: "5.0.0-SNAPSHOT"
                    groupId: dev.ikm.komet
                  komet-bom:
                    repo: https://example.com/komet-bom.git
                    version: "5.0.0-SNAPSHOT"
                    groupId: dev.ikm.komet
                  child:
                    repo: https://example.com/child.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: dev.ikm.komet
                    parent: komet-bom
                """);
        writePom("komet", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.komet</groupId>
                    <artifactId>komet</artifactId>
                    <version>5.0.0-SNAPSHOT</version>
                </project>
                """);
        writePom("komet-bom", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.komet</groupId>
                    <artifactId>komet-bom</artifactId>
                    <version>5.0.0-SNAPSHOT</version>
                </project>
                """);
        writePom("child", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>dev.ikm.komet</groupId>
                        <artifactId>komet</artifactId>
                        <version>4.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        RecordingLog log = new RecordingLog();
        DriftReport report = new AlignmentReconciler().detect(context(log));

        assertThat(report.hasDrift())
                .as("a parent: edge to the wrong sibling must not rewrite")
                .isFalse();
        assertThat(log.warnings)
                .anyMatch(w -> w.contains("child")
                        && w.contains("produced by komet")
                        && w.contains("skipping parent alignment"));
    }

    /**
     * A correct {@code parent:} edge — the named subproject does publish
     * the POM's real {@code <parent>} GA — must still align (no
     * regression from the new guard).
     */
    @Test
    void aligns_a_valid_parent_edge() throws Exception {
        writeManifest("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  parent-lib:
                    repo: https://example.com/parent-lib.git
                    version: "2.0.0-SNAPSHOT"
                    groupId: com.example.parent
                  child:
                    repo: https://example.com/child.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: com.example.parent
                    parent: parent-lib
                """);
        writePom("parent-lib", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.parent</groupId>
                    <artifactId>parent-lib</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                </project>
                """);
        writePom("child", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example.parent</groupId>
                        <artifactId>parent-lib</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        DriftReport report =
                new AlignmentReconciler().detect(context(new RecordingLog()));

        assertThat(report.hasDrift())
                .as("a valid parent edge must still align")
                .isTrue();
        assertThat(report.detailLines())
                .anyMatch(l -> l.contains("parent:parent-lib")
                        && l.contains("1.0.0")
                        && l.contains("2.0.0-SNAPSHOT"));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private WorkspaceContext context(TestLog log) {
        Path manifest = tempDir.resolve("workspace.yaml");
        return new WorkspaceContext(
                tempDir.toFile(),
                manifest,
                new WorkspaceGraph(ManifestReader.read(manifest)),
                ReconcilerOptions.empty(),
                log);
    }

    private void writeManifest(String yaml) throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), yaml,
                StandardCharsets.UTF_8);
    }

    private void writePom(String subproject, String pom) throws Exception {
        Path dir = tempDir.resolve(subproject);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
    }

    /** A {@link TestLog} that captures {@code warn} messages. */
    private static final class RecordingLog extends TestLog {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void warn(CharSequence content) {
            warnings.add(content.toString());
        }
    }
}
