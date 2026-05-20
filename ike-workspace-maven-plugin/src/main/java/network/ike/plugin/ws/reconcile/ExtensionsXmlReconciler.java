package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.bootstrap.WorkspaceBootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Keeps the managed {@code ike-workspace-extension} entry in
 * {@code .mvn/extensions.xml} in lockstep with the
 * {@code ike-workspace-extension.version} property declared in
 * {@code ike-parent} (IKE-Network/ike-issues#460).
 *
 * <p>Maven 4 does not interpolate POM properties inside
 * {@code .mvn/extensions.xml} at extension-load time — the version
 * must be a literal string. So the literal is rewritten in place by
 * this reconciler whenever {@code ws:scaffold-publish} runs.
 *
 * <p>On a workspace that predates the managed-block convention (the
 * file is missing the sentinel markers and the extension entry),
 * the reconciler migrates it: it inserts the managed block before the
 * closing {@code </extensions>} tag, preserving any other entries
 * (e.g. {@code wagon-ssh-external}).
 *
 * <p>The extension version is read from {@code ws-plugin.properties}
 * (filtered at build time by Maven from the
 * {@code ike-workspace-extension.version} property in ike-parent).
 */
public class ExtensionsXmlReconciler implements Reconciler {

    private static final String EXTENSIONS_XML = ".mvn/extensions.xml";
    private static final String PROPERTIES_RESOURCE =
            "/network/ike/plugin/ws/ws-plugin.properties";

    @Override
    public String dimension() {
        return ".mvn/extensions.xml ike-workspace-extension version";
    }

    @Override
    public String optOutFlag() {
        return "updateExtensions";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        Path xml = ctx.workspaceRoot().toPath().resolve(EXTENSIONS_XML);
        if (!Files.exists(xml)) {
            return DriftReport.noDrift(dimension());
        }
        String target = resolveExtensionVersion();
        try {
            String existing = Files.readString(xml);
            if (matchesTarget(existing, target)) {
                return DriftReport.noDrift(dimension());
            }
            return new DriftReport(
                    dimension(),
                    true,
                    "ike-workspace-extension entry not at " + target,
                    List.of(EXTENSIONS_XML
                            + ": rewrite managed block to <version>"
                            + target + "</version>"),
                    "rewrite the managed block in " + EXTENSIONS_XML
                            + " to ike-workspace-extension:" + target,
                    "-D" + optOutFlag() + "=false");
        } catch (IOException e) {
            return DriftReport.noDrift(dimension());
        }
    }

    @Override
    public void apply(WorkspaceContext ctx) {
        Path xml = ctx.workspaceRoot().toPath().resolve(EXTENSIONS_XML);
        if (!Files.exists(xml)) {
            ctx.log().debug(dimension() + ": no " + EXTENSIONS_XML + " — skipping");
            return;
        }
        String target = resolveExtensionVersion();
        try {
            boolean refreshed = WorkspaceBootstrap
                    .refreshExtensionsManagedBlock(xml, target);
            if (refreshed) {
                ctx.log().info("  ✓ " + EXTENSIONS_XML
                        + " → ike-workspace-extension:" + target);
            }
        } catch (IOException e) {
            ctx.log().warn(dimension() + ": refresh failed: " + e.getMessage());
        }
    }

    private static boolean matchesTarget(String content, String target) {
        int begin = content.indexOf(WorkspaceBootstrap.EXTENSIONS_MANAGED_BEGIN);
        if (begin < 0) {
            return false;
        }
        int end = content.indexOf(WorkspaceBootstrap.EXTENSIONS_MANAGED_END, begin);
        if (end < 0) {
            return false;
        }
        String block = content.substring(begin, end);
        return block.contains("<version>" + target + "</version>");
    }

    private static String resolveExtensionVersion() {
        try (var is = ExtensionsXmlReconciler.class
                .getResourceAsStream(PROPERTIES_RESOURCE)) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String v = props.getProperty("ike-workspace-extension.version");
                if (v != null && !v.isBlank() && !v.startsWith("${")) {
                    return v;
                }
            }
        } catch (IOException e) {
            // Fall through to fallback.
        }
        // Fallback for tests / unfiltered classpath.
        return "1";
    }
}
