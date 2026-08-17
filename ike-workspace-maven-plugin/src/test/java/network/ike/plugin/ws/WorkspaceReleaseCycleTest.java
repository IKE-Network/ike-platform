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
        root = repoWithManifest("wsr-root", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>wsr-root</artifactId>
                    <version>1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """);
        libA = repo("wsr-root/lib-a", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>lib-a</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);
        libB = repo("wsr-root/lib-b", """
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
                "mvnw -B -ntp clean install -P release"
                        + " -Dike.workspace.release=true -T 1 -DskipTests");
        assertThat(ranCommands.get(1)).isEqualTo(
                "mvnw -B -ntp deploy -pl :lib-a,:lib-b,:wsr-root"
                        + " -P release,signArtifacts"
                        + " -Dike.workspace.release=true -T 1 -DskipTests");

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

    /**
     * A multi-module member: the version pass must move the versions
     * its sub-modules spell out, not just the repository root's. This
     * is tinkar-core's real shape — a module parenting to an
     * aggregator inside the same repository — which stranded one
     * module at {@code -SNAPSHOT} while its siblings released, and
     * broke the reactor 45 modules in (ike-issues#1011).
     */
    @Test
    void sub_modules_naming_their_own_version_move_with_the_repository()
            throws Exception {
        // An intermediate aggregator, and a leaf parenting to it —
        // both spelling out lib-a's version.
        Path group = Files.createDirectories(libA.toPath().resolve("group"));
        Files.writeString(group.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>group</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """, StandardCharsets.UTF_8);
        Path leaf = Files.createDirectories(group.resolve("leaf"));
        Files.writeString(leaf.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>group</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>leaf</artifactId>
                </project>
                """, StandardCharsets.UTF_8);
        git(libA, "add", ".");
        git(libA, "commit", "-m", "add modules");

        cycle("multi-module").execute("mvnw", true);

        // Released tree: no module left behind at the old version.
        String groupAtTag = git(libA, "show", "v1.0.0:group/pom.xml");
        assertThat(groupAtTag).contains("<version>1.0.0</version>")
                .doesNotContain("1.0.0-SNAPSHOT");
        assertThat(git(libA, "show", "v1.0.0:group/leaf/pom.xml"))
                .contains("<version>1.0.0</version>")
                .doesNotContain("1.0.0-SNAPSHOT");

        // And the post-bump carries them forward too.
        assertThat(Files.readString(group.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .contains("<version>1.0.1-SNAPSHOT</version>");
        assertThat(Files.readString(leaf.resolve("pom.xml"),
                StandardCharsets.UTF_8))
                .contains("<version>1.0.1-SNAPSHOT</version>");
    }

    /** A module inheriting its version must keep declaring none. */
    @Test
    void inherited_module_versions_are_left_alone() throws Exception {
        Path child = Files.createDirectories(libA.toPath().resolve("child"));
        Files.writeString(child.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
                        <relativePath>../pom.xml</relativePath>
                    </parent>
                    <artifactId>child</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.external</groupId>
                            <artifactId>unrelated</artifactId>
                            <version>1.0.0-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """, StandardCharsets.UTF_8);
        git(libA, "add", ".");
        git(libA, "commit", "-m", "add child");

        cycle("inheritance").execute("mvnw", true);

        String released = git(libA, "show", "v1.0.0:child/pom.xml");

        // The parent reference — this repository's own coordinate — moved.
        String parentBlock = released.substring(released.indexOf("<parent>"),
                released.indexOf("</parent>"));
        assertThat(parentBlock).contains("lib-a").contains("1.0.0")
                .doesNotContain("SNAPSHOT");

        // The unrelated external dependency carries the same version
        // string and a different coordinate: it must be untouched.
        String dependencyBlock =
                released.substring(released.indexOf("<dependencies>"));
        assertThat(dependencyBlock).contains("unrelated")
                .contains("1.0.0-SNAPSHOT");

        // And the module still declares no version of its own.
        assertThat(released.substring(released.indexOf("</parent>")))
                .doesNotContain("<version>1.0.0</version>");
    }

    /**
     * The manifest carried in the tagged root tree must pin every
     * member at the commit that was released. Before this, a release
     * tag carried released versions beside checkpoint-era commits, and
     * the installer chain — which materialises members from these pins
     — built a tree that never existed as a release
     * (IKE-Network/ike-issues#1017).
     */
    @Test
    void the_tagged_manifest_pins_the_released_commits() throws Exception {
        cycle("pins").execute("mvnw", true);

        String manifest = git(root, "show", "v1:workspace.yaml");
        String libAReleaseSha = git(libA, "rev-parse", "v1.0.0^{commit}");
        String libBReleaseSha = git(libB, "rev-parse", "v2.0.0^{commit}");

        assertThat(manifest).contains(libAReleaseSha)
                .contains(libBReleaseSha)
                // the stale placeholders are gone
                .doesNotContain("0000000000000000000000000000000000000000")
                .doesNotContain("1111111111111111111111111111111111111111");
    }

    /**
     * The root's version pass must not reach into its members. They
     * live inside its directory but are separate repositories with
     * their own release lines; a member whose version string happened
     * to match the root's was rewritten as though it were one of the
     * root's modules, leaving five members with uncommitted version
     * changes after cycle 2 (IKE-Network/ike-issues#1011 follow-up).
     */
    @Test
    void the_roots_version_pass_stops_at_a_members_repository()
            throws Exception {
        // A member sharing the root's exact version string — the case
        // that actually happened.
        File twin = repo("wsr-root/twin", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>twin</artifactId>
                    <version>1-SNAPSHOT</version>
                </project>
                """);
        String before = Files.readString(twin.toPath().resolve("pom.xml"),
                StandardCharsets.UTF_8);

        cycle("boundary").execute("mvnw", true);

        // Untouched: not released, not rewritten, nothing uncommitted.
        assertThat(Files.readString(twin.toPath().resolve("pom.xml"),
                StandardCharsets.UTF_8)).isEqualTo(before);
        assertThat(git(twin, "status", "--porcelain")).isEmpty();
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


    /**
     * The cycle's what-changed notes (ike-issues#1016): committed into
     * the tagged root tree beside the record; per-member sections carry
     * the non-mechanical commits since the member's previous release
     * tag (bodies indented, trailers dropped); a member whose only
     * movement is the cascade lands on the alignment-only line; a
     * member with no previous release tag is noted as a first release.
     */
    @Test
    void notes_carry_member_commits_and_filter_mechanical()
            throws Exception {
        // lib-a: previous release v0.9.0, then one feature commit (with
        // body and trailer) and one goal-authored hygiene commit.
        git(libA, "tag", "v0.9.0");
        Files.writeString(libA.toPath().resolve("flight.txt"), "wings",
                StandardCharsets.UTF_8);
        git(libA, "add", ".");
        git(libA, "commit", "-m", "Teach lib-a to fly\n\n"
                + "Wings are load-bearing.\n\n"
                + "Refs: IKE-Network/ike-issues#1");
        Files.writeString(libA.toPath().resolve("aligned.txt"), "x",
                StandardCharsets.UTF_8);
        git(libA, "add", ".");
        git(libA, "commit", "-m",
                "workspace: align inter-subproject versions");
        // lib-b: previously released, nothing since — pure cascade.
        git(libB, "tag", "v1.9.0");

        cycle("notes-cycle").execute("mvnw", true);

        String notes = git(root, "show",
                "v1:releases/release-notes-cycle-notes.md");
        assertThat(notes)
                .contains("# What changed — cycle notes-cycle")
                .contains("## lib-a 1.0.0  ·  v0.9.0 → v1.0.0")
                .contains("- **Teach lib-a to fly**")
                .contains("\n  Wings are load-bearing.")
                .doesNotContain("Refs:")
                .doesNotContain("workspace: align")
                .contains("Moved for version alignment only: lib-b 2.0.0.")
                .contains("## (workspace root) 1")
                .contains("- _First release of this member._");
    }

    /** Cadence, hygiene, trailers, and blanks never reach the notes. */
    @Test
    void notes_formatting_filters_mechanical_and_trailers() {
        String raw = String.join("\u001e",
                "release: set version to 1.0.0",
                "checkpoint: pre-release safety checkpoint x",
                "chore: align upstream versions before release",
                "post-release: sync workspace.yaml versions (#371)",
                "Fix the flux capacitor\u001fNeeds 1.21 gigawatts.\n\n"
                        + "Fixes: IKE-Network/ike-issues#88\n"
                        + "Co-Authored-By: Doc <doc@test>");
        assertThat(WorkspaceReleaseCycle.formatNotesEntries(raw))
                .containsExactly("- **Fix the flux capacitor**"
                        + "\n  Needs 1.21 gigawatts.");
    }

    /** Bodiless commits format as a bare bullet; empty input is empty. */
    @Test
    void notes_formatting_handles_bodiless_and_empty() {
        assertThat(WorkspaceReleaseCycle.formatNotesEntries(
                "Add a knob"))
                .containsExactly("- **Add a knob**");
        assertThat(WorkspaceReleaseCycle.formatNotesEntries("")).isEmpty();
        assertThat(WorkspaceReleaseCycle.formatNotesEntries(null)).isEmpty();
    }

    /**
     * The workspace root, with the manifest the cycle pins into. The
     * pins start deliberately stale — a checkpoint-era value — so a test
     * can tell "the cycle wrote them" from "they happened to be right".
     */
    private File repoWithManifest(String name, String pomXml) throws Exception {
        File f = repo(name, pomXml);
        Files.writeString(f.toPath().resolve("workspace.yaml"), """
                subprojects:
                  lib-a:
                    repo: https://example.invalid/lib-a.git
                    branch: main
                    version: "0.0.1"
                    sha: "0000000000000000000000000000000000000000"
                  lib-b:
                    repo: https://example.invalid/lib-b.git
                    branch: main
                    version: "0.0.1"
                    sha: "1111111111111111111111111111111111111111"
                """, StandardCharsets.UTF_8);
        git(f, "add", "workspace.yaml");
        git(f, "commit", "-m", "add manifest");
        return f;
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
        String bareName = name.substring(name.lastIndexOf('/') + 1);
        Path bare = bares.resolve(bareName + ".git");
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
