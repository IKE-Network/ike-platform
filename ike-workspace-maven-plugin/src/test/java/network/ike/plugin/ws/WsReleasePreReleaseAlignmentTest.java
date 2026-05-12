package network.ike.plugin.ws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure helpers powering pre-release upstream
 * alignment in {@link WsReleaseDraftMojo} (ike-issues#377). The
 * end-to-end alignment behavior (pom rewrite + git commit) is
 * exercised by the {@code WsReleaseCascadeIntegrationTest} and by
 * live cascade dogfood; tests here cover only the string- and
 * map-level building blocks.
 */
class WsReleasePreReleaseAlignmentTest {

    // ── extractPropertyValue ─────────────────────────────────────

    @Test
    void extractPropertyValue_returnsValueWhenPresent() {
        String pom = "<project><properties>"
                + "<ike-tooling.version>163</ike-tooling.version>"
                + "</properties></project>";
        assertThat(WsReleaseDraftMojo.extractPropertyValue(pom,
                "ike-tooling.version"))
                .isEqualTo("163");
    }

    @Test
    void extractPropertyValue_returnsValueWithSurroundingWhitespace() {
        String pom = """
                <project>
                  <properties>
                    <ike-tooling.version>164</ike-tooling.version>
                  </properties>
                </project>
                """;
        assertThat(WsReleaseDraftMojo.extractPropertyValue(pom,
                "ike-tooling.version"))
                .isEqualTo("164");
    }

    @Test
    void extractPropertyValue_returnsNullWhenAbsent() {
        String pom = "<project><properties></properties></project>";
        assertThat(WsReleaseDraftMojo.extractPropertyValue(pom,
                "ike-tooling.version"))
                .isNull();
    }

    @Test
    void extractPropertyValue_returnsNullOnNullInput() {
        assertThat(WsReleaseDraftMojo.extractPropertyValue(null,
                "ike-tooling.version"))
                .isNull();
    }

    @Test
    void extractPropertyValue_returnsNullWhenOnlyOpenTag() {
        // Malformed POM — open tag without close. Defensive: don't crash.
        String pom = "<project><properties><ike-tooling.version>163"
                + "</project>";
        assertThat(WsReleaseDraftMojo.extractPropertyValue(pom,
                "ike-tooling.version"))
                .isNull();
    }

    // ── Foundation map sanity ────────────────────────────────────

    @Test
    void foundationGroupToDir_coversTheThreeFoundations() {
        assertThat(WsReleaseDraftMojo.FOUNDATION_GROUP_TO_DIR)
                .containsEntry("network.ike.tooling", "ike-tooling")
                .containsEntry("network.ike.docs", "ike-docs")
                .containsEntry("network.ike.platform", "ike-platform")
                .hasSize(3);
    }

    @Test
    void propertyToGroup_coversTheThreeFoundations() {
        assertThat(WsReleaseDraftMojo.PROPERTY_TO_GROUP)
                .containsEntry("ike-tooling.version", "network.ike.tooling")
                .containsEntry("ike-docs.version", "network.ike.docs")
                .containsEntry("ike-platform.version", "network.ike.platform")
                .hasSize(3);
    }
}
