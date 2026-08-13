package network.ike.plugin.ws;

import network.ike.workspace.ReleaseRecord;
import network.ike.workspace.ReleaseRecordFile;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage for {@link WorkspaceReleaseCycle} — the
 * reactor-pass release (ike-issues#997) — against real git
 * repositories with bare {@code file://} origins. The two reactor
 * builds go through a recording {@link WorkspaceReleaseCycle.Runner},
 * so the exact command lines are asserted without executing Maven
 * (the bare-mode release tests' house pattern); every git effect —
 * commits, tags, pushes, the cycle record — is real.
 */
class WorkspaceReleaseCycleTest {

    @TempDir
    Path tempDir;

    private File root;
    private File libA;
    private File libB;
    private Path bares;
    private final List<String> ranCommands = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        bares = Files.createDirectories(tempDir.resolve("bares"));
        root = repo("wsr-root", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>wsr-root</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """);
        libA = repo("lib-a", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>lib-a</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);
        libB = repo("lib-b", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>lib-b</artifactId>
                    <version>2.0.0-SNAPSHOT</version>
                    <properties>
                        <lib-a.version>1.0.0-SNAPSHOT</lib-a.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.test</groupId>
                            <artifactId>lib-a</artifactId>
                            <version>${lib-a.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
    }

    @Test
    void full_cycle_versions_tags_records_bumps_and_pushes()
            throws Exception {
        WorkspaceReleaseCycle cycle = cycle("test-cycle-1");

        Map<String, String> released = cycle.execute("mvnw", true);

        // Released identities, root included.
        assertThat(released).containsExactly(
                Map.entry("lib-a", "1.0.0"),
                Map.entry("lib-b", "2.0.0"),
                Map.entry("(workspace root)", "1"));

        // Exactly two reactor builds: one verify, one scoped deploy.
        assertThat(ranCommands).hasSize(2);
        assertThat(ranCommands.get(0)).isEqualTo(
                "mvnw -B -ntp clean install -P release -T 1 -DskipTests");
        assertThat(ranCommands.get(1)).isEqualTo(
                "mvnw -B -ntp deploy -pl :lib-a,:lib-b,:wsr-root"
                        + " -P release,signArtifacts -T 1 -DskipTests");

        // Working trees post-bumped; reference settled at the release.
        assertThat(pom(libA)).contains("<version>1.0.1-SNAPSHOT</version>");
        assertThat(pom(libB)).contains("<version>2.0.1-SNAPSHOT</version>")
                .contains("<lib-a.version>1.0.0</lib-a.version>");

        // Tags exist and their trees carry release versions.
        assertThat(git(libA, "tag", "-l", "v1.0.0")).contains("v1.0.0");
        assertThat(git(libA, "show", "v1.0.0:pom.xml"))
                .contains("<version>1.0.0</version>");
        assertThat(git(libB, "show", "v2.0.0:pom.xml"))
                .contains("<lib-a.version>1.0.0</lib-a.version>");
        assertThat(git(root, "show", "v1:pom.xml"))
                .contains("<version>1</version>");

        // The tagged root tree carries the cycle record; rows are real.
        assertThat(git(root, "show",
                "v1:releases/release-test-cycle-1.yaml"))
                .contains("lib-a").contains("\"1.0.0\"");
        ReleaseRecord record = ReleaseRecordFile.read(
                ReleaseRecordFile.pathFor(root.toPath(), "test-cycle-1"));
        assertThat(record.members().keySet())
                .containsExactly("lib-a", "lib-b", "(workspace root)");
        assertThat(record.members().get("lib-b").tag()).isEqualTo("v2.0.0");
        assertThat(record.members().get("lib-a").sha()).hasSize(40);

        // Commit subjects are release-cadence forms.
        assertThat(git(libA, "log", "--format=%s", "-2")).isEqualTo(
                "post-release: bump to 1.0.1-SNAPSHOT\n"
                        + "release: set version to 1.0.0");

        // Branch and tag pushed to the bare origin.
        File bareA = bares.resolve("lib-a.git").toFile();
        assertThat(git(bareA, "tag", "-l", "v1.0.0")).contains("v1.0.0");
        assertThat(git(bareA, "log", "--format=%s", "-1", "main"))
                .startsWith("post-release: bump to 1.0.1-SNAPSHOT");
    }

    @Test
    void in_flight_cycle_is_refused() {
        WorkspaceReleaseCycle cycle = new WorkspaceReleaseCycle(root,
                List.of(new WorkspaceReleaseCycle.ReleasingRepo("lib-a",
                        libA, "lib-a", "1.0.0", "1.0.0", "1.0.1-SNAPSHOT")),
                rootRepo(), emptyPlan(), "c1", "2026-08-12",
                ReleaseTagStyle.V_PREFIXED, new TestLog(), recorder());
        assertThatThrownBy(() -> cycle.execute("mvnw", true))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("In-flight release cycle");
    }

    @Test
    void existing_release_tag_is_refused() throws Exception {
        git(libA, "tag", "-a", "v1.0.0", "-m", "stale");
        assertThatThrownBy(() -> cycle("c1").execute("mvnw", true))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("v1.0.0 already exists");
    }

    // ── Fixture ──────────────────────────────────────────────────────

    private WorkspaceReleaseCycle cycle(String label) {
        List<WorkspaceReleaseCycle.ReleasingRepo> members = List.of(
                new WorkspaceReleaseCycle.ReleasingRepo("lib-a", libA,
                        "lib-a", "1.0.0-SNAPSHOT", "1.0.0",
                        "1.0.1-SNAPSHOT"),
                new WorkspaceReleaseCycle.ReleasingRepo("lib-b", libB,
                        "lib-b", "2.0.0-SNAPSHOT", "2.0.0",
                        "2.0.1-SNAPSHOT"));
        return new WorkspaceReleaseCycle(root, members, rootRepo(),
                plan(), label, "2026-08-12", ReleaseTagStyle.V_PREFIXED,
                new TestLog(), recorder());
    }

    private WorkspaceReleaseCycle.ReleasingRepo rootRepo() {
        return new WorkspaceReleaseCycle.ReleasingRepo("(workspace root)",
                root, "wsr-root", "1-SNAPSHOT", "1", "2-SNAPSHOT");
    }

    private WorkspaceReleaseCycle.Runner recorder() {
        return (dir, command) -> ranCommands.add(String.join(" ", command));
    }

    /** Plan: lib-b tracks lib-a through the lib-a.version property. */
    private ReleasePlan plan() {
        Path libBPom = libB.toPath().resolve("pom.xml");
        SequencedMap<ReleasePlan.GA, ReleasePlan.ArtifactReleasePlan> arts =
                new LinkedHashMap<>();
        ReleasePlan.GA gaA = new ReleasePlan.GA("com.test", "lib-a");
        arts.put(gaA, new ReleasePlan.ArtifactReleasePlan(gaA, "lib-a",
                libA.toPath().resolve("pom.xml"), "1.0.0-SNAPSHOT",
                "1.0.0", "1.0.1-SNAPSHOT",
                List.of(new ReleasePlan.ReferenceSite(libBPom,
                        ReleasePlan.ReferenceKind.DEPENDENCY, gaA,
                        "${lib-a.version}"))));
        List<ReleasePlan.PropertyReleasePlan> props = List.of(
                new ReleasePlan.PropertyReleasePlan("lib-a.version",
                        libBPom, "lib-b", "1.0.0-SNAPSHOT", "1.0.0",
                        "1.0.0", List.of()));
        return new ReleasePlan(arts, props);
    }

    private ReleasePlan emptyPlan() {
        return new ReleasePlan(new LinkedHashMap<>(), List.of());
    }

    private File repo(String name, String pomXml) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(dir.resolve("pom.xml"), pomXml,
                StandardCharsets.UTF_8);
        File f = dir.toFile();
        git(f, "init", "-b", "main");
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        git(f, "config", "core.hooksPath", noHooks.toString());
        git(f, "config", "commit.gpgsign", "false");
        git(f, "config", "tag.gpgsign", "false");
        git(f, "config", "user.email", "test@test");
        git(f, "config", "user.name", "Test");
        git(f, "add", ".");
        git(f, "commit", "-m", "init");
        Path bare = bares.resolve(name + ".git");
        exec(bares.toFile(), "git", "init", "--bare", "-b", "main",
                bare.toString());
        git(f, "remote", "add", "origin", bare.toString());
        git(f, "push", "-u", "origin", "main");
        return f;
    }

    private String pom(File dir) throws Exception {
        return Files.readString(dir.toPath().resolve("pom.xml"),
                StandardCharsets.UTF_8);
    }

    private String git(File dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return exec(dir, cmd);
    }

    private String exec(File dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(dir)
                .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command)
                    + " failed:\n" + out);
        }
        return out.trim();
    }
}
