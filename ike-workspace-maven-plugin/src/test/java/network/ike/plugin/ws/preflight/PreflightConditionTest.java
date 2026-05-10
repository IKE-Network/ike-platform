package network.ike.plugin.ws.preflight;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the pure-string helpers in {@link PreflightCondition}
 * introduced by #346 to expand the {@code ws:release-draft}
 * pre-flight scope.
 */
class PreflightConditionTest {

    // ── hasDistributionManagementOrParent ─────────────────────────

    @Test
    void hasDistMgmtOrParent_withDistMgmt_true() {
        String pom = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>x</artifactId>
                  <version>1</version>
                  <distributionManagement>
                    <repository>...</repository>
                  </distributionManagement>
                </project>
                """;
        assertThat(PreflightCondition.hasDistributionManagementOrParent(pom))
                .isTrue();
    }

    @Test
    void hasDistMgmtOrParent_withParent_true() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """;
        assertThat(PreflightCondition.hasDistributionManagementOrParent(pom))
                .isTrue();
    }

    @Test
    void hasDistMgmtOrParent_neither_false() {
        // The its/ pom that broke the v150 cascade — no parent, no
        // distributionManagement.
        String pom = """
                <project>
                  <groupId>network.ike.examples.its</groupId>
                  <artifactId>ike-example-ws-its</artifactId>
                  <version>1-SNAPSHOT</version>
                  <packaging>pom</packaging>
                </project>
                """;
        assertThat(PreflightCondition.hasDistributionManagementOrParent(pom))
                .isFalse();
    }

    @Test
    void hasDistMgmtOrParent_nullOrBlank_false() {
        assertThat(PreflightCondition.hasDistributionManagementOrParent(null))
                .isFalse();
        assertThat(PreflightCondition.hasDistributionManagementOrParent(""))
                .isFalse();
    }

    // ── shadowsProperty ───────────────────────────────────────────

    @Test
    void shadowsProperty_topLevelDeclaration_true() {
        // The exact case that bit the v150 cascade — its/ pom
        // shadowed the inherited ike-tooling.version with a local
        // value, pinning the plugin to v126.
        String pom = """
                <project>
                  <properties>
                    <ike-tooling.version>126</ike-tooling.version>
                  </properties>
                </project>
                """;
        assertThat(PreflightCondition.shadowsProperty(pom,
                "ike-tooling.version")).isTrue();
    }

    @Test
    void shadowsProperty_namespacedAlternative_false() {
        // The post-#347 layout: same value but under `it.*` namespace
        // — does NOT shadow the inherited property.
        String pom = """
                <project>
                  <properties>
                    <it.ike-tooling.version>126</it.ike-tooling.version>
                  </properties>
                </project>
                """;
        assertThat(PreflightCondition.shadowsProperty(pom,
                "ike-tooling.version")).isFalse();
    }

    @Test
    void shadowsProperty_notDeclared_false() {
        String pom = """
                <project>
                  <properties>
                    <java.version>25</java.version>
                  </properties>
                </project>
                """;
        assertThat(PreflightCondition.shadowsProperty(pom,
                "ike-tooling.version")).isFalse();
    }

    @Test
    void shadowsProperty_noPropertiesBlock_false() {
        String pom = """
                <project>
                  <groupId>x</groupId>
                  <artifactId>y</artifactId>
                  <version>1</version>
                </project>
                """;
        assertThat(PreflightCondition.shadowsProperty(pom,
                "ike-tooling.version")).isFalse();
    }

    // ── extractParentGa + extractParentVersion ───────────────────

    @Test
    void extractParentGa_normalCase_returnsConcat() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """;
        assertThat(PreflightCondition.extractParentGa(pom))
                .isEqualTo("network.ike.platform:ike-parent");
    }

    @Test
    void extractParentGa_noParent_returnsNull() {
        String pom = "<project><artifactId>orphan</artifactId></project>";
        assertThat(PreflightCondition.extractParentGa(pom)).isNull();
    }

    @Test
    void extractParentVersion_normalCase_returnsVersion() {
        String pom = """
                <project>
                  <parent>
                    <groupId>x</groupId>
                    <artifactId>y</artifactId>
                    <version>1.2.3</version>
                  </parent>
                </project>
                """;
        assertThat(PreflightCondition.extractParentVersion(pom))
                .isEqualTo("1.2.3");
    }

    @Test
    void extractParentVersion_noParent_returnsNull() {
        assertThat(PreflightCondition.extractParentVersion(
                "<project/>")).isNull();
    }

    @Test
    void extractParentVersion_parentWithoutVersion_returnsNull() {
        // Maven 4 allows omitting <version> from <parent> when the
        // version is inherited from the workspace. We don't handle
        // that case specifically — the helper just returns null and
        // the coherence check skips it.
        String pom = """
                <project>
                  <parent>
                    <groupId>x</groupId>
                    <artifactId>y</artifactId>
                  </parent>
                </project>
                """;
        assertThat(PreflightCondition.extractParentVersion(pom)).isNull();
    }
}
