package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@code depends-on} re-derivation refuses a drifted
 * checkout as its basis (IKE-Network/ike-issues#968).
 *
 * <p>The incident this guards against: a machine whose checkouts sat
 * on {@code main} while the manifest pinned {@code feature/grpc_plugin}
 * re-derived from the main-content POMs and stripped edges that are
 * real on the declared branch. A drifted checkout is the same
 * epistemic situation as an absent clone — the POM on disk does not
 * represent the manifest's declared state — so its recorded edges must
 * be left alone, with a warning that names the remediation.
 */
class YamlDepsSyncBranchDriftTest {

    @TempDir Path tempDir;
    private final YamlDepsSyncCycleGateTest.RecordingLog log =
            new YamlDepsSyncCycleGateTest.RecordingLog();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:
                """, StandardCharsets.UTF_8);
    }

    @Test
    void matching_branch_derives() throws Exception {
        createLib("lib-a", "com.example.a", null, null);
        createLib("lib-b", "com.example.b", "com.example.a", "lib-a");
        initRepo(tempDir.resolve("lib-a"), "main");
        initRepo(tempDir.resolve("lib-b"), "main");
        addToManifest("lib-a", "com.example.a", null, null,
                "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", null, null,
                "    depends-on: []\n");

        boolean changed = YamlDepsSync.run(tempDir.toFile(), log).changed();

        assertThat(changed).isTrue();
        assertThat(manifest()).contains("- subproject: lib-a");
        assertThat(log.warns).noneMatch(w -> w.contains("#968"));
    }

    @Test
    void drifted_branch_keeps_recorded_edges_and_warns() throws Exception {
        // Manifest declares feature/x for lib-b with a recorded edge to
        // lib-a; the checkout is on main, whose POM has no such
        // dependency. The old behavior removed the edge; drift must
        // keep it and warn with the reconcile remediation.
        createLib("lib-a", "com.example.a", null, null);
        createLib("lib-b", "com.example.b", null, null);
        initRepo(tempDir.resolve("lib-a"), "main");
        initRepo(tempDir.resolve("lib-b"), "main");
        addToManifest("lib-a", "com.example.a", null, null,
                "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", "feature/x", null, """
                    depends-on:
                      - subproject: lib-a
                        relationship: build
                """);

        YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(manifest())
                .as("the feature-branch edge survives the drifted checkout")
                .contains("- subproject: lib-a");
        assertThat(log.warns)
                .anyMatch(w -> w.contains("IKE-Network/ike-issues#968"))
                .anyMatch(w -> w.contains("lib-b")
                        && w.contains("feature/x"))
                .anyMatch(w -> w.contains("ws:reconcile-branches-publish"));
    }

    @Test
    void detached_at_manifest_pin_derives() throws Exception {
        createLib("lib-a", "com.example.a", null, null);
        createLib("lib-b", "com.example.b", "com.example.a", "lib-a");
        initRepo(tempDir.resolve("lib-a"), "main");
        initRepo(tempDir.resolve("lib-b"), "main");
        String pinned = captureGit(tempDir.resolve("lib-b"),
                "rev-parse", "HEAD");
        runGit(tempDir.resolve("lib-b"), "checkout", "--detach", "HEAD");
        addToManifest("lib-a", "com.example.a", null, null,
                "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", "main", pinned,
                "    depends-on: []\n");

        boolean changed = YamlDepsSync.run(tempDir.toFile(), log).changed();

        assertThat(changed).isTrue();
        assertThat(manifest()).contains("- subproject: lib-a");
        assertThat(log.warns).noneMatch(w -> w.contains("#968"));
    }

    @Test
    void detached_off_manifest_pin_is_skipped() throws Exception {
        createLib("lib-a", "com.example.a", null, null);
        createLib("lib-b", "com.example.b", "com.example.a", "lib-a");
        initRepo(tempDir.resolve("lib-a"), "main");
        initRepo(tempDir.resolve("lib-b"), "main");
        runGit(tempDir.resolve("lib-b"), "checkout", "--detach", "HEAD");
        addToManifest("lib-a", "com.example.a", null, null,
                "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", "main",
                "0000000000000000000000000000000000000000",
                "    depends-on: []\n");

        YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(manifest())
                .as("no edge derived from the off-pin checkout")
                .doesNotContain("- subproject: lib-a");
        assertThat(log.warns)
                .anyMatch(w -> w.contains("lib-b")
                        && w.contains("detached at"));
    }

    @Test
    void checkout_without_git_metadata_derives() throws Exception {
        // Branch verification needs a repository; a plain synced tree
        // (pom.xml, no .git) keeps the pre-#968 behavior.
        createLib("lib-a", "com.example.a", null, null);
        createLib("lib-b", "com.example.b", "com.example.a", "lib-a");
        addToManifest("lib-a", "com.example.a", null, null,
                "    depends-on: []\n");
        addToManifest("lib-b", "com.example.b", null, null,
                "    depends-on: []\n");

        boolean changed = YamlDepsSync.run(tempDir.toFile(), log).changed();

        assertThat(changed).isTrue();
        assertThat(manifest()).contains("- subproject: lib-a");
        assertThat(log.warns).noneMatch(w -> w.contains("#968"));
    }

    // ── Sandbox helpers ─────────────────────────────────────────────

    private void createLib(String name, String groupId,
                           String depGroupId, String depArtifact)
            throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        String deps = depGroupId == null ? "" : """
                    <dependencies>
                        <dependency>
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                """.formatted(depGroupId, depArtifact);
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0</version>
                %s</project>
                """.formatted(groupId, name, deps), StandardCharsets.UTF_8);
    }

    private void initRepo(Path dir, String branch) throws Exception {
        runGit(dir, "init", "-b", branch);
        runGit(dir, "config", "user.email", "test@example.org");
        runGit(dir, "config", "user.name", "Drift Test");
        runGit(dir, "config", "commit.gpgsign", "false");
        runGit(dir, "config", "core.hooksPath", "");
        runGit(dir, "add", "pom.xml");
        runGit(dir, "commit", "-m", "initial pom for drift test");
    }

    private void runGit(Path dir, String... args) throws Exception {
        gitOutput(dir, args);
    }

    private String captureGit(Path dir, String... args) throws Exception {
        return gitOutput(dir, args).trim();
    }

    private String gitOutput(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(
                List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(p.waitFor())
                .as("git %s: %s", String.join(" ", args), out)
                .isZero();
        return out;
    }

    private void addToManifest(String name, String groupId, String branch,
                               String sha, String depsBlock)
            throws Exception {
        String entry = "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + (branch == null ? "" : "    branch: " + branch + "\n")
                + (sha == null ? "" : "    sha: \"" + sha + "\"\n")
                + "    groupId: " + groupId + "\n"
                + depsBlock;
        Files.writeString(tempDir.resolve("workspace.yaml"),
                manifest() + entry, StandardCharsets.UTF_8);
    }

    private String manifest() throws Exception {
        return Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
    }
}
