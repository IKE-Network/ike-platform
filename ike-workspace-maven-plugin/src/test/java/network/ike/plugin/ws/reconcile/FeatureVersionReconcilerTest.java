package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.TestLog;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FeatureVersionReconciler} (IKE-Network/ike-issues#574):
 * a subproject that tracks a feature branch but whose version isn't
 * branch-qualified gets qualified (POM + workspace.yaml); already-qualified
 * and main-tracking members are left alone; and the parent version is never
 * mistaken for the project version.
 */
class FeatureVersionReconcilerTest {

    private static final String MANIFEST_FEATURE = """
            schema-version: "1.0"
            defaults:
              branch: main
            subprojects:
              komet-claude-plugin:
                repo: https://example.com/kcp.git
                branch: feature/claude-assistant
                version: "1-SNAPSHOT"
                groupId: network.ike.komet
            """;

    private static final String KCP_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>102</version>
                </parent>
                <artifactId>komet-claude-plugin</artifactId>
                <version>1-SNAPSHOT</version>
            </project>
            """;

    private static WorkspaceContext ctx(Path tmp) throws IOException {
        Path manifest = tmp.resolve("workspace.yaml");
        return new WorkspaceContext(
                tmp.toFile(), manifest,
                new WorkspaceGraph(ManifestReader.read(manifest)),
                ReconcilerOptions.empty(), new TestLog());
    }

    private static Path writeWorkspace(Path tmp, String manifest)
            throws IOException {
        Files.writeString(tmp.resolve("workspace.yaml"), manifest,
                StandardCharsets.UTF_8);
        Path dir = tmp.resolve("komet-claude-plugin");
        Files.createDirectories(dir);
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, KCP_POM, StandardCharsets.UTF_8);
        return pom;
    }

    @Test
    void apply_qualifies_unqualified_member(@TempDir Path tmp)
            throws IOException {
        Path pom = writeWorkspace(tmp, MANIFEST_FEATURE);

        new FeatureVersionReconciler().apply(ctx(tmp));

        String pomText = Files.readString(pom);
        assertThat(pomText)
                .contains("<version>1-claude-assistant-SNAPSHOT</version>")
                .contains("<version>102</version>");      // parent untouched
        long plainProjectVersion = pomText.split(
                "<version>1-SNAPSHOT</version>", -1).length - 1;
        assertThat(plainProjectVersion)
                .as("project <version> rewritten away").isZero();
        assertThat(Files.readString(tmp.resolve("workspace.yaml")))
                .contains("version: 1-claude-assistant-SNAPSHOT");
    }

    @Test
    void detect_flags_unqualified_member(@TempDir Path tmp)
            throws IOException {
        writeWorkspace(tmp, MANIFEST_FEATURE);
        DriftReport r = new FeatureVersionReconciler().detect(ctx(tmp));
        assertThat(r.hasDrift()).isTrue();
        assertThat(r.detailLines()).hasSize(1);
        assertThat(r.detailLines().get(0))
                .contains("komet-claude-plugin")
                .contains("1-SNAPSHOT")
                .contains("1-claude-assistant-SNAPSHOT");
    }

    @Test
    void apply_is_idempotent(@TempDir Path tmp) throws IOException {
        Path pom = writeWorkspace(tmp, MANIFEST_FEATURE);
        new FeatureVersionReconciler().apply(ctx(tmp));
        String afterFirst = Files.readString(pom);

        // Re-reading the now-qualified manifest, a second pass is a no-op.
        assertThat(new FeatureVersionReconciler().detect(ctx(tmp)).hasDrift())
                .as("already qualified → no drift").isFalse();
        new FeatureVersionReconciler().apply(ctx(tmp));
        assertThat(Files.readString(pom)).isEqualTo(afterFirst);
    }

    @Test
    void main_branch_is_noop(@TempDir Path tmp) throws IOException {
        String mainManifest = MANIFEST_FEATURE.replace(
                "branch: feature/claude-assistant", "branch: main");
        Path pom = writeWorkspace(tmp, mainManifest);
        String before = Files.readString(pom);
        new FeatureVersionReconciler().apply(ctx(tmp));
        assertThat(Files.readString(pom)).isEqualTo(before);
    }

    @Test
    void rewriteOwnVersion_skips_matching_parent(@TempDir Path tmp)
            throws IOException {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <parent>
                    <artifactId>p</artifactId>
                    <version>1-SNAPSHOT</version>
                  </parent>
                  <artifactId>x</artifactId>
                  <version>1-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);

        FeatureVersionReconciler.rewriteOwnVersion(pom, "1-SNAPSHOT",
                "1-foo-SNAPSHOT");

        String c = Files.readString(pom);
        assertThat(c).contains("<version>1-foo-SNAPSHOT</version>");
        long remainingPlain = c.split("<version>1-SNAPSHOT</version>", -1)
                .length - 1;
        assertThat(remainingPlain)
                .as("only the parent's 1-SNAPSHOT remains").isEqualTo(1);
    }
}
