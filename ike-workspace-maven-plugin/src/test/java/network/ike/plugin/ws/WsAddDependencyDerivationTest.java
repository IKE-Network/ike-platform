package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for POM-based dependency derivation in {@link WsAddMojo}.
 *
 * <p>Verifies that {@code ws:add} correctly derives inter-subproject
 * dependencies from POM analysis (parent and dependency groupIds)
 * and that bidirectional resolution works regardless of the order
 * in which subprojects are added.
 */
class WsAddDependencyDerivationTest {

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        // Create a minimal workspace scaffold
        writeWorkspaceYaml("""
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                """);

        writePom(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>local.aggregate</groupId>
                    <artifactId>test-ws</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <profiles>
                    </profiles>
                </project>
                """);
    }

    @Test
    void forward_derivation_finds_dependency_by_artifact() throws Exception {
        // Register lib-a (publishes com.example.lib:lib-a)
        createSubprojectDir("lib-a", "com.example.lib", null, null);
        addToManifest("lib-a", "com.example.lib");

        // Create lib-b which depends on com.example.lib:lib-a
        createSubprojectDir("lib-b", "com.example.app", "com.example.lib", "lib-a");

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                tempDir.resolve("lib-b"), "lib-b");

        assertThat(derivedDeps).isEqualTo("lib-a");
    }

    @Test
    void forward_derivation_returns_null_when_no_match() throws Exception {
        // Register lib-a (publishes com.example.lib:lib-a)
        createSubprojectDir("lib-a", "com.example.lib", null, null);
        addToManifest("lib-a", "com.example.lib");

        // Create lib-b with a dependency on com.unrelated:something — no match
        createSubprojectDir("lib-b", "com.example.app", "com.unrelated", "something");

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                tempDir.resolve("lib-b"), "lib-b");

        assertThat(derivedDeps).isNull();
    }

    @Test
    void forward_derivation_matches_parent_artifact() throws Exception {
        // Register parent-pom (publishes com.example.parent:parent-pom)
        createSubprojectDir("parent-pom", "com.example.parent", null, null);
        addToManifest("parent-pom", "com.example.parent");

        // Create child whose <parent> references com.example.parent:parent-pom
        Path childDir = tempDir.resolve("child-lib");
        Files.createDirectories(childDir);
        writePom(childDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example.parent</groupId>
                        <artifactId>parent-pom</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child-lib</artifactId>
                </project>
                """);

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                childDir, "child-lib");

        assertThat(derivedDeps).isEqualTo("parent-pom");
    }

    @Test
    void forward_derivation_skips_self_with_shared_groupId() throws Exception {
        // Two subprojects share groupId com.example.shared
        createSubprojectDir("comp-a", "com.example.shared", null, null);
        addToManifest("comp-a", "com.example.shared");

        // comp-b has same groupId but depends on itself (via its own artifacts)
        // — should NOT create a self-dependency
        createSubprojectDir("comp-b", "com.example.shared", "com.example.shared", "comp-b");
        addToManifest("comp-b", "com.example.shared");

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                tempDir.resolve("comp-b"), "comp-b");

        // Should not include comp-b itself; comp-a publishes comp-a not comp-b
        assertThat(derivedDeps).isNull();
    }

    @Test
    void backward_resolution_updates_existing_subproject() throws Exception {
        // Add lib-b first (depends on com.example.lib, but lib-a isn't registered yet)
        createSubprojectDir("lib-b", "com.example.app", "com.example.lib", "lib-a");
        addToManifest("lib-b", "com.example.app");

        // Now "add" lib-a — backfill should detect that lib-b depends on it
        createSubprojectDir("lib-a", "com.example.lib", null, null);

        String yaml = Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);

        // Verify lib-b currently has no depends-on
        assertThat(yaml).contains("lib-b:");
        assertThat(yaml).doesNotContain("subproject: lib-a");

        // Simulate backfill: add dependency edge to lib-b
        String updated = WsAddMojo.addDependencyEdge(yaml, "lib-b", "lib-a", null);

        assertThat(updated).contains("subproject: lib-a");
        assertThat(updated).contains("relationship: build");
    }

    @Test
    void addDependencyEdge_converts_empty_list() {
        String yaml = """
                subprojects:
                  my-lib:
                    type: software
                    depends-on: []
                """;

        String result = WsAddMojo.addDependencyEdge(yaml, "my-lib", "upstream", null);

        assertThat(result)
                .contains("subproject: upstream")
                .contains("relationship: build")
                .doesNotContain("[]");
    }

    @Test
    void addDependencyEdge_appends_to_existing_list() {
        String yaml = """
                subprojects:
                  my-lib:
                    type: software
                    depends-on:
                      - subproject: existing-dep
                        relationship: build
                """;

        String result = WsAddMojo.addDependencyEdge(yaml, "my-lib", "new-dep", null);

        assertThat(result)
                .contains("subproject: existing-dep")
                .contains("subproject: new-dep");
    }

    // ── #239: BOM imports and plugin coords count as build edges ──

    @Test
    void forward_derivation_finds_dependency_via_bom_import() throws Exception {
        // Register a BOM publisher (publishes com.example.bom:my-bom)
        createSubprojectDir("my-bom", "com.example.bom", null, null);
        addToManifest("my-bom", "com.example.bom");

        // Create a consumer that imports my-bom inside dependencyManagement
        Path consumerDir = tempDir.resolve("my-app");
        Files.createDirectories(consumerDir);
        writePom(consumerDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.app</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.example.bom</groupId>
                                <artifactId>my-bom</artifactId>
                                <version>1.0.0</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                consumerDir, "my-app");

        assertThat(derivedDeps).isEqualTo("my-bom");
    }

    @Test
    void forward_derivation_skips_plain_depMgmt_version_constraint() throws Exception {
        // Register lib-x but consumer only declares it as a version constraint
        // in dependencyManagement (no scope=import, no type=pom). This is just
        // a version pin, not an edge — we should NOT match.
        createSubprojectDir("lib-x", "com.example.lib", null, null);
        addToManifest("lib-x", "com.example.lib");

        Path consumerDir = tempDir.resolve("consumer");
        Files.createDirectories(consumerDir);
        writePom(consumerDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.app</groupId>
                    <artifactId>consumer</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.example.lib</groupId>
                                <artifactId>lib-x</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                consumerDir, "consumer");

        assertThat(derivedDeps).isNull();
    }

    @Test
    void forward_derivation_finds_build_plugin_dependency() throws Exception {
        // Register a plugin publisher
        createSubprojectDir("my-build-plugin", "com.example.tooling", null, null);
        addToManifest("my-build-plugin", "com.example.tooling");

        Path consumerDir = tempDir.resolve("plugin-user");
        Files.createDirectories(consumerDir);
        writePom(consumerDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.app</groupId>
                    <artifactId>plugin-user</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>com.example.tooling</groupId>
                                <artifactId>my-build-plugin</artifactId>
                                <version>1.0.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                consumerDir, "plugin-user");

        assertThat(derivedDeps).isEqualTo("my-build-plugin");
    }

    @Test
    void forward_derivation_finds_dependency_inside_profile() throws Exception {
        // Register lib-y; consumer references it only inside a profile.
        createSubprojectDir("lib-y", "com.example.lib", null, null);
        addToManifest("lib-y", "com.example.lib");

        Path consumerDir = tempDir.resolve("profile-user");
        Files.createDirectories(consumerDir);
        writePom(consumerDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example.app</groupId>
                    <artifactId>profile-user</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <profiles>
                        <profile>
                            <id>extra</id>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example.lib</groupId>
                                    <artifactId>lib-y</artifactId>
                                </dependency>
                            </dependencies>
                        </profile>
                    </profiles>
                </project>
                """);

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                consumerDir, "profile-user");

        assertThat(derivedDeps).isEqualTo("lib-y");
    }

    // ── #240: insertion point honours trailing top-level constructs ──

    @Test
    void insertionPoint_inserts_before_trailing_ide_comment_block() {
        // The default ws:create template ends with a commented-out
        // `# ide:` block. New subprojects must NOT be appended after
        // that block (which would leave the comment trapped between
        // entries and dangle the indented commented lines).
        String yaml = """
                schema-version: "1.0"

                subprojects:
                  # Add subprojects with: mvn ws:add -Drepo=<git-url>

                # Optional: IntelliJ project settings shared across collaborators.
                # ide:
                #   language-level: JDK_25_PREVIEW
                #   jdk-name: "25"
                """;

        int insertAt = WsAddMojo.findSubprojectsBlockInsertionPoint(yaml);

        // Insertion point must land BEFORE the column-0 `# Optional` comment.
        int optionalIdx = yaml.indexOf("# Optional");
        assertThat(insertAt).isPositive().isLessThan(optionalIdx);

        // Inserting an entry there preserves the trailing comment block intact.
        String entry = "\n  newproj:\n    description: x\n    depends-on: []\n";
        String updated = yaml.substring(0, insertAt) + entry
                + yaml.substring(insertAt);

        assertThat(updated)
                .contains("newproj:")
                .contains("# ide:")
                .contains("#   language-level: JDK_25_PREVIEW");
        // The new entry must appear before the # Optional block.
        assertThat(updated.indexOf("newproj:"))
                .isLessThan(updated.indexOf("# Optional"));
    }

    @Test
    void insertionPoint_appends_after_existing_subprojects() {
        String yaml = """
                subprojects:
                  # Add subprojects with: mvn ws:add -Drepo=<git-url>

                  komet-bom:
                    description: existing
                    depends-on: []

                # ide:
                #   language-level: JDK_25_PREVIEW
                """;

        int insertAt = WsAddMojo.findSubprojectsBlockInsertionPoint(yaml);
        String entry = "\n  newproj:\n    description: x\n    depends-on: []\n";
        String updated = yaml.substring(0, insertAt) + entry
                + yaml.substring(insertAt);

        // The new entry sits after the existing komet-bom and before the
        // trailing comment block.
        int kometIdx = updated.indexOf("komet-bom:");
        int newIdx = updated.indexOf("newproj:");
        int ideIdx = updated.indexOf("# ide:");
        assertThat(kometIdx).isLessThan(newIdx);
        assertThat(newIdx).isLessThan(ideIdx);
    }

    @Test
    void insertionPoint_handles_subprojects_at_eof() {
        String yaml = """
                schema-version: "1.0"

                subprojects:
                  # Add subprojects with: mvn ws:add -Drepo=<git-url>
                """;

        int insertAt = WsAddMojo.findSubprojectsBlockInsertionPoint(yaml);
        // No trailing top-level construct — insertion point is end of
        // the last non-blank line of the block (i.e. EOF here).
        assertThat(insertAt).isEqualTo(yaml.length());
    }

    @Test
    void insertionPoint_returns_negative_when_no_subprojects_key() {
        String yaml = """
                schema-version: "1.0"

                defaults:
                  branch: main
                """;

        assertThat(WsAddMojo.findSubprojectsBlockInsertionPoint(yaml))
                .isEqualTo(-1);
    }

    @Test
    void deriveSubprojectName_strips_git_suffix() {
        assertThat(WsAddMojo.deriveSubprojectName(
                "https://github.com/ikmdev/tinkar-core.git"))
                .isEqualTo("tinkar-core");
    }

    @Test
    void deriveSubprojectName_handles_no_suffix() {
        assertThat(WsAddMojo.deriveSubprojectName(
                "https://github.com/ikmdev/tinkar-core"))
                .isEqualTo("tinkar-core");
    }

    @Test
    void deriveSubprojectName_handles_trailing_slash() {
        assertThat(WsAddMojo.deriveSubprojectName(
                "https://github.com/ikmdev/tinkar-core/"))
                .isEqualTo("tinkar-core");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void writeWorkspaceYaml(String content) throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), content,
                StandardCharsets.UTF_8);
    }

    private void writePom(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private void createSubprojectDir(String name, String groupId,
                                     String depGroupId, String depArtifact)
            throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);

        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project>\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n");
        pom.append("    <groupId>").append(groupId).append("</groupId>\n");
        pom.append("    <artifactId>").append(name).append("</artifactId>\n");
        pom.append("    <version>1.0.0-SNAPSHOT</version>\n");

        if (depGroupId != null && depArtifact != null) {
            pom.append("    <dependencies>\n");
            pom.append("        <dependency>\n");
            pom.append("            <groupId>").append(depGroupId).append("</groupId>\n");
            pom.append("            <artifactId>").append(depArtifact).append("</artifactId>\n");
            pom.append("            <version>1.0.0</version>\n");
            pom.append("        </dependency>\n");
            pom.append("    </dependencies>\n");
        }

        pom.append("</project>\n");
        writePom(dir.resolve("pom.xml"), pom.toString());
    }

    private void addToManifest(String name, String groupId) throws Exception {
        String yaml = Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
        String entry = "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + "    groupId: " + groupId + "\n"
                + "    depends-on: []\n";

        yaml = yaml + entry;
        Files.writeString(tempDir.resolve("workspace.yaml"), yaml,
                StandardCharsets.UTF_8);
    }

    /**
     * Invoke the private deriveDependencies method reflectively.
     * Converts List&lt;DerivedDep&gt; result to comma-separated string
     * for assertion simplicity.
     */
    @SuppressWarnings("unchecked")
    private String invokeDeriveForward(Path wsDir, Path manifestPath,
                                        Path subprojectDir, String subprojectName)
            throws Exception {
        WsAddMojo mojo = TestLog.createMojo(WsAddMojo.class);
        var method = WsAddMojo.class.getDeclaredMethod(
                "deriveDependencies", Path.class, Path.class, Path.class, String.class);
        method.setAccessible(true);
        var result = (java.util.List<WsAddMojo.DerivedDep>) method.invoke(
                mojo, wsDir, manifestPath, subprojectDir, subprojectName);
        if (result == null || result.isEmpty()) return null;
        return result.stream()
                .map(WsAddMojo.DerivedDep::subproject)
                .collect(java.util.stream.Collectors.joining(","));
    }
}
