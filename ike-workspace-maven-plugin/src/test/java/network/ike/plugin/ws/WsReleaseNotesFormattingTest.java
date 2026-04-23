package network.ike.plugin.ws;

import network.ike.plugin.ReleaseNotesSupport;
import network.ike.plugin.ReleaseNotesSupport.Issue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the release notes formatting logic in
 * {@link ReleaseNotesSupport}.
 *
 * <p>Tests the pure formatting functions (categorization by label,
 * sorting, markdown output) without requiring GitHub API access.
 */
class WsReleaseNotesFormattingTest {

    @Test
    void categorizes_bugs_as_fixes() {
        var issues = List.of(
                new Issue(1, "Fix null pointer in init", List.of("bug", "tooling")));

        String notes = ReleaseNotesSupport.formatNotes("test v1", issues);

        assertThat(notes).contains("### Fixes");
        assertThat(notes).contains("- Fix null pointer in init (#1)");
        assertThat(notes).doesNotContain("### Enhancements");
        assertThat(notes).doesNotContain("### Internal");
    }

    @Test
    void categorizes_enhancements() {
        var issues = List.of(
                new Issue(2, "Add ws:release-notes goal", List.of("enhancement", "tooling")));

        String notes = ReleaseNotesSupport.formatNotes("test v1", issues);

        assertThat(notes).contains("### Enhancements");
        assertThat(notes).contains("- Add ws:release-notes goal (#2)");
    }

    @Test
    void categorizes_unlabeled_as_internal() {
        var issues = List.of(
                new Issue(3, "Update docs", List.of("documentation")));

        String notes = ReleaseNotesSupport.formatNotes("test v1", issues);

        assertThat(notes).contains("### Internal");
        assertThat(notes).contains("- Update docs (#3)");
    }

    @Test
    void mixed_categories_produce_all_sections() {
        var issues = List.of(
                new Issue(1, "Fix crash", List.of("bug")),
                new Issue(2, "New feature", List.of("enhancement")),
                new Issue(3, "Cleanup", List.of("tech-debt")));

        String notes = ReleaseNotesSupport.formatNotes("milestone v2", issues);

        assertThat(notes)
                .contains("## milestone v2")
                .contains("### Fixes")
                .contains("### Enhancements")
                .contains("### Internal");
    }

    @Test
    void release_notes_labeled_issues_sort_first() {
        var issues = List.of(
                new Issue(10, "Minor fix", List.of("bug")),
                new Issue(5, "Important fix", List.of("bug", "release-notes")));

        String notes = ReleaseNotesSupport.formatNotes("test v1", issues);

        int importantIdx = notes.indexOf("Important fix (#5)");
        int minorIdx = notes.indexOf("Minor fix (#10)");
        assertThat(importantIdx).isLessThan(minorIdx);
    }

    @Test
    void empty_issues_produces_no_closed_message() {
        String notes = ReleaseNotesSupport.formatNotes("test v1", List.of());

        assertThat(notes).contains("No closed issues in this milestone.");
        assertThat(notes).doesNotContain("### Fixes");
        assertThat(notes).doesNotContain("### Enhancements");
        assertThat(notes).doesNotContain("### Internal");
    }

    @Test
    void milestone_name_appears_as_heading() {
        String notes = ReleaseNotesSupport.formatNotes("ike-tooling v57", List.of());

        assertThat(notes).startsWith("## ike-tooling v57");
    }
}
