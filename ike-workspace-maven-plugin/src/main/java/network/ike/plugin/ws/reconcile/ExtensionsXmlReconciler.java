package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ws.bootstrap.WorkspaceBootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Keeps the managed extension entries in {@code .mvn/extensions.xml} —
 * {@code ike-workspace-extension} (IKE-Network/ike-issues#460),
 * {@code ike-build-report-extension} (IKE-Network/ike-issues#978) and
 * {@code ike-version-management-extension} (IKE-Network/ike-issues#1094)
 * — in lockstep with the version properties declared on the platform.
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
        return ".mvn/extensions.xml managed extension versions";
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
        String wsTarget = resolveVersion("ike-workspace-extension.version", "1");
        String reportTarget = resolveVersion("ike-build-report-extension.version", "244");
        String versionsTarget =
                resolveVersion("ike-version-management-extension.version", "11");
        String targets = describeTargets(wsTarget, reportTarget, versionsTarget);
        try {
            String existing = Files.readString(xml);
            if (matchesTargets(existing,
                    "ike-workspace-extension", wsTarget,
                    "ike-build-report-extension", reportTarget,
                    "ike-version-management-extension", versionsTarget)) {
                return DriftReport.noDrift(dimension());
            }
            return new DriftReport(
                    dimension(),
                    true,
                    "managed extension entries not at " + targets,
                    List.of(EXTENSIONS_XML + ": rewrite managed block to " + targets),
                    "rewrite the managed block in " + EXTENSIONS_XML + " to " + targets,
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
        String wsTarget = resolveVersion("ike-workspace-extension.version", "1");
        String reportTarget = resolveVersion("ike-build-report-extension.version", "244");
        String versionsTarget =
                resolveVersion("ike-version-management-extension.version", "11");
        try {
            boolean refreshed = WorkspaceBootstrap.refreshExtensionsManagedBlock(
                    xml, wsTarget, reportTarget, versionsTarget);
            if (refreshed) {
                ctx.log().info("  ✓ " + EXTENSIONS_XML + " → "
                        + describeTargets(wsTarget, reportTarget, versionsTarget));
            }
        } catch (IOException e) {
            ctx.log().warn(dimension() + ": refresh failed: " + e.getMessage());
        }
    }

    /** One line naming every managed entry at its target version. */
    private static String describeTargets(String wsTarget, String reportTarget,
                                          String versionsTarget) {
        return "ike-workspace-extension:" + wsTarget
                + " + ike-build-report-extension:" + reportTarget
                + " + ike-version-management-extension:" + versionsTarget;
    }

    private static boolean matchesTargets(String content,
                                          String artifactIdA, String targetA,
                                          String artifactIdB, String targetB,
                                          String artifactIdC, String targetC) {
        int begin = content.indexOf(WorkspaceBootstrap.EXTENSIONS_MANAGED_BEGIN);
        if (begin < 0) {
            return false;
        }
        int end = content.indexOf(WorkspaceBootstrap.EXTENSIONS_MANAGED_END, begin);
        if (end < 0) {
            return false;
        }
        String block = content.substring(begin, end);
        return containsEntryAt(block, artifactIdA, targetA)
                && containsEntryAt(block, artifactIdB, targetB)
                && containsEntryAt(block, artifactIdC, targetC);
    }

    /**
     * Checks that the managed block carries the named artifact with the
     * target version literal immediately following it — a per-entry
     * match, so one entry's version cannot satisfy the other's check.
     */
    private static boolean containsEntryAt(String block, String artifactId, String target) {
        int at = block.indexOf("<artifactId>" + artifactId + "</artifactId>");
        if (at < 0) {
            return false;
        }
        int versionAt = block.indexOf("<version>" + target + "</version>", at);
        int nextEntry = block.indexOf("<artifactId>", at + artifactId.length());
        return versionAt > at && (nextEntry < 0 || versionAt < nextEntry);
    }

    private static String resolveVersion(String key, String fallback) {
        try (InputStream is = ExtensionsXmlReconciler.class
                .getResourceAsStream(PROPERTIES_RESOURCE)) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String v = props.getProperty(key);
                if (v != null && !v.isBlank() && !v.startsWith("${")) {
                    return v;
                }
            }
        } catch (IOException e) {
            // Fall through to fallback.
        }
        // Fallback for tests / unfiltered classpath.
        return fallback;
    }
}
