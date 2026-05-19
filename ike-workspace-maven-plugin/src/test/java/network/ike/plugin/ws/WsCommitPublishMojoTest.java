package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the formatting helpers in {@link WsCommitPublishMojo}.
 *
 * <p>Covers ike-issues#231 — when {@code -DstagedOnly} causes
 * {@code ws:commit-publish} to skip a repo with uncommitted work, the
 * message must surface both tracked-unstaged and untracked file paths
 * so the developer sees exactly what would be missed.
 */
class WsCommitPublishMojoTest {

    @Test
    void suffix_unstaged_only_lists_tracked_paths() {
        String suffix = WsCommitPublishMojo.formatUncommittedSuffix(
                "src/main/java/A.java, src/main/java/B.java",
                List.of());
        assertThat(suffix).isEqualTo(
                "unstaged: src/main/java/A.java, src/main/java/B.java");
    }

    @Test
    void suffix_untracked_only_lists_new_file_paths() {
        String suffix = WsCommitPublishMojo.formatUncommittedSuffix(
                "",
                List.of("src/main/java/Foo.java", "src/main/java/Bar.java"));
        assertThat(suffix).isEqualTo(
                "untracked: src/main/java/Foo.java, src/main/java/Bar.java");
    }

    @Test
    void suffix_both_kinds_shows_both_lists_separated_by_semicolon() {
        String suffix = WsCommitPublishMojo.formatUncommittedSuffix(
                "src/main/java/Existing.java",
                List.of("src/main/java/New.java"));
        assertThat(suffix).isEqualTo(
                "unstaged: src/main/java/Existing.java; untracked: src/main/java/New.java");
    }

    @Test
    void suffix_neither_emits_uncommitted_placeholder() {
        // Defensive fallback — if the caller invoked us with empty
        // inputs (shouldn't happen in practice), don't emit empty parens.
        assertThat(invokeSuffix("", List.of())).isEqualTo("uncommitted");
    }

    @Test
    void suffix_handles_null_inputs_defensively() {
        assertThat(invokeSuffix(null, null)).isEqualTo("uncommitted");
        assertThat(invokeSuffix(null, List.of("x"))).isEqualTo("untracked: x");
        assertThat(invokeSuffix("y", null)).isEqualTo("unstaged: y");
    }

    /** Test-internal alias to keep the test names readable. */
    private static String invokeSuffix(String unstaged, List<String> newFiles) {
        return WsCommitPublishMojo.formatUncommittedSuffix(unstaged, newFiles);
    }
}
