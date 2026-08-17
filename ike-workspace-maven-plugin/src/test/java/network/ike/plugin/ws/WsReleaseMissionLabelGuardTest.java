package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mission-label series guard (IKE-Network/ike-issues#1035): the
 * delivery chain resolves the mission record by the canonical
 * {@code <root artifactId>-<root release version>} name, so an
 * explicitly drifted label warns at draft and refuses at publish
 * unless the operator confirms it. Mission 6 shipped with mission 5's
 * release body exactly this way ({@code -Dmission=6} vs the canonical
 * {@code ike-komet-wsr-6}).
 */
class WsReleaseMissionLabelGuardTest {

    /** A log that records its warnings, for the draft-path assertion. */
    private static final class WarnRecordingLog extends TestLog {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void warn(CharSequence content) {
            warnings.add(content.toString());
        }
    }

    @Test
    void canonical_label_passes_silently_in_both_modes() {
        WarnRecordingLog log = new WarnRecordingLog();
        assertThatCode(() -> WsReleaseDraftMojo.guardMissionLabel(
                "ike-komet-wsr-6", "ike-komet-wsr-6", true, false, log))
                .doesNotThrowAnyException();
        assertThatCode(() -> WsReleaseDraftMojo.guardMissionLabel(
                "ike-komet-wsr-6", "ike-komet-wsr-6", false, false, log))
                .doesNotThrowAnyException();
        assertThat(log.warnings).isEmpty();
    }

    @Test
    void drifted_label_refuses_at_publish_with_remediation() {
        assertThatThrownBy(() -> WsReleaseDraftMojo.guardMissionLabel(
                "6", "ike-komet-wsr-6", true, false, new TestLog()))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("`6`")
                .hasMessageContaining("`ike-komet-wsr-6`")
                .hasMessageContaining("-DacceptMissionLabel=true");
    }

    @Test
    void drifted_label_warns_at_draft_without_refusing() {
        WarnRecordingLog log = new WarnRecordingLog();
        assertThatCode(() -> WsReleaseDraftMojo.guardMissionLabel(
                "6", "ike-komet-wsr-6", false, false, log))
                .doesNotThrowAnyException();
        assertThat(log.warnings).singleElement().asString()
                .contains("ike-komet-wsr-6");
    }

    @Test
    void confirmed_drift_is_accepted_in_both_modes() {
        WarnRecordingLog log = new WarnRecordingLog();
        assertThatCode(() -> WsReleaseDraftMojo.guardMissionLabel(
                "hotfix-6", "ike-komet-wsr-6", true, true, log))
                .doesNotThrowAnyException();
        assertThatCode(() -> WsReleaseDraftMojo.guardMissionLabel(
                "hotfix-6", "ike-komet-wsr-6", false, true, log))
                .doesNotThrowAnyException();
        assertThat(log.warnings).isEmpty();
    }
}
