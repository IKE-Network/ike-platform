package network.ike.plugin.ws.reconcile;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.Subproject;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconciler that keeps the denormalized {@code workspace.yaml}
 * fields ({@code version}, {@code groupId}) in sync with each cloned
 * subproject's POM.
 *
 * <p>Subsumes the retired {@code ws:fix} goal (IKE-Network/ike-issues#393).
 * The Maven coordinates in {@code workspace.yaml} are denormalized
 * convenience — the authoritative source is each subproject's
 * {@code pom.xml}. This reconciler detects when the manifest has
 * drifted from POM truth and applies the corrections.
 *
 * <p>Only {@code version} and {@code groupId} are touched. Novel
 * fields ({@code repo}, {@code branch}, {@code type}, {@code depends-on})
 * are never modified by this reconciler.
 *
 * <p>Subprojects that are not yet cloned are silently skipped — they
 * have no POM to compare against.
 */
public class FieldNormalizationReconciler implements Reconciler {

    private static final Pattern GROUP_ID_PATTERN =
            Pattern.compile("<groupId>([^<]+)</groupId>");

    @Override
    public String dimension() {
        return "Denormalized YAML fields (version, groupId)";
    }

    @Override
    public String optOutFlag() {
        return "updateFields";
    }

    @Override
    public DriftReport detect(WorkspaceContext ctx) {
        Drift drift = computeDrift(ctx);
        if (drift.versionUpdates.isEmpty() && drift.groupIdUpdates.isEmpty()) {
            return DriftReport.noDrift(dimension());
        }

        List<String> detail = new ArrayList<>();
        for (Map.Entry<String, String[]> e : drift.versionUpdates.entrySet()) {
            String name = e.getKey();
            String[] beforeAfter = e.getValue();
            detail.add(name + ": version "
                    + (beforeAfter[0] == null ? "(null)" : beforeAfter[0])
                    + " → " + beforeAfter[1]);
        }
        for (Map.Entry<String, String[]> e : drift.groupIdUpdates.entrySet()) {
            String name = e.getKey();
            String[] beforeAfter = e.getValue();
            detail.add(name + ": groupId "
                    + (beforeAfter[0] == null || beforeAfter[0].isEmpty()
                            ? "(empty)" : beforeAfter[0])
                    + " → " + beforeAfter[1]);
        }

        int total = drift.versionUpdates.size() + drift.groupIdUpdates.size();
        String summary = total + " field(s) drift from POM truth";
        String action = "sync from POM truth on scaffold-publish";
        String optOut = "mvn ws:scaffold-publish -D" + optOutFlag() + "=false";

        return new DriftReport(dimension(), true, summary, detail, action, optOut);
    }

    @Override
    public void apply(WorkspaceContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension() + ": skipped (opted out via -D"
                    + optOutFlag() + "=false)");
            return;
        }
        Drift drift = computeDrift(ctx);
        if (drift.versionUpdates.isEmpty() && drift.groupIdUpdates.isEmpty()) {
            return;
        }
        try {
            if (!drift.versionUpdates.isEmpty()) {
                Map<String, String> versionValues = new LinkedHashMap<>();
                for (Map.Entry<String, String[]> e : drift.versionUpdates.entrySet()) {
                    versionValues.put(e.getKey(), e.getValue()[1]);
                }
                writeVersionFields(ctx.manifestPath(), versionValues);
            }
            if (!drift.groupIdUpdates.isEmpty()) {
                Map<String, String> groupIdValues = new LinkedHashMap<>();
                for (Map.Entry<String, String[]> e : drift.groupIdUpdates.entrySet()) {
                    groupIdValues.put(e.getKey(), e.getValue()[1]);
                }
                writeGroupIdFields(ctx.manifestPath(), groupIdValues);
            }
            int total = drift.versionUpdates.size() + drift.groupIdUpdates.size();
            ctx.log().info("  " + dimension() + ": updated " + total + " field(s)");
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to update workspace.yaml: " + e.getMessage(), e);
        }
    }

    // ── Drift computation (shared by detect and apply) ──────────────

    /**
     * Per-subproject "before, after" pairs for each denormalized field
     * that drifts from POM truth. Used as a shared intermediate
     * between {@link #detect} and {@link #apply} so they agree on
     * exactly which subprojects are affected.
     */
    private record Drift(
            Map<String, String[]> versionUpdates,
            Map<String, String[]> groupIdUpdates) {}

    private Drift computeDrift(WorkspaceContext ctx) {
        Map<String, String[]> versionUpdates = new LinkedHashMap<>();
        Map<String, String[]> groupIdUpdates = new LinkedHashMap<>();
        for (Map.Entry<String, Subproject> entry
                : ctx.graph().manifest().subprojects().entrySet()) {
            String name = entry.getKey();
            Subproject subproject = entry.getValue();
            File subprojectDir = new File(ctx.workspaceRoot(), name);
            File pomFile = new File(subprojectDir, "pom.xml");
            if (!pomFile.exists()) continue;

            try {
                String pomVersion = ReleaseSupport.readPomVersion(pomFile);
                String yamlVersion = subproject.version();
                if (yamlVersion != null && !yamlVersion.equals(pomVersion)) {
                    versionUpdates.put(name, new String[]{yamlVersion, pomVersion});
                } else if (yamlVersion == null && pomVersion != null) {
                    versionUpdates.put(name, new String[]{null, pomVersion});
                }
            } catch (MojoException e) {
                ctx.log().warn("  " + name + ": could not read POM version — "
                        + e.getMessage());
            }

            try {
                String pomGroupId = readPomGroupId(pomFile);
                String yamlGroupId = subproject.groupId();
                if (pomGroupId != null && !pomGroupId.isEmpty()) {
                    if (yamlGroupId == null || yamlGroupId.isEmpty()
                            || !yamlGroupId.equals(pomGroupId)) {
                        groupIdUpdates.put(name,
                                new String[]{yamlGroupId, pomGroupId});
                    }
                }
            } catch (MojoException e) {
                ctx.log().warn("  " + name + ": could not read POM groupId — "
                        + e.getMessage());
            }
        }
        return new Drift(versionUpdates, groupIdUpdates);
    }

    // ── POM groupId reader (lifted from WsFixMojo) ──────────────────

    /**
     * Read the project's own {@code <groupId>} from a POM file,
     * skipping any {@code <groupId>} inside the {@code <parent>} block.
     * Falls back to the parent's groupId if the project POM does not
     * declare its own.
     *
     * @param pomFile the POM file to read
     * @return the groupId, or null if not found
     * @throws MojoException if the file cannot be read
     */
    static String readPomGroupId(File pomFile) throws MojoException {
        try {
            String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
            String stripped = content.replaceFirst(
                    "(?s)<parent>.*?</parent>", "");
            Matcher matcher = GROUP_ID_PATTERN.matcher(stripped);
            if (matcher.find()) {
                return matcher.group(1);
            }
            Matcher parentMatcher = Pattern.compile(
                    "(?s)<parent>.*?<groupId>([^<]+)</groupId>.*?</parent>"
            ).matcher(content);
            if (parentMatcher.find()) {
                return parentMatcher.group(1);
            }
            return null;
        } catch (IOException e) {
            throw new MojoException("Failed to read " + pomFile, e);
        }
    }

    // ── ManifestWriter wrappers (preserves WsFixMojo semantics) ─────

    private static void writeVersionFields(Path manifestPath,
                                            Map<String, String> updates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            content = ManifestWriter.updateSubprojectField(
                    content, entry.getKey(), "version", entry.getValue());
        }
        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }

    private static void writeGroupIdFields(Path manifestPath,
                                            Map<String, String> updates)
            throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            content = ManifestWriter.updateSubprojectField(
                    content, entry.getKey(), "groupId", entry.getValue());
        }
        Files.writeString(manifestPath, content, StandardCharsets.UTF_8);
    }
}
