package network.ike.plugin.ws.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClaudeMdMerge} — the non-destructive
 * {@code CLAUDE.md} regeneration that backs {@code ws:scaffold-init}
 * (IKE-Network/ike-issues#917, #920).
 */
class ClaudeMdMergeTest {

    /** A representative freshly-generated component template. */
    private static final String TEMPLATE = """
            # komet-desktop

            ## Build Standards

            Files in `.claude/standards/` are build artifacts. DO NOT edit.

            ## Build

            ```bash
            mvn clean verify -DskipTests -T 1C
            ```

            ## Key Facts

            - Uses `--enable-preview` (Java 25)

            ## Prohibited Patterns

            - **Never use `maven-antrun-plugin`**

            See `.claude/standards/` for full standards.
            See `CLAUDE-komet-desktop.md` for project-specific notes.
            """;

    private static final String STANDARDS_POINTER = """
            <!-- BEGIN ike-managed: standards-pointer -->
            This project follows the IKE build standards. Run `mvn validate`.
            <!-- END ike-managed: standards-pointer -->""";

    private static final String DEVELOPER_SETUP = """
            <!-- BEGIN ike-managed: developer-setup -->
            Clone with `git clone …`; build with the Maven wrapper.
            <!-- END ike-managed: developer-setup -->""";

    private static final String HAND_AUTHORED = """
            ## Fast-Dev Build

            The fast-dev profile auto-activates. Signed installers need
            `IKE_RELEASE=1` and an `op run` wrapper.
            """;

    @Test
    void nullExisting_returnsGeneratedUnchanged() {
        ClaudeMdMerge.Result r = ClaudeMdMerge.merge(null, TEMPLATE);
        assertThat(r.claudeMd()).isEqualTo(TEMPLATE);
        assertThat(r.rescued()).isEmpty();
    }

    @Test
    void blankExisting_returnsGeneratedUnchanged() {
        ClaudeMdMerge.Result r = ClaudeMdMerge.merge("   \n  ", TEMPLATE);
        assertThat(r.claudeMd()).isEqualTo(TEMPLATE);
        assertThat(r.rescued()).isEmpty();
    }

    @Test
    void managedRegionPreservedVerbatim_920() {
        String existing = TEMPLATE + "\n" + STANDARDS_POINTER + "\n";

        ClaudeMdMerge.Result r = ClaudeMdMerge.merge(existing, TEMPLATE);

        // The whole managed block survives, including its exact marker lines —
        // the ike plugin locates the region by full-line equality, so a single
        // altered byte on a marker line would orphan it.
        assertThat(r.claudeMd())
                .contains("<!-- BEGIN ike-managed: standards-pointer -->")
                .contains("<!-- END ike-managed: standards-pointer -->")
                .contains("This project follows the IKE build standards.");
        assertThat(r.rescued()).isEmpty();
    }

    @Test
    void multipleManagedRegionsPreserved_920() {
        String existing = TEMPLATE + "\n" + STANDARDS_POINTER + "\n\n"
                + DEVELOPER_SETUP + "\n";

        ClaudeMdMerge.Result r = ClaudeMdMerge.merge(existing, TEMPLATE);

        assertThat(r.claudeMd())
                .contains("<!-- BEGIN ike-managed: standards-pointer -->")
                .contains("<!-- BEGIN ike-managed: developer-setup -->")
                .contains("<!-- END ike-managed: developer-setup -->");
        assertThat(r.rescued()).isEmpty();
    }

    @Test
    void handAuthoredSectionRescued_notDestroyed_917() {
        String existing = TEMPLATE + "\n" + HAND_AUTHORED;

        ClaudeMdMerge.Result r = ClaudeMdMerge.merge(existing, TEMPLATE);

        assertThat(r.rescued())
                .extracting(ClaudeMdMerge.Section::title)
                .containsExactly("Fast-Dev Build");
        // The rescued section is pulled OUT of the regenerated CLAUDE.md
        // (it belongs in CLAUDE-<name>.md) — but it is never dropped.
        assertThat(r.claudeMd()).doesNotContain("Fast-Dev Build");
        assertThat(r.rescued().getFirst().text())
                .contains("The fast-dev profile auto-activates.");
    }

    @Test
    void templateSectionsAreNotRescued() {
        // Every ## heading here is one the template itself emits, so none is
        // hand-authored — nothing to rescue even though the bodies differ.
        String staleTemplate = TEMPLATE.replace("Java 25", "Java 24");

        ClaudeMdMerge.Result r = ClaudeMdMerge.merge(staleTemplate, TEMPLATE);

        assertThat(r.rescued()).isEmpty();
        assertThat(r.claudeMd()).isEqualTo(TEMPLATE);
    }

    @Test
    void managedAndHandAuthored_bothHandled() {
        String existing = TEMPLATE + "\n" + HAND_AUTHORED + "\n"
                + STANDARDS_POINTER + "\n";

        ClaudeMdMerge.Result r = ClaudeMdMerge.merge(existing, TEMPLATE);

        assertThat(r.claudeMd())
                .contains("<!-- BEGIN ike-managed: standards-pointer -->")
                .doesNotContain("Fast-Dev Build");
        assertThat(r.rescued())
                .extracting(ClaudeMdMerge.Section::title)
                .containsExactly("Fast-Dev Build");
    }

    @Test
    void idempotent_reMergeIsAFixedPoint() {
        String existing = TEMPLATE + "\n" + HAND_AUTHORED + "\n"
                + STANDARDS_POINTER + "\n";

        ClaudeMdMerge.Result first = ClaudeMdMerge.merge(existing, TEMPLATE);
        // Feeding the merge output back in must not churn: the managed region
        // stays, and there is nothing left to rescue.
        ClaudeMdMerge.Result second =
                ClaudeMdMerge.merge(first.claudeMd(), TEMPLATE);

        assertThat(second.claudeMd()).isEqualTo(first.claudeMd());
        assertThat(second.rescued()).isEmpty();
    }

    @Test
    void managedRegionSurvivesAcrossTemplateBump() {
        // A template version bump changes template bodies but must not disturb
        // the managed region carried through from the old file.
        String existing = TEMPLATE + "\n" + STANDARDS_POINTER + "\n";
        String bumped = TEMPLATE.replace("Java 25", "Java 26");

        ClaudeMdMerge.Result r = ClaudeMdMerge.merge(existing, bumped);

        assertThat(r.claudeMd())
                .contains("Java 26")
                .contains("<!-- BEGIN ike-managed: standards-pointer -->")
                .contains("This project follows the IKE build standards.");
        assertThat(r.rescued()).isEmpty();
    }

    @Test
    void unterminatedManagedMarker_isNotSwallowed() {
        // A corrupt (BEGIN without END) marker must not eat the rest of the
        // file; it is treated as ordinary content, not a managed region.
        String existing = TEMPLATE
                + "\n<!-- BEGIN ike-managed: broken -->\ndangling\n";

        ClaudeMdMerge.Result r = ClaudeMdMerge.merge(existing, TEMPLATE);

        // "broken" is a ## -less fragment, so it is neither a rescued section
        // nor a preserved managed block — but the merge still completes.
        assertThat(r.rescued()).isEmpty();
        assertThat(List.of(r.claudeMd())).isNotEmpty();
    }
}
