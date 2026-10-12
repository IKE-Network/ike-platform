package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.TestLog;
import network.ike.plugin.ws.bootstrap.WorkspaceBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The managed extension pins of {@code .mvn/extensions.xml}: the reconciler brings an entry to
 * the version the plugin was built against, except that a version already committed ahead of
 * it stays (plugin 196 lowered a committed ike-build-report-extension 262 to 261;
 * IKE-Network/ike-issues#1261). The plugin's own targets are whatever this build filtered
 * into its properties, so the tests read them through the reconciler rather than assume them.
 */
class ExtensionsXmlReconcilerTest {

    @TempDir
    Path tempDir;

    private final ExtensionsXmlReconciler reconciler = new ExtensionsXmlReconciler();

    private Path extensionsXml(String wsVersion, String reportVersion, String versionsVersion) throws Exception {
        Path mvn = tempDir.resolve(".mvn");
        Files.createDirectories(mvn);
        Path xml = mvn.resolve("extensions.xml");
        Files.writeString(xml, "<extensions>\n</extensions>\n", StandardCharsets.UTF_8);
        WorkspaceBootstrap.refreshExtensionsManagedBlock(xml, wsVersion, reportVersion, versionsVersion);
        return xml;
    }

    private WorkspaceContext ctx() {
        return new WorkspaceContext(tempDir.toFile(), tempDir.resolve("workspace.yaml"), null,
                ReconcilerOptions.empty(), new TestLog());
    }

    private static String pluginTarget(String key) {
        return ExtensionsXmlReconciler.resolveVersion(key, "0");
    }

    @Test
    void aCommittedVersionAheadOfThePluginIsNotDrift_andStays() throws Exception {
        String ahead = Integer.toString(Integer.parseInt(pluginTarget("ike-build-report-extension.version")) + 1);
        Path xml = extensionsXml(pluginTarget("ike-workspace-extension.version"), ahead,
                pluginTarget("ike-version-management-extension.version"));
        assertThat(reconciler.detect(ctx()).hasDrift())
                .as("a pin ahead of the plugin's own is not drift")
                .isFalse();
        reconciler.apply(ctx());
        String content = Files.readString(xml, StandardCharsets.UTF_8);
        assertThat(ExtensionsXmlReconciler.committedVersion(content, "ike-build-report-extension")).isEqualTo(ahead);
    }

    @Test
    void aCommittedVersionBehindThePluginIsRaised() throws Exception {
        Path xml = extensionsXml("1", "1", "1");
        assertThat(reconciler.detect(ctx()).hasDrift()).isTrue();
        reconciler.apply(ctx());
        String content = Files.readString(xml, StandardCharsets.UTF_8);
        assertThat(ExtensionsXmlReconciler.committedVersion(content, "ike-build-report-extension"))
                .isEqualTo(pluginTarget("ike-build-report-extension.version"));
        assertThat(ExtensionsXmlReconciler.committedVersion(content, "ike-workspace-extension"))
                .isEqualTo(pluginTarget("ike-workspace-extension.version"));
        assertThat(content).doesNotContain("\r");
        assertThat(reconciler.detect(ctx()).hasDrift()).isFalse();
    }

    @Test
    void aCrlfFileIsRewrittenWithLf() throws Exception {
        Path xml = extensionsXml("1", "1", "1");
        Files.writeString(xml, Files.readString(xml, StandardCharsets.UTF_8).replace("\n", "\r\n"), StandardCharsets.UTF_8);
        reconciler.apply(ctx());
        assertThat(Files.readString(xml, StandardCharsets.UTF_8)).doesNotContain("\r");
    }

    @Test
    void versionsCompareAsBuildNumbers() {
        assertThat(ExtensionsXmlReconciler.compareVersions("262", "261")).isPositive();
        assertThat(ExtensionsXmlReconciler.compareVersions("9", "10")).isNegative();
        assertThat(ExtensionsXmlReconciler.target("<artifactId>x</artifactId><version>262</version>", "x", "261")).isEqualTo("262");
        assertThat(ExtensionsXmlReconciler.target("<artifactId>x</artifactId><version>200</version>", "x", "261")).isEqualTo("261");
        assertThat(ExtensionsXmlReconciler.target("", "x", "261")).isEqualTo("261");
    }
}
