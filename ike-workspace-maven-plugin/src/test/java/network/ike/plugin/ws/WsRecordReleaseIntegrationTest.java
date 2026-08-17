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
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage for {@code ws:record-release-{draft,publish}}
 * (IKE-Network/ike-issues#973) against a real workspace: git-backed
 * subprojects from {@link TestWorkspaceHelper}, a git-backed root with
 * the deny-by-default {@code .gitignore} shape real workspace roots
 * carry, and real {@code v*} tags.
 */
class WsRecordReleaseIntegrationTest {

    @TempDir
    Path tempDir;

    private TestWorkspaceHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = new TestWorkspaceHelper(tempDir);
        helper.buildWorkspace();

        // The root is its own git repo (like every real workspace root),
        // deny-by-default so the nested subproject repos stay untracked.
        // The ws꞉*.md line pre-seeds WorkspaceReport's self-healing
        // gitignore entry so report-writing never dirties the tree.
        Files.writeString(tempDir.resolve(".gitignore"), """
                *
                !.gitignore
                !workspace.yaml
                !releases/
                !releases/**
                ws꞉*.md
                """, StandardCharsets.UTF_8);
        exec(tempDir.toFile(), "git", "init", "-b", "main");
        // Hermetic root, mirroring TestWorkspaceHelper.configureHermetic:
        // neutralize the developer's global hooks and signing so commits
        // succeed in any environment.
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec(tempDir.toFile(), "git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec(tempDir.toFile(), "git", "config", "commit.gpgsign", "false");
        exec(tempDir.toFile(), "git", "config", "tag.gpgsign", "false");
        exec(tempDir.toFile(), "git", "config", "user.email", "test@test");
        exec(tempDir.toFile(), "git", "config", "user.name", "Test");
        exec(tempDir.toFile(), "git", "add", ".");
        exec(tempDir.toFile(), "git", "commit", "-m", "workspace: init");
    }

    @Test
    void publish_pins_member_and_writes_cycle_record() throws Exception {
        File libA = tempDir.resolve("lib-a").toFile();
        exec(libA, "git", "tag", "-a", "v1.0.0", "-m", "release 1.0.0");
        String expectedSha = exec(libA, "git", "rev-list", "-n", "1",
                "v1.0.0").trim();

        WsRecordReleasePublishMojo mojo =
                TestLog.createMojo(WsRecordReleasePublishMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.member = "lib-a";
        mojo.mission = "test-mission-1";
        mojo.execute();

        String manifest = Files.readString(helper.workspaceYaml());
        assertThat(manifest).containsSubsequence(
                "lib-a:",
                "version: \"1.0.0\"",
                "state: tag-aligned",
                "kind: release",
                "tag: v1.0.0",
                "lib-b:");

        Path recordPath = ReleaseRecordFile.pathFor(
                tempDir, "test-mission-1");
        assertThat(recordPath).exists();
        ReleaseRecord record = ReleaseRecordFile.read(recordPath);
        // cycle() is the deprecated bridge for mission() — this module still
        // compiles against the pre-rename tooling until the next foundation
        // cascade adopts it (ike-issues#1038); flip to mission() then.
        assertThat(record.cycle()).isEqualTo("test-mission-1");
        ReleaseRecord.MemberRelease row = record.members().get("lib-a");
        assertThat(row.version()).isEqualTo("1.0.0");
        assertThat(row.tag()).isEqualTo("v1.0.0");
        assertThat(row.sha()).isEqualTo(expectedSha);

        // One root commit swept exactly the two authored files; the
        // tree is clean afterwards.
        assertThat(exec(tempDir.toFile(), "git", "log", "--format=%s"))
                .startsWith("workspace: record release lib-a 1.0.0");
        assertThat(exec(tempDir.toFile(), "git", "status", "--porcelain"))
                .isBlank();
    }

    @Test
    void publish_is_idempotent_and_does_not_commit_twice()
            throws Exception {
        File libA = tempDir.resolve("lib-a").toFile();
        exec(libA, "git", "tag", "-a", "v1.0.0", "-m", "release 1.0.0");

        runPublish("lib-a", "test-mission-1");
        String manifestAfterFirst = Files.readString(helper.workspaceYaml());
        String logAfterFirst = exec(tempDir.toFile(),
                "git", "rev-list", "--count", "HEAD").trim();

        runPublish("lib-a", "test-mission-1");

        assertThat(Files.readString(helper.workspaceYaml()))
                .isEqualTo(manifestAfterFirst);
        assertThat(exec(tempDir.toFile(),
                "git", "rev-list", "--count", "HEAD").trim())
                .as("an unchanged re-record must not mint a commit")
                .isEqualTo(logAfterFirst);
    }

    @Test
    void second_member_appends_to_the_same_cycle_record() throws Exception {
        exec(tempDir.resolve("lib-a").toFile(),
                "git", "tag", "-a", "v1.0.0", "-m", "r");
        exec(tempDir.resolve("lib-b").toFile(),
                "git", "tag", "-a", "v2.0.0", "-m", "r");

        runPublish("lib-a", "test-mission-1");
        runPublish("lib-b", "test-mission-1");

        ReleaseRecord record = ReleaseRecordFile.read(
                ReleaseRecordFile.pathFor(tempDir, "test-mission-1"));
        assertThat(record.members().keySet())
                .containsExactly("lib-a", "lib-b");
        assertThat(record.members().get("lib-b").version())
                .isEqualTo("2.0.0");
    }

    @Test
    void publish_refuses_member_without_release_tag() {
        WsRecordReleasePublishMojo mojo =
                TestLog.createMojo(WsRecordReleasePublishMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.member = "lib-b";
        mojo.mission = "test-mission-1";

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("no v* release tag");
    }

    @Test
    void publish_requires_member_and_cycle() throws Exception {
        exec(tempDir.resolve("lib-a").toFile(),
                "git", "tag", "-a", "v1.0.0", "-m", "r");

        WsRecordReleasePublishMojo noMember =
                TestLog.createMojo(WsRecordReleasePublishMojo.class);
        noMember.manifest = helper.workspaceYaml().toFile();
        assertThatThrownBy(noMember::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("-Dmember");

        WsRecordReleasePublishMojo noCycle =
                TestLog.createMojo(WsRecordReleasePublishMojo.class);
        noCycle.manifest = helper.workspaceYaml().toFile();
        noCycle.member = "lib-a";
        assertThatThrownBy(noCycle::execute)
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("-Dmission");
    }

    @Test
    void draft_lists_unrecorded_candidates_and_modifies_nothing()
            throws Exception {
        exec(tempDir.resolve("lib-a").toFile(),
                "git", "tag", "-a", "v1.0.0", "-m", "r");
        String manifestBefore = Files.readString(helper.workspaceYaml());

        WsRecordReleaseDraftMojo mojo =
                TestLog.createMojo(WsRecordReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.execute();

        assertThat(Files.readString(helper.workspaceYaml()))
                .isEqualTo(manifestBefore);
        String report = readReport("record-release-draft");
        assertThat(report).contains("lib-a — tip tag v1.0.0");
        assertThat(report).contains("record-release-publish");
    }

    @Test
    void draft_previews_the_transition_for_a_member() throws Exception {
        exec(tempDir.resolve("lib-a").toFile(),
                "git", "tag", "-a", "v1.0.0", "-m", "r");
        String manifestBefore = Files.readString(helper.workspaceYaml());

        WsRecordReleaseDraftMojo mojo =
                TestLog.createMojo(WsRecordReleaseDraftMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.member = "lib-a";
        mojo.execute();

        assertThat(Files.readString(helper.workspaceYaml()))
                .isEqualTo(manifestBefore);
        String report = readReport("record-release-draft");
        assertThat(report).contains("tag-aligned");
        assertThat(report).contains("v1.0.0");
        assertThat(report).contains("-Dmember=lib-a");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void runPublish(String member, String mission) throws Exception {
        WsRecordReleasePublishMojo mojo =
                TestLog.createMojo(WsRecordReleasePublishMojo.class);
        mojo.manifest = helper.workspaceYaml().toFile();
        mojo.member = member;
        mojo.mission = mission;
        // Emulate Maven's parameter injection, which applies the
        // inherited publish parameter's defaultValue="false" AFTER
        // construction. The publish subclass must therefore assert
        // publish mode in runGoal(), not its constructor — the platform
        // 152 defect this line pins: without it the harness let a
        // constructor-only flip pass while real Maven ran a draft.
        mojo.publish = false;
        mojo.execute();
    }

    private String readReport(String goalFragment) throws Exception {
        try (Stream<Path> files = Files.list(tempDir)) {
            List<Path> reports = files
                    .filter(p -> p.getFileName().toString()
                            .contains(goalFragment))
                    .toList();
            assertThat(reports)
                    .as("expected a %s report in the workspace root",
                            goalFragment)
                    .isNotEmpty();
            return Files.readString(reports.get(0), StandardCharsets.UTF_8);
        }
    }

    private static String exec(File workDir, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Command failed (" + exit + "): "
                    + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
