package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@code depends-on} re-derivation writes each member's edges
 * into that member's own section of the manifest
 * (IKE-Network/ike-issues#1387).
 *
 * <p>The reported failure ({@code solor-kb-ws}, 2026-11-15): a member with
 * no {@code depends-on:} key matched the block of a later member, so the
 * derived edges of every such member were merged, one over the other,
 * into that one block. A member added with no dependencies was recorded
 * with six build edges that belong to another member, and the members
 * that have dependencies were still recorded with none. The member's name
 * was also matched anywhere in the text, so {@code gudid-kb:} matched
 * inside {@code solor-gudid-kb:}.
 *
 * <p>Every fixture is a real manifest and real POMs on disk; the
 * assertions read a member's section back from the written manifest with
 * a line scanner of their own, independent of the code under test.
 */
class YamlDepsSyncMemberSectionTest {

    private static final String GROUP_ID = "com.example.kb";

    /** A block as the merge leaves it when a member has no edges. */
    private static final String MANAGED_EMPTY_BLOCK =
            DependsOnMerge.MARKER + "\n    depends-on: []\n";

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

    // ── A manifest in which no member has a block ────────────────

    @Test
    void manifest_without_any_block_gains_one_per_member_holding_its_own_edges()
            throws Exception {
        createLib("base-kb");
        createLib("mid-kb", "base-kb");
        createLib("top-kb", "base-kb", "mid-kb");
        addToManifest("base-kb", "");
        addToManifest("mid-kb", "");
        addToManifest("top-kb", "");

        YamlDepsSync.SyncResult result =
                YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(result.changed())
                .as("an older manifest gains its derived edges")
                .isTrue();
        assertThat(section("base-kb")).endsWith(MANAGED_EMPTY_BLOCK);
        assertThat(section("mid-kb")).endsWith(DependsOnMerge.MARKER + "\n"
                + "    depends-on:\n"
                + "      - subproject: base-kb\n"
                + "        relationship: build\n");
        assertThat(section("top-kb")).endsWith(DependsOnMerge.MARKER + "\n"
                + "    depends-on:\n"
                + "      - subproject: base-kb\n"
                + "        relationship: build\n"
                + "      - subproject: mid-kb\n"
                + "        relationship: build\n");
        assertThat(occurrences(manifest(), "    depends-on:"))
                .as("one block per member, none duplicated")
                .isEqualTo(3);
        assertThat(log.errors).isEmpty();
    }

    @Test
    void created_blocks_are_stable_on_a_second_run() throws Exception {
        createLib("base-kb");
        createLib("mid-kb", "base-kb");
        addToManifest("base-kb", "");
        addToManifest("mid-kb", "");

        assertThat(YamlDepsSync.run(tempDir.toFile(), log).changed())
                .isTrue();
        String afterFirst = manifest();

        assertThat(YamlDepsSync.run(tempDir.toFile(), log).changed())
                .as("second run makes no further change")
                .isFalse();
        assertThat(manifest()).isEqualTo(afterFirst);
    }

    @Test
    void block_is_created_at_the_end_of_the_members_own_fields()
            throws Exception {
        // The shape of a generated manifest: a blank line between
        // members, a folded description, and a top-level construct
        // after the last member. The block belongs directly under the
        // member's last field — before the blank line, and inside
        // subprojects: for the last member. A description line that
        // happens to open with the key's text is prose, not the key.
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:

                  base-kb:
                    description: >
                      The base knowledge base. In this sentence
                      depends-on: is only a word.
                    repo: https://example.com/base-kb.git
                    groupId: com.example.kb

                  # ── assembled from the base ──
                  mid-kb:
                    description: >
                      Layered on the base.
                    repo: https://example.com/mid-kb.git
                    groupId: com.example.kb

                # ide:
                #   maven-home: wrapper
                """, StandardCharsets.UTF_8);
        createLib("base-kb");
        createLib("mid-kb", "base-kb");

        YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(manifest()).isEqualTo("""
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                subprojects:

                  base-kb:
                    description: >
                      The base knowledge base. In this sentence
                      depends-on: is only a word.
                    repo: https://example.com/base-kb.git
                    groupId: com.example.kb
                %1$s
                    depends-on: []

                  # ── assembled from the base ──
                  mid-kb:
                    description: >
                      Layered on the base.
                    repo: https://example.com/mid-kb.git
                    groupId: com.example.kb
                %1$s
                    depends-on:
                      - subproject: base-kb
                        relationship: build

                # ide:
                #   maven-home: wrapper
                """.formatted(DependsOnMerge.MARKER));
    }

    // ── Only a later member has a block ──────────────────────────

    @Test
    void later_members_block_does_not_take_an_earlier_members_edges()
            throws Exception {
        // The second ws:add of the report: the older members have no
        // block and do have dependencies; the member added last has the
        // empty block ws:add wrote for it, already annotated by the run
        // before. Its build file declares no dependencies.
        createLib("base-kb");
        createLib("mid-kb", "base-kb");
        createLib("top-kb", "base-kb", "mid-kb");
        createLib("new-kb");
        addToManifest("base-kb", "");
        addToManifest("mid-kb", "");
        addToManifest("top-kb", "");
        addToManifest("new-kb", MANAGED_EMPTY_BLOCK);
        String newKbBefore = section("new-kb");

        YamlDepsSync.SyncResult result =
                YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(result.changed()).isTrue();
        assertThat(section("new-kb"))
                .as("a member with no dependencies keeps an empty block")
                .isEqualTo(newKbBefore)
                .doesNotContain("subproject:");
        assertThat(edgeTargets("base-kb")).isEmpty();
        assertThat(edgeTargets("mid-kb")).containsExactly("base-kb");
        assertThat(edgeTargets("top-kb"))
                .containsExactly("base-kb", "mid-kb");
        assertThat(String.join("\n", result.changeLines()))
                .as("an untouched member is not reported as changed")
                .doesNotContain("new-kb");
    }

    @Test
    void later_members_unannotated_block_gains_only_the_marker()
            throws Exception {
        // The first ws:add of the report: the new member's block is the
        // bare "depends-on: []" ws:add writes. The only change that
        // belongs in it is the managed marker.
        createLib("base-kb");
        createLib("mid-kb", "base-kb");
        createLib("new-kb");
        addToManifest("base-kb", "");
        addToManifest("mid-kb", "");
        addToManifest("new-kb", "    depends-on: []\n");

        YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(section("new-kb")).endsWith(MANAGED_EMPTY_BLOCK);
        assertThat(edgeTargets("new-kb")).isEmpty();
        assertThat(edgeTargets("mid-kb")).containsExactly("base-kb");
        assertThat(section("base-kb")).endsWith(MANAGED_EMPTY_BLOCK);
    }

    @Test
    void change_lines_report_each_member_whose_block_was_written()
            throws Exception {
        createLib("base-kb");
        createLib("mid-kb", "base-kb");
        createLib("new-kb");
        addToManifest("base-kb", "");
        addToManifest("mid-kb", "");
        addToManifest("new-kb", MANAGED_EMPTY_BLOCK);
        String before = manifest();

        YamlDepsSync.SyncResult result =
                YamlDepsSync.run(tempDir.toFile(), log);

        // Every reported member's section really differs, and every
        // member whose section differs is reported.
        Set<String> written = new LinkedHashSet<>();
        for (String name : List.of("base-kb", "mid-kb", "new-kb")) {
            if (!section(before, name).equals(section(manifest(), name))) {
                written.add(name);
            }
        }
        assertThat(written).containsExactly("base-kb", "mid-kb");
        assertThat(result.changeLines()).hasSize(2);
        assertThat(result.changeLines().get(0))
                .startsWith("base-kb: ")
                .contains("no edges");
        assertThat(result.changeLines().get(1))
                .startsWith("mid-kb depends-on (+1, -0)")
                .contains("added [base-kb]")
                .contains("new block");
    }

    // ── One member's name ends with another's ────────────────────

    @Test
    void member_named_as_the_tail_of_an_earlier_one_leaves_that_ones_block_alone()
            throws Exception {
        // Everything is already in step with the POMs. Looking for
        // "gudid-kb:" anywhere finds it inside "solor-gudid-kb:" first,
        // and the merge then strips solor-gudid-kb's real edge because
        // gudid-kb does not derive it.
        createLib("base-kb");
        createLib("solor-gudid-kb", "gudid-kb");
        createLib("gudid-kb", "base-kb");
        addToManifest("base-kb", MANAGED_EMPTY_BLOCK);
        addToManifest("solor-gudid-kb", managedBlock("gudid-kb"));
        addToManifest("gudid-kb", managedBlock("base-kb"));
        String before = manifest();

        YamlDepsSync.SyncResult result =
                YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(result.changed())
                .as("a manifest in step with its POMs is left alone")
                .isFalse();
        assertThat(manifest()).isEqualTo(before);
    }

    @Test
    void edges_of_a_member_named_as_the_tail_of_an_earlier_one_land_in_its_own_block()
            throws Exception {
        createLib("base-kb");
        createLib("solor-gudid-kb");
        createLib("gudid-kb", "base-kb");
        addToManifest("base-kb", MANAGED_EMPTY_BLOCK);
        addToManifest("solor-gudid-kb", MANAGED_EMPTY_BLOCK);
        addToManifest("gudid-kb", MANAGED_EMPTY_BLOCK);

        YamlDepsSync.SyncResult result =
                YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(result.changed()).isTrue();
        assertThat(edgeTargets("gudid-kb")).containsExactly("base-kb");
        assertThat(edgeTargets("solor-gudid-kb")).isEmpty();
        assertThat(String.join("\n", result.changeLines()))
                .contains("gudid-kb depends-on (+1, -0)")
                .doesNotContain("solor-gudid-kb");
    }

    @Test
    void tail_named_member_without_a_block_gains_its_own()
            throws Exception {
        // Both defects at once: the later, shorter-named member has no
        // block, and the earlier, longer-named one does.
        createLib("base-kb");
        createLib("solor-gudid-kb", "gudid-kb");
        createLib("gudid-kb", "base-kb");
        addToManifest("base-kb", MANAGED_EMPTY_BLOCK);
        addToManifest("solor-gudid-kb", managedBlock("gudid-kb"));
        addToManifest("gudid-kb", "");

        YamlDepsSync.run(tempDir.toFile(), log);

        assertThat(edgeTargets("solor-gudid-kb"))
                .containsExactly("gudid-kb");
        assertThat(edgeTargets("gudid-kb")).containsExactly("base-kb");
    }

    // ── The commit says what was written ─────────────────────────

    @Test
    void commit_body_names_the_members_whose_sections_the_commit_changes()
            throws Exception {
        createLib("base-kb");
        createLib("mid-kb", "base-kb");
        createLib("top-kb", "base-kb", "mid-kb");
        createLib("new-kb");
        addToManifest("base-kb", "");
        addToManifest("mid-kb", "");
        addToManifest("top-kb", "");
        addToManifest("new-kb", MANAGED_EMPTY_BLOCK);
        gitInitAndCommitAll("baseline");

        boolean committed = PostMutationSync.refresh(tempDir.toFile(), log);

        assertThat(committed).isTrue();
        String committedBefore = capture("git", "show", "HEAD~1:workspace.yaml");
        String committedAfter = capture("git", "show", "HEAD:workspace.yaml");
        List<String> changedInCommit = new ArrayList<>();
        for (String name : List.of("base-kb", "mid-kb", "top-kb", "new-kb")) {
            if (!section(committedBefore, name)
                    .equals(section(committedAfter, name))) {
                changedInCommit.add(name);
            }
        }
        assertThat(changedInCommit)
                .containsExactly("base-kb", "mid-kb", "top-kb");

        // One bullet per member the commit changes, in manifest order,
        // and none for a member it leaves alone.
        List<String> bullets = new ArrayList<>();
        for (String line : capture("git", "log", "-1", "--format=%b")
                .split("\n")) {
            if (line.startsWith("- ")) bullets.add(line.substring(2));
        }
        assertThat(bullets).hasSize(3);
        assertThat(bullets.get(0)).startsWith("base-kb: ");
        assertThat(bullets.get(1))
                .startsWith("mid-kb depends-on (+1, -0)")
                .contains("added [base-kb]");
        assertThat(bullets.get(2))
                .startsWith("top-kb depends-on (+2, -0)")
                .contains("added [base-kb, mid-kb]");
        assertThat(capture("git", "log", "-1", "--format=%b"))
                .contains("ike-issues#1387");
    }

    // ── Fixture ──────────────────────────────────────────────────

    /**
     * A single-module repository whose POM depends on each named
     * upstream member.
     */
    private void createLib(String name, String... upstreams)
            throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        StringBuilder deps = new StringBuilder();
        if (upstreams.length > 0) {
            deps.append("    <dependencies>\n");
            for (String upstream : upstreams) {
                deps.append("        <dependency>\n")
                        .append("            <groupId>").append(GROUP_ID)
                        .append("</groupId>\n")
                        .append("            <artifactId>").append(upstream)
                        .append("</artifactId>\n")
                        .append("            <version>1.0.0</version>\n")
                        .append("        </dependency>\n");
            }
            deps.append("    </dependencies>\n");
        }
        Files.writeString(dir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                %s</project>
                """.formatted(GROUP_ID, name, deps),
                StandardCharsets.UTF_8);
    }

    /** A managed block holding one build edge per named target. */
    private static String managedBlock(String... targets) {
        StringBuilder block = new StringBuilder(DependsOnMerge.MARKER)
                .append("\n    depends-on:\n");
        for (String target : targets) {
            block.append("      - subproject: ").append(target).append('\n')
                    .append("        relationship: build\n");
        }
        return block.toString();
    }

    /**
     * Append a member to the manifest. {@code depsBlock} is its
     * {@code depends-on} block verbatim, or empty for a member with no
     * {@code depends-on:} key at all.
     */
    private void addToManifest(String name, String depsBlock)
            throws Exception {
        String entry = "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + "    groupId: " + GROUP_ID + "\n"
                + depsBlock;
        Files.writeString(tempDir.resolve("workspace.yaml"),
                manifest() + entry, StandardCharsets.UTF_8);
    }

    private String manifest() throws Exception {
        return Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
    }

    private String section(String name) throws Exception {
        return section(manifest(), name);
    }

    /**
     * The lines of one member's entry, read independently of the code
     * under test: from its key line up to the next line at member
     * indentation or the first top-level line, without trailing blank
     * lines.
     */
    private static String section(String yaml, String name) {
        StringBuilder own = new StringBuilder();
        StringBuilder pendingBlank = new StringBuilder();
        boolean inside = false;
        for (String line : yaml.split("\n", -1)) {
            if (!inside) {
                inside = line.equals("  " + name + ":");
                if (inside) own.append(line).append('\n');
                continue;
            }
            if (line.isBlank()) {
                pendingBlank.append(line).append('\n');
                continue;
            }
            if (!line.startsWith("   ")) break;
            own.append(pendingBlank).append(line).append('\n');
            pendingBlank.setLength(0);
        }
        return own.toString();
    }

    /** The {@code subproject:} targets recorded in a member's section. */
    private List<String> edgeTargets(String name) throws Exception {
        List<String> targets = new ArrayList<>();
        for (String line : section(name).split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("- subproject:")) {
                targets.add(stripped
                        .substring("- subproject:".length()).trim());
            }
        }
        return targets;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0;
                at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private void gitInitAndCommitAll(String message) throws Exception {
        exec("git", "init", "-b", "main");
        Path noHooks = Files.createDirectories(tempDir.resolve(".nohooks"));
        exec("git", "config", "core.hooksPath",
                noHooks.toAbsolutePath().toString());
        exec("git", "config", "commit.gpgsign", "false");
        exec("git", "config", "user.email", "test@example.com");
        exec("git", "config", "user.name", "Test");
        exec("git", "add", "-A");
        exec("git", "commit", "-m", message);
    }

    private void exec(String... command) throws Exception {
        capture(command);
    }

    private String capture(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: "
                    + String.join(" ", command) + "\n" + out);
        }
        return out.strip();
    }
}
