package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.TestLog;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * State-aware alignment (IKE-Network/ike-issues#972): a tag-aligned
 * member's consumers align to its manifest-pinned released version, not
 * its on-disk POM (which has post-bumped past the release); snapshot-
 * aligned members keep POM truth. The tag-aligned manifest invariant
 * (version + tag both present) fails the pass when violated.
 */
class AlignmentReconcilerStateTest {

    @TempDir
    Path tempDir;

    /**
     * The mid-train shape: {@code lib} released 2.0.0 and was recorded
     * tag-aligned; its POM has post-bumped to 2.0.1-SNAPSHOT. The
     * consumer's direct dependency must align to the pin — not the
     * post-bump SNAPSHOT — and the draft line carries the pin
     * annotation.
     */
    @Test
    void tag_aligned_member_aligns_consumers_to_pin_not_pom()
            throws Exception {
        writeManifest("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  lib:
                    repo: https://example.com/lib.git
                    version: "2.0.0"
                    state: tag-aligned
                    kind: release
                    tag: v2.0.0
                    groupId: com.example
                  consumer:
                    repo: https://example.com/consumer.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: com.example
                """);
        writePom("lib", pom("com.example", "lib", "2.0.1-SNAPSHOT"));
        writePom("consumer", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>consumer</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>2.0.0-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        DriftReport report = new AlignmentReconciler().detect(context());

        assertThat(report.hasDrift()).isTrue();
        assertThat(report.detailLines())
                .anyMatch(l -> l.contains("consumer")
                        && l.contains("com.example:lib")
                        && l.contains("2.0.0-SNAPSHOT → 2.0.0")
                        && l.contains("(pinned v2.0.0)"));
        // The pin target is the manifest version — never the post-bump POM.
        assertThat(report.detailLines())
                .noneMatch(l -> l.contains("2.0.1-SNAPSHOT"));
    }

    /**
     * Snapshot-aligned members keep today's behavior byte-for-byte:
     * consumers track the member's current POM version, no pin
     * annotation.
     */
    @Test
    void snapshot_member_still_aligns_to_pom_truth() throws Exception {
        writeManifest("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  lib:
                    repo: https://example.com/lib.git
                    version: "2.0.1-SNAPSHOT"
                    groupId: com.example
                  consumer:
                    repo: https://example.com/consumer.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: com.example
                """);
        writePom("lib", pom("com.example", "lib", "2.0.1-SNAPSHOT"));
        writePom("consumer", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>consumer</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>2.0.0-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        DriftReport report = new AlignmentReconciler().detect(context());

        assertThat(report.hasDrift()).isTrue();
        assertThat(report.detailLines())
                .anyMatch(l -> l.contains("2.0.0-SNAPSHOT → 2.0.1-SNAPSHOT"));
        assertThat(report.detailLines())
                .noneMatch(l -> l.contains("pinned"));
    }

    /**
     * Property-routed references ({@code ${lib.version}}) align to the
     * pin the same way the direct form does.
     */
    @Test
    void tag_aligned_property_reference_aligns_to_pin() throws Exception {
        writeManifest("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  lib:
                    repo: https://example.com/lib.git
                    version: "2.0.0"
                    state: tag-aligned
                    kind: release
                    tag: v2.0.0
                    groupId: com.example
                  consumer:
                    repo: https://example.com/consumer.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: com.example
                """);
        writePom("lib", pom("com.example", "lib", "2.0.1-SNAPSHOT"));
        writePom("consumer", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>consumer</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <properties>
                        <lib.version>2.0.0-SNAPSHOT</lib.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>${lib.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        DriftReport report = new AlignmentReconciler().detect(context());

        assertThat(report.detailLines())
                .anyMatch(l -> l.contains("property:lib.version")
                        && l.contains("2.0.0-SNAPSHOT → 2.0.0")
                        && l.contains("(pinned v2.0.0)"));
    }

    /**
     * The parent path sources the manifest version field directly, which
     * for a tag-aligned parent IS the pin — the change aligns the child
     * {@code <parent>} to it and carries the annotation.
     */
    @Test
    void tag_aligned_parent_aligns_child_to_pin() throws Exception {
        writeManifest("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  parent-lib:
                    repo: https://example.com/parent-lib.git
                    version: "2.0.0"
                    state: tag-aligned
                    kind: release
                    tag: v2.0.0
                    groupId: com.example.parent
                  child:
                    repo: https://example.com/child.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: com.example.parent
                    parent: parent-lib
                """);
        writePom("parent-lib",
                pom("com.example.parent", "parent-lib", "2.0.1-SNAPSHOT"));
        writePom("child", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example.parent</groupId>
                        <artifactId>parent-lib</artifactId>
                        <version>1.9.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        DriftReport report = new AlignmentReconciler().detect(context());

        assertThat(report.detailLines())
                .anyMatch(l -> l.contains("parent:parent-lib")
                        && l.contains("1.9.0 → 2.0.0")
                        && l.contains("(pinned v2.0.0)"));
    }

    /**
     * The tag-aligned invariant: version and tag must both be present.
     * A violation means the manifest is corrupt — the pass fails with
     * the remediation command rather than aligning to a wrong version.
     */
    @Test
    void tag_aligned_without_tag_fails_the_pass() throws Exception {
        writeManifest("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  lib:
                    repo: https://example.com/lib.git
                    version: "2.0.0"
                    state: tag-aligned
                    kind: release
                    groupId: com.example
                  consumer:
                    repo: https://example.com/consumer.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: com.example
                """);
        writePom("lib", pom("com.example", "lib", "2.0.1-SNAPSHOT"));
        writePom("consumer", pom("com.example", "consumer", "1.0.0-SNAPSHOT"));

        assertThatThrownBy(() ->
                new AlignmentReconciler().detect(context()))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("lib")
                .hasMessageContaining("record-release-publish");
    }

    // ── Harness (mirrors AlignmentReconcilerParentTest) ─────────────

    private WorkspaceContext context() {
        Path manifest = tempDir.resolve("workspace.yaml");
        return new WorkspaceContext(
                tempDir.toFile(),
                manifest,
                new WorkspaceGraph(ManifestReader.read(manifest)),
                ReconcilerOptions.empty(),
                new TestLog());
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

    private static String pom(String groupId, String artifactId,
                              String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version);
    }
}
