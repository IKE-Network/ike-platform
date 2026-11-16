package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests where {@link DependsOnMerge} looks for a member's block and
 * where it writes one, on manifest layouts at the edges of what the
 * {@code ws:} goals write (IKE-Network/ike-issues#1387).
 *
 * <p>The workspace-level behavior — real manifests and POMs, through
 * {@link YamlDepsSync} — is in {@link YamlDepsSyncMemberSectionTest}.
 */
class DependsOnMergeSectionTest {

    private static final List<WsAddMojo.DerivedDep> NONE = List.of();

    private static final List<WsAddMojo.DerivedDep> ON_BASE =
            List.of(new WsAddMojo.DerivedDep("base", null));

    @Test
    void last_line_without_a_line_ending_gets_one_before_the_new_block() {
        String yaml = "subprojects:\n  lib:\n    repo: https://example.com/lib.git";

        String merged = DependsOnMerge.merge(yaml, "lib", NONE).yaml();

        assertThat(merged).isEqualTo("subprojects:\n  lib:\n"
                + "    repo: https://example.com/lib.git\n"
                + DependsOnMerge.MARKER + "\n"
                + "    depends-on: []\n");
    }

    @Test
    void block_ending_the_file_without_a_line_ending_is_found_not_duplicated() {
        String yaml = "subprojects:\n  lib:\n    depends-on: []";

        String merged = DependsOnMerge.merge(yaml, "lib", ON_BASE).yaml();

        assertThat(merged).isEqualTo("subprojects:\n  lib:\n"
                + DependsOnMerge.MARKER + "\n"
                + "    depends-on:\n"
                + "      - subproject: base\n"
                + "        relationship: build\n");
    }

    @Test
    void member_with_no_fields_gains_the_block_under_its_key() {
        String yaml = """
                subprojects:
                  lib:
                  other:
                    repo: https://example.com/other.git
                """;

        String merged = DependsOnMerge.merge(yaml, "lib", ON_BASE).yaml();

        assertThat(merged).isEqualTo("""
                subprojects:
                  lib:
                %s
                    depends-on:
                      - subproject: base
                        relationship: build
                  other:
                    repo: https://example.com/other.git
                """.formatted(DependsOnMerge.MARKER));
    }

    @Test
    void key_line_carrying_a_comment_is_the_members_key() {
        String yaml = """
                subprojects:
                  lib:   # the library
                    repo: https://example.com/lib.git
                """;

        assertThat(DependsOnMerge.merge(yaml, "lib", NONE).yaml())
                .endsWith("    repo: https://example.com/lib.git\n"
                        + DependsOnMerge.MARKER + "\n"
                        + "    depends-on: []\n");
    }

    @Test
    void name_in_a_comment_or_a_value_is_not_the_members_key() {
        // "lib:" appears in a member-level comment, in a field value
        // and as the tail of a longer name — and nowhere as a key.
        String yaml = """
                subprojects:
                  # lib:
                  my-lib:
                    description: lib:
                    depends-on: []
                """;

        DependsOnMerge.Result merged =
                DependsOnMerge.merge(yaml, "lib", ON_BASE);

        assertThat(merged.changed(yaml)).isFalse();
        assertThat(DependsOnMerge.recorded(yaml, "lib").present()).isFalse();
    }

    @Test
    void member_after_a_comment_at_column_zero_is_still_a_member() {
        // A comment at column 0 does not close subprojects:.
        String yaml = """
                subprojects:
                  base:
                    repo: https://example.com/base.git
                # ── libraries ──
                  lib:
                    repo: https://example.com/lib.git
                """;

        String merged = DependsOnMerge.merge(yaml, "lib", ON_BASE).yaml();

        assertThat(merged).isEqualTo("""
                subprojects:
                  base:
                    repo: https://example.com/base.git
                # ── libraries ──
                  lib:
                    repo: https://example.com/lib.git
                %s
                    depends-on:
                      - subproject: base
                        relationship: build
                """.formatted(DependsOnMerge.MARKER));
    }

    @Test
    void member_past_the_end_of_subprojects_is_not_a_member() {
        // "lib:" under another top-level key is not a subproject.
        String yaml = """
                subprojects:
                  base:
                    repo: https://example.com/base.git

                ide:
                  lib:
                    depends-on: []
                """;

        assertThat(DependsOnMerge.merge(yaml, "lib", ON_BASE).changed(yaml))
                .isFalse();
    }

    @Test
    void manifest_with_no_subprojects_key_is_left_alone() {
        String yaml = """
                defaults:
                  lib:
                    depends-on: []
                """;

        assertThat(DependsOnMerge.merge(yaml, "lib", ON_BASE).changed(yaml))
                .isFalse();
    }

    @Test
    void block_with_carriage_return_line_endings_is_left_alone() {
        // The block pattern does not read carriage-return line endings.
        // A member whose key is there but unreadable must not be given
        // a second depends-on key.
        String yaml = "subprojects:\r\n  lib:\r\n"
                + "    repo: https://example.com/lib.git\r\n"
                + "    depends-on: []\r\n";

        assertThat(DependsOnMerge.merge(yaml, "lib", ON_BASE).changed(yaml))
                .isFalse();
    }

    @Test
    void recorded_reads_a_members_own_edges() {
        String yaml = """
                subprojects:
                  solor-gudid-kb:
                    depends-on:
                      - subproject: gudid-kb
                        relationship: bundle
                  gudid-kb:
                    depends-on:
                      - subproject: base
                        version-property: base.version
                  base:
                    repo: https://example.com/base.git
                """;

        assertThat(DependsOnMerge.recorded(yaml, "gudid-kb").edges())
                .containsExactly(Map.entry("base",
                        new DependsOnMerge.Edge("build", "base.version")));
        assertThat(DependsOnMerge.recorded(yaml, "solor-gudid-kb").edges())
                .containsExactly(Map.entry("gudid-kb",
                        new DependsOnMerge.Edge("bundle", null)));
        assertThat(DependsOnMerge.recorded(yaml, "base").present())
                .isFalse();
    }
}
