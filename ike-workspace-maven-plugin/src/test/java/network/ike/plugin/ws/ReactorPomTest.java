package network.ike.plugin.ws;

import network.ike.plugin.ws.ReactorPom.ProfileEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReactorPom}, the OpenRewrite-LST editor for a
 * workspace reactor POM's subproject membership (IKE-Network/ike-issues#696).
 *
 * <p>Assertions go through the read-back methods rather than exact
 * whitespace so the tests stay robust against OpenRewrite's printer —
 * mirroring {@code PomRewriterTest}.
 */
class ReactorPomTest {

    /** Modern reactor POM: top-level {@code <subprojects>}, no profiles. */
    private static final String MODERN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.1.0" root="true">
                <modelVersion>4.1.0</modelVersion>
                <groupId>local.aggregate</groupId>
                <artifactId>demo-wsr</artifactId>
                <version>1-SNAPSHOT</version>
                <packaging>pom</packaging>
                <subprojects>
                    <subproject>alpha</subproject>
                    <subproject>beta</subproject>
                </subprojects>
            </project>
            """;

    /** Legacy reactor POM: one file-activated {@code with-*} profile each. */
    private static final String LEGACY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.1.0" root="true">
                <modelVersion>4.1.0</modelVersion>
                <groupId>local.aggregate</groupId>
                <artifactId>demo-wsr</artifactId>
                <version>1-SNAPSHOT</version>
                <packaging>pom</packaging>
                <profiles>
                    <profile>
                        <id>with-alpha</id>
                        <activation>
                            <file>
                                <exists>${project.basedir}/alpha/pom.xml</exists>
                            </file>
                        </activation>
                        <subprojects>
                            <subproject>alpha</subproject>
                        </subprojects>
                    </profile>
                    <profile>
                        <id>with-beta</id>
                        <activation>
                            <file>
                                <exists>${project.basedir}/beta/pom.xml</exists>
                            </file>
                        </activation>
                        <subprojects>
                            <subproject>beta</subproject>
                        </subprojects>
                    </profile>
                </profiles>
            </project>
            """;

    // ── Reads ────────────────────────────────────────────────────

    @Test
    void listSubprojects_reads_top_level_block_in_order() {
        assertThat(ReactorPom.listSubprojects(MODERN))
                .containsExactly("alpha", "beta");
    }

    @Test
    void listSubprojects_empty_when_no_block() {
        assertThat(ReactorPom.listSubprojects(LEGACY)).isEmpty();
    }

    @Test
    void listSubprojectProfiles_reads_legacy_profiles() {
        List<ProfileEntry> profiles = ReactorPom.listSubprojectProfiles(LEGACY);
        assertThat(profiles).extracting(ProfileEntry::id)
                .containsExactly("with-alpha", "with-beta");
        assertThat(profiles).flatExtracting(ProfileEntry::subprojects)
                .containsExactly("alpha", "beta");
    }

    @Test
    void listSubprojectProfiles_empty_on_modern_pom() {
        assertThat(ReactorPom.listSubprojectProfiles(MODERN)).isEmpty();
    }

    // ── setSubprojects ───────────────────────────────────────────

    @Test
    void setSubprojects_replaces_existing_block() {
        String updated = ReactorPom.setSubprojects(MODERN,
                List.of("alpha", "beta", "gamma"));
        assertThat(ReactorPom.listSubprojects(updated))
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void setSubprojects_removes_entries() {
        String updated = ReactorPom.setSubprojects(MODERN, List.of("alpha"));
        assertThat(ReactorPom.listSubprojects(updated)).containsExactly("alpha");
    }

    @Test
    void setSubprojects_inserts_block_when_absent_keeping_profiles() {
        // A legacy POM has no top-level block; inserting one must not
        // disturb the existing <profiles> (migration adds before retiring).
        String updated = ReactorPom.setSubprojects(LEGACY,
                List.of("alpha", "beta"));
        assertThat(ReactorPom.listSubprojects(updated))
                .containsExactly("alpha", "beta");
        assertThat(ReactorPom.listSubprojectProfiles(updated))
                .extracting(ProfileEntry::id)
                .containsExactly("with-alpha", "with-beta");
    }

    @Test
    void setSubprojects_is_idempotent() {
        String once = ReactorPom.setSubprojects(MODERN, List.of("alpha", "beta"));
        assertThat(once).isEqualTo(MODERN);
    }

    @Test
    void setSubprojects_empty_list_leaves_empty_block() {
        String updated = ReactorPom.setSubprojects(MODERN, List.of());
        assertThat(ReactorPom.listSubprojects(updated)).isEmpty();
        assertThat(updated).contains("<subprojects>");
    }

    // ── removeProfile ────────────────────────────────────────────

    @Test
    void removeProfile_drops_one_keeps_others() {
        String updated = ReactorPom.removeProfile(LEGACY, "with-alpha");
        assertThat(ReactorPom.listSubprojectProfiles(updated))
                .extracting(ProfileEntry::id)
                .containsExactly("with-beta");
    }

    @Test
    void removeProfile_drops_profiles_block_when_last_removed() {
        String afterFirst = ReactorPom.removeProfile(LEGACY, "with-alpha");
        String afterBoth = ReactorPom.removeProfile(afterFirst, "with-beta");
        assertThat(ReactorPom.listSubprojectProfiles(afterBoth)).isEmpty();
        assertThat(afterBoth).doesNotContain("<profiles>");
    }

    @Test
    void removeProfile_noop_when_absent() {
        assertThat(ReactorPom.removeProfile(MODERN, "with-nope"))
                .isEqualTo(MODERN);
    }

    // ── Full migration round-trip ────────────────────────────────

    @Test
    void migration_legacy_to_modern_round_trips() {
        // Mirror the reconciler: declare the full membership top-level,
        // then retire every legacy profile.
        String pom = ReactorPom.setSubprojects(LEGACY, List.of("alpha", "beta"));
        for (ProfileEntry p : ReactorPom.listSubprojectProfiles(pom)) {
            pom = ReactorPom.removeProfile(pom, p.id());
        }
        assertThat(ReactorPom.listSubprojects(pom))
                .containsExactly("alpha", "beta");
        assertThat(ReactorPom.listSubprojectProfiles(pom)).isEmpty();
        assertThat(pom).doesNotContain("<profiles>");
        assertThat(pom).doesNotContain("with-alpha");
        // Still well-formed enough to re-read.
        assertThat(ReactorPom.listSubprojects(
                ReactorPom.setSubprojects(pom, List.of("alpha", "beta"))))
                .containsExactly("alpha", "beta");
    }
}
