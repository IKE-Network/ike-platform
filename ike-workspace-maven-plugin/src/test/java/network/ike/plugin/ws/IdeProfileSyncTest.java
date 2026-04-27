package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the owned-block rewrite in {@link IdeProfileSync}.
 *
 * <p>These tests exercise the pure-string transformation that drives
 * {@code .mvn/maven.config} updates. The integration with the Maven
 * model and filesystem is tested implicitly via end-to-end runs from
 * the trigger-set mojos.
 */
class IdeProfileSyncTest {

    @Test
    void emptyFile_emptyProfiles_emptyOutput() {
        String out = IdeProfileSync.rewriteOwnedBlock("", List.of());
        assertThat(out).isEmpty();
    }

    @Test
    void emptyFile_someProfiles_writesBlockOnly() {
        String out = IdeProfileSync.rewriteOwnedBlock("",
                List.of("with-tinkar-core", "with-komet-bom"));
        assertThat(out).isEqualTo(
                "# >>> ws:ide-sync managed >>>\n"
                        + "-Pwith-tinkar-core,with-komet-bom\n"
                        + "# <<< ws:ide-sync managed <<<\n");
    }

    @Test
    void preservesUserLines_appendsBlock() {
        String existing = "-T 1C\n-pl\n!.teamcity\n";
        String out = IdeProfileSync.rewriteOwnedBlock(existing,
                List.of("with-tinkar-core"));
        assertThat(out).isEqualTo(
                "-T 1C\n-pl\n!.teamcity\n"
                        + "# >>> ws:ide-sync managed >>>\n"
                        + "-Pwith-tinkar-core\n"
                        + "# <<< ws:ide-sync managed <<<\n");
    }

    @Test
    void replacesExistingBlock_keepingUserLines() {
        String existing = "-T 1C\n-pl\n!.teamcity\n"
                + "# >>> ws:ide-sync managed >>>\n"
                + "-Pwith-tinkar-core\n"
                + "# <<< ws:ide-sync managed <<<\n";
        String out = IdeProfileSync.rewriteOwnedBlock(existing,
                List.of("with-tinkar-core", "with-komet-bom"));
        assertThat(out).isEqualTo(
                "-T 1C\n-pl\n!.teamcity\n"
                        + "# >>> ws:ide-sync managed >>>\n"
                        + "-Pwith-tinkar-core,with-komet-bom\n"
                        + "# <<< ws:ide-sync managed <<<\n");
    }

    @Test
    void emptyProfilesRemovesExistingBlock() {
        String existing = "-T 1C\n"
                + "# >>> ws:ide-sync managed >>>\n"
                + "-Pwith-tinkar-core\n"
                + "# <<< ws:ide-sync managed <<<\n";
        String out = IdeProfileSync.rewriteOwnedBlock(existing, List.of());
        assertThat(out).isEqualTo("-T 1C\n");
    }

    @Test
    void idempotent_secondRewriteIsNoop() {
        String existing = "-T 1C\n";
        String first = IdeProfileSync.rewriteOwnedBlock(existing,
                List.of("with-tinkar-core"));
        String second = IdeProfileSync.rewriteOwnedBlock(first,
                List.of("with-tinkar-core"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void preservesLinesAfterBlock() {
        String existing = "-T 1C\n"
                + "# >>> ws:ide-sync managed >>>\n"
                + "-Pwith-old\n"
                + "# <<< ws:ide-sync managed <<<\n"
                + "-DskipTests=false\n";
        String out = IdeProfileSync.rewriteOwnedBlock(existing,
                List.of("with-new"));
        assertThat(out).contains("-T 1C\n");
        assertThat(out).contains("-Pwith-new\n");
        assertThat(out).contains("-DskipTests=false");
        assertThat(out).doesNotContain("with-old");
    }
}
