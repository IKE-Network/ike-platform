package network.ike.plugin.ws.bootstrap;

import network.ike.workspace.ManifestReader;
import network.ike.workspace.Subproject;
import network.ike.workspace.WorkspaceGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generated CLAUDE.md files state the Java version the project actually
 * declares, not a hard-coded one (IKE-Network/ike-issues#1147).
 */
class ClaudeMdJavaVersionTest {

    @TempDir
    Path tempDir;

    private Path pom(String properties) throws IOException {
        Path pom = tempDir.resolve("pom-" + Math.abs(properties.hashCode()) + ".xml");
        Files.writeString(pom, """
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>g</groupId>
                    <artifactId>a</artifactId>
                    <version>1</version>
                    <properties>
                %s
                    </properties>
                </project>
                """.formatted(properties), StandardCharsets.UTF_8);
        return pom;
    }

    private static WorkspaceGraph graph() {
        return new WorkspaceGraph(ManifestReader.read(new StringReader("""
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  lib-a:
                    repo: https://example.com/lib-a.git
                    version: "1.0.0-SNAPSHOT"
                    groupId: com.example
                """)));
    }

    @Test
    void declaredJavaVersion_readsTheProperty() throws IOException {
        assertThat(SubprojectInitializer.declaredJavaVersion(
                pom("        <java.version>27</java.version>").toFile()))
                .contains("27");
    }

    @Test
    void declaredJavaVersion_isEmptyWhenAbsentUnresolvedOrMissing() throws IOException {
        assertThat(SubprojectInitializer.declaredJavaVersion(
                pom("        <other>x</other>").toFile())).isEmpty();
        assertThat(SubprojectInitializer.declaredJavaVersion(
                pom("        <java.version>${jdk.release}</java.version>").toFile())).isEmpty();
        assertThat(SubprojectInitializer.declaredJavaVersion(
                tempDir.resolve("absent.xml").toFile())).isEmpty();
    }

    @Test
    void workspaceClaudeMd_statesTheDeclaredVersion_orOmitsIt() {
        String with = SubprojectInitializer.generateWorkspaceClaudeMd("ws", graph(), Optional.of("27"));
        assertThat(with).contains("- All projects use `--enable-preview` (Java 27)\n");
        assertThat(with).doesNotContain("Java 25");

        String without = SubprojectInitializer.generateWorkspaceClaudeMd("ws", graph(), Optional.empty());
        assertThat(without).contains("- All projects use `--enable-preview`\n");
        assertThat(without).doesNotContain("(Java");
    }

    @Test
    void componentClaudeMd_statesTheDeclaredVersion_orOmitsIt() {
        Subproject lib = graph().manifest().subprojects().get("lib-a");

        String with = SubprojectInitializer.generateComponentClaudeMd(lib, Optional.of("27"));
        assertThat(with).contains("- Uses `--enable-preview` (Java 27)\n");

        String without = SubprojectInitializer.generateComponentClaudeMd(lib, Optional.empty());
        assertThat(without).contains("- Uses `--enable-preview`\n");
        assertThat(without).doesNotContain("(Java");
    }
}
