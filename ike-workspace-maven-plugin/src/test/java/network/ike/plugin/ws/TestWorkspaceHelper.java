package network.ike.plugin.ws;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates a realistic temp workspace for Mojo integration tests.
 *
 * <p>Builds a {@code workspace.yaml}, subproject directories with
 * initialised git repos and Maven POMs. The workspace has three
 * subprojects forming a linear dependency chain:
 *
 * <pre>
 *   lib-a  (leaf)
 *     ↑
 *   lib-b  (depends on lib-a)
 *     ↑
 *   app-c  (depends on lib-b)
 * </pre>
 */
class TestWorkspaceHelper {

    private final Path root;

    TestWorkspaceHelper(Path tempDir) {
        this.root = tempDir;
    }

    /** Build a complete workspace with 3 subprojects. */
    void buildWorkspace() throws Exception {
        writeWorkspaceYaml();
        createSubproject("lib-a", "1.0.0-SNAPSHOT", null);
        createSubproject("lib-b", "2.0.0-SNAPSHOT", "lib-a");
        createSubproject("app-c", "3.0.0-SNAPSHOT", "lib-b");
    }

    Path workspaceYaml() {
        return root.resolve("workspace.yaml");
    }

    File workspaceRoot() {
        return root.toFile();
    }

    /**
     * Build a workspace with bare upstream repos for init testing.
     *
     * <p>Creates bare repos for each subproject, pushes initial commits
     * to them, and writes workspace.yaml with {@code file://} repo URLs.
     * Does NOT create subproject directories — init will clone them.
     */
    void buildWorkspaceWithUpstreams() throws Exception {
        Path upstreams = root.resolve(".upstreams");
        Files.createDirectories(upstreams);

        String libAUrl = createBareUpstream(upstreams, "lib-a", "1.0.0-SNAPSHOT", null);
        String libBUrl = createBareUpstream(upstreams, "lib-b", "2.0.0-SNAPSHOT", "lib-a");
        String appCUrl = createBareUpstream(upstreams, "app-c", "3.0.0-SNAPSHOT", "lib-b");

        writeWorkspaceYamlWithRepos(libAUrl, libBUrl, appCUrl);
    }

    // ── Internal ────────────────────────────────────────────────────

    private void writeWorkspaceYaml() throws Exception {
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
                    version: "1.0.0-SNAPSHOT"
                  lib-b:
                    type: software
                    description: Library B (depends on A)
                    repo: https://example.com/lib-b.git
                    branch: main
                    version: "2.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-a
                        relationship: build
                  app-c:
                    type: software
                    description: Application C (depends on B)
                    repo: https://example.com/app-c.git
                    branch: main
                    version: "3.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-b
                        relationship: build
                """;
        Files.writeString(workspaceYaml(), yaml, StandardCharsets.UTF_8);
    }

    private void createSubproject(String name, String version,
                                  String dependencyArtifact) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);

        // Write pom.xml
        StringBuilder pom = new StringBuilder();
        pom.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                """);
        pom.append("    <artifactId>").append(name).append("</artifactId>\n");
        pom.append("    <version>").append(version).append("</version>\n");

        if (dependencyArtifact != null) {
            pom.append("    <dependencies>\n");
            pom.append("        <dependency>\n");
            pom.append("            <groupId>com.test</groupId>\n");
            pom.append("            <artifactId>").append(dependencyArtifact)
               .append("</artifactId>\n");
            pom.append("            <version>1.0.0-SNAPSHOT</version>\n");
            pom.append("        </dependency>\n");
            pom.append("    </dependencies>\n");
        }

        pom.append("</project>\n");
        Files.writeString(dir.resolve("pom.xml"), pom.toString(),
                StandardCharsets.UTF_8);

        // Initialise git repo with an initial commit on main
        exec(dir, "git", "init", "-b", "main");
        configureHermetic(dir);
        exec(dir, "git", "add", ".");
        exec(dir, "git", "commit", "-m", "Initial commit");
    }

    private String createBareUpstream(Path upstreams, String name,
                                       String version,
                                       String dependencyArtifact)
            throws Exception {
        // Create bare repo
        Path bare = upstreams.resolve("upstream-" + name + ".git");
        Files.createDirectories(bare);
        exec(bare, "git", "init", "--bare");

        // Create temp working dir, add pom, commit, push
        Path work = upstreams.resolve("work-" + name);
        Files.createDirectories(work);

        // Write pom.xml
        StringBuilder pom = new StringBuilder();
        pom.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                """);
        pom.append("    <artifactId>").append(name).append("</artifactId>\n");
        pom.append("    <version>").append(version).append("</version>\n");

        if (dependencyArtifact != null) {
            pom.append("    <dependencies>\n");
            pom.append("        <dependency>\n");
            pom.append("            <groupId>com.test</groupId>\n");
            pom.append("            <artifactId>").append(dependencyArtifact)
               .append("</artifactId>\n");
            pom.append("            <version>1.0.0-SNAPSHOT</version>\n");
            pom.append("        </dependency>\n");
            pom.append("    </dependencies>\n");
        }

        pom.append("</project>\n");
        Files.writeString(work.resolve("pom.xml"), pom.toString(),
                StandardCharsets.UTF_8);

        exec(work, "git", "init", "-b", "main");
        configureHermetic(work);
        exec(work, "git", "add", ".");
        exec(work, "git", "commit", "-m", "Initial commit");
        exec(work, "git", "remote", "add", "origin",
                bare.toAbsolutePath().toString());
        exec(work, "git", "push", "-u", "origin", "main");

        return bare.toUri().toString();
    }

    private void writeWorkspaceYamlWithRepos(String libAUrl, String libBUrl,
                                              String appCUrl) throws Exception {
        String yaml = """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                  lib-a:
                    type: software
                    description: Shared library A
                    repo: %s
                    branch: main
                    version: "1.0.0-SNAPSHOT"
                  lib-b:
                    type: software
                    description: Library B (depends on A)
                    repo: %s
                    branch: main
                    version: "2.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-a
                        relationship: build
                  app-c:
                    type: software
                    description: Application C (depends on B)
                    repo: %s
                    branch: main
                    version: "3.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-b
                        relationship: build
                """.formatted(libAUrl, libBUrl, appCUrl);
        Files.writeString(workspaceYaml(), yaml, StandardCharsets.UTF_8);
    }

    /**
     * Makes a freshly-initialised sandbox repo hermetic: it ignores any
     * host git config — a global {@code core.hooksPath} with failing hooks
     * (e.g. an AI commit-message hook), {@code commit.gpgsign} with no
     * usable key, etc. — so the mojos' real {@code git} invocations during
     * a test never depend on the developer's or build agent's machine. Same
     * idiom as {@code CheckpointPublishSelfTripTest} /
     * {@code WsCommitPublishMojoTest}; see IKE-Network/ike-issues#560.
     */
    private void configureHermetic(Path dir) throws Exception {
        Path noHooks = Files.createDirectories(root.resolve(".nohooks"));
        exec(dir, "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(dir, "git", "config", "commit.gpgsign", "false");
        exec(dir, "git", "config", "tag.gpgsign", "false");
        exec(dir, "git", "config", "user.email", "test@example.com");
        exec(dir, "git", "config", "user.name", "Test");
        // Ignore local VCS state, mirroring real IKE repos (whose .gitignore
        // lists .ike/vcs-state) instead of leaning on the developer's global
        // core.excludesFile — which a hermetic test fork (GIT_CONFIG_GLOBAL
        // disabled) does not see. Written before the caller's `git add .`,
        // so it is committed and inherited by any clone of the repo.
        if (!Files.exists(dir.resolve(".gitignore"))) {
            Files.writeString(dir.resolve(".gitignore"), ".ike/vcs-state\n",
                    StandardCharsets.UTF_8);
        }
    }

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        // Capture combined output so a failure is self-diagnosing
        // (git's stderr would otherwise be lost).
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command)
                            + (output.isBlank() ? "" : "\n" + output));
        }
    }
}
