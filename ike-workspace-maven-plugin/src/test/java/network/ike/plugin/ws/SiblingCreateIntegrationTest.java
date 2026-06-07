package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link SiblingCreateMojo} (ike-issues#207) using real
 * temp workspaces with bare {@code file://} upstreams.
 *
 * <p>{@link TestWorkspaceHelper#buildSiblingScenario()} lays out a primary
 * workspace at {@code <tempDir>/primary} (cloned root + components); the goal
 * produces the sibling at {@code <tempDir>/primary-<feature>}.
 */
class SiblingCreateIntegrationTest {

    private static final List<String> COMPONENTS = List.of("lib-a", "lib-b", "app-c");

    @TempDir
    Path tempDir;

    private Path primary;

    @BeforeEach
    void setUp() throws Exception {
        primary = new TestWorkspaceHelper(tempDir).buildSiblingScenario();
    }

    @Test
    void siblingCreate_producesIsolatedCloneOnFeatureBranch() throws Exception {
        SiblingCreateMojo mojo = TestLog.createMojo(SiblingCreateMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "jira-456";

        mojo.execute();

        Path sibling = tempDir.resolve("primary-jira-456");

        // 1. Expected directory layout: a self-contained clone of the root
        //    plus every component.
        assertThat(sibling).isDirectory();
        assertThat(sibling.resolve("workspace.yaml")).exists();
        assertThat(sibling.resolve("pom.xml")).exists();
        assertThat(sibling.resolve(".git")).isDirectory();
        for (String name : COMPONENTS) {
            assertThat(sibling.resolve(name).resolve(".git")).isDirectory();
        }

        // 2. Root + every component on the feature branch in the sibling.
        assertThat(branch(sibling)).isEqualTo("feature/jira-456");
        for (String name : COMPONENTS) {
            assertThat(branch(sibling.resolve(name))).isEqualTo("feature/jira-456");
        }

        // 3. POM versions are branch-qualified in the sibling.
        for (String name : COMPONENTS) {
            String pom = Files.readString(
                    sibling.resolve(name).resolve("pom.xml"), StandardCharsets.UTF_8);
            assertThat(pom).contains("jira-456").contains("SNAPSHOT");
        }

        // 4. The sibling's workspace.yaml carries the feature branch.
        String yaml = Files.readString(
                sibling.resolve("workspace.yaml"), StandardCharsets.UTF_8);
        assertThat(yaml).contains("branch: feature/jira-456");

        // 5. Each sibling component's origin points at the real upstream — not
        //    the primary's local path (proving --reference did not leak into
        //    the remote, which would break ws:push).
        String originLibA = execCapture(sibling.resolve("lib-a"),
                "git", "remote", "get-url", "origin");
        assertThat(originLibA).contains("upstream-lib-a.git");
        assertThat(originLibA).doesNotContain(File.separator + "primary" + File.separator);

        // 6. The primary is untouched: still on main, versions not qualified.
        assertThat(branch(primary)).isEqualTo("main");
        for (String name : COMPONENTS) {
            assertThat(branch(primary.resolve(name))).isEqualTo("main");
            String pom = Files.readString(
                    primary.resolve(name).resolve("pom.xml"), StandardCharsets.UTF_8);
            assertThat(pom).doesNotContain("jira-456");
        }
    }

    @Test
    void siblingCreate_refusesWhenSiblingDirectoryAlreadyExists() throws Exception {
        Files.createDirectories(tempDir.resolve("primary-dup"));

        SiblingCreateMojo mojo = TestLog.createMojo(SiblingCreateMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "dup";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("already exists");

        // The pre-existing directory is left untouched — nothing was cloned.
        assertThat(tempDir.resolve("primary-dup").resolve("workspace.yaml"))
                .doesNotExist();
    }

    @Test
    void siblingCreate_rejectsFilesystemUnsafeFeatureName() throws Exception {
        SiblingCreateMojo mojo = TestLog.createMojo(SiblingCreateMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "bad/name";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("filesystem-safe");

        // No sibling materialized for the rejected name.
        assertThat(tempDir.resolve("primary-bad")).doesNotExist();
    }

    @Test
    void siblingCreate_skipVersion_branchesWithoutQualifyingVersions()
            throws Exception {
        SiblingCreateMojo mojo = TestLog.createMojo(SiblingCreateMojo.class);
        mojo.manifest = primary.resolve("workspace.yaml").toFile();
        mojo.feature = "docs";
        mojo.skipVersion = true;

        mojo.execute();

        Path sibling = tempDir.resolve("primary-docs");
        for (String name : COMPONENTS) {
            assertThat(branch(sibling.resolve(name))).isEqualTo("feature/docs");
        }
        // Version left at its base value — no branch qualifier applied.
        String libAPom = Files.readString(
                sibling.resolve("lib-a").resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(libAPom).contains("1.0.0-SNAPSHOT").doesNotContain("docs");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String branch(Path repo) throws Exception {
        return execCapture(repo, "git", "rev-parse", "--abbrev-ref", "HEAD");
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
