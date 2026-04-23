package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.workspace.WorkspaceGraph;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only diagnostic for any in-flight or partial workspace release.
 *
 * <p>This goal performs no mutations. It walks every checked-out
 * subproject in {@code workspace.yaml}, collects git artifacts that
 * indicate an interrupted release ({@code release/*} branches and
 * unpushed {@code v*} tags), and prints a punch list with one line
 * per subproject. The footer recommends a next action — typically
 * pointing at {@code IKE-RELEASE-RECOVERY.md} for the matching state.
 *
 * <p>Inference rules live in {@link ReleaseStatusInspector}; this
 * mojo is a thin shell that supplies the git observations and
 * formats the output. The split keeps the rules unit-testable
 * without spinning up a real git repository for each scenario.
 *
 * <p><strong>Cycle 1 of #187</strong>. The git-only inference here
 * is deliberately conservative — it cannot tell <em>why</em> a
 * release was interrupted, only that artifacts remain. Cycle 2 will
 * add a {@code .ike/release-state.json} written by
 * {@link WsReleaseDraftMojo} at phase boundaries; this goal will
 * then merge git evidence with the state file for richer findings.
 *
 * <pre>{@code
 * mvn ws:release-status     # punch list of every subproject's release state
 * }</pre>
 *
 * @see ReleaseStatusInspector
 * @see WsReleaseDraftMojo
 */
@Mojo(name = "release-status", projectRequired = false)
public class WsReleaseStatusMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public WsReleaseStatusMojo() {}

    @Override
    public void execute() throws MojoException {
        ReportLog report = startReport();
        try {
            WorkspaceGraph graph = loadGraph();
            File root = workspaceRoot();

            getLog().info("");
            getLog().info(header("Release status"));
            getLog().info("══════════════════════════════════════════════════════════════");
            getLog().info("");

            List<String> order = graph.topologicalSort();
            List<ReleaseStatusInspector.Finding> findings = new ArrayList<>();
            for (String name : order) {
                File subDir = new File(root, name);
                ReleaseStatusInspector.Observation obs = observe(name, subDir);
                ReleaseStatusInspector.Finding finding =
                        ReleaseStatusInspector.classify(obs);
                findings.add(finding);
                renderFinding(finding);
            }

            getLog().info("");
            renderFooter(findings);
            writeReport(WsGoal.RELEASE_STATUS, buildMarkdownReport(findings));
        } finally {
            finishReport(WsGoal.RELEASE_STATUS, report);
        }
    }

    // ── Observation: real git interaction ────────────────────────────

    /**
     * Collect a {@link ReleaseStatusInspector.Observation} for one
     * subproject by running git subprocesses. Marked package-private
     * so tests can call it directly against a fixture repo.
     *
     * @param name   the subproject name from {@code workspace.yaml}
     * @param subDir the on-disk subproject directory
     * @return the populated observation snapshot
     */
    static ReleaseStatusInspector.Observation observe(String name, File subDir) {
        boolean checkedOut = subDir.isDirectory()
                && new File(subDir, ".git").exists();
        if (!checkedOut) {
            return new ReleaseStatusInspector.Observation(
                    name, false, "unknown", "unknown",
                    List.of(), Set.of(), Set.of(), false);
        }

        String version = readPomVersion(subDir);
        String branch = safeCurrentBranch(subDir);
        List<String> releaseBranches = VcsOperations.localBranches(subDir, "release/");
        Set<String> localTags = listLocalTags(subDir);
        Set<String> remoteTags;
        boolean remoteReachable;
        Optional<Set<String>> remote = listRemoteTags(subDir);
        if (remote.isPresent()) {
            remoteTags = remote.get();
            remoteReachable = true;
        } else {
            remoteTags = Set.of();
            remoteReachable = false;
        }

        return new ReleaseStatusInspector.Observation(
                name, true, version, branch,
                releaseBranches, localTags, remoteTags, remoteReachable);
    }

    private static String readPomVersion(File subDir) {
        File pom = new File(subDir, "pom.xml");
        if (!pom.exists()) {
            return "unknown";
        }
        try {
            String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
            return WsReleaseDraftMojo.extractVersionFromPom(content);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String safeCurrentBranch(File subDir) {
        try {
            return VcsOperations.currentBranch(subDir);
        } catch (MojoException e) {
            return "unknown";
        }
    }

    /**
     * List local tags matching {@code v*}. Returns an empty set on
     * failure rather than throwing — the diagnostic is best-effort.
     */
    private static Set<String> listLocalTags(File subDir) {
        try {
            String output = ReleaseSupport.execCapture(subDir,
                    "git", "tag", "-l", "v*");
            if (output == null || output.isBlank()) return Set.of();
            return new LinkedHashSet<>(List.of(output.split("\n")));
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * List remote tags matching {@code v*} on {@code origin}. Returns
     * empty when {@code origin} is unreachable so the inspector can
     * suppress false-positive local-only-tag warnings.
     */
    private static Optional<Set<String>> listRemoteTags(File subDir) {
        try {
            String output = ReleaseSupport.execCapture(subDir,
                    "git", "ls-remote", "--tags", "origin", "v*");
            if (output == null || output.isBlank()) {
                return Optional.of(Set.of());
            }
            Set<String> tags = new LinkedHashSet<>();
            for (String line : output.split("\n")) {
                // ls-remote --tags output: <sha>\trefs/tags/<name>[^{}]
                int slash = line.lastIndexOf('/');
                if (slash < 0) continue;
                String tag = line.substring(slash + 1);
                if (tag.endsWith("^{}")) {
                    tag = tag.substring(0, tag.length() - 3);
                }
                tags.add(tag);
            }
            return Optional.of(tags);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── Output formatting ────────────────────────────────────────────

    private void renderFinding(ReleaseStatusInspector.Finding f) {
        String header = String.format("  %s  %-28s  %-12s  %s",
                f.status().badge(),
                f.subprojectName(),
                f.status().label(),
                "v=" + f.currentVersion()
                        + (f.currentBranch() == null
                            ? "" : " · branch=" + f.currentBranch()));
        switch (f.status()) {
            case CLEAN -> getLog().info(Ansi.green(header));
            case IN_FLIGHT -> getLog().warn(Ansi.yellow(header));
            case DIVERGED -> getLog().error(Ansi.red(header));
            case ABSENT -> getLog().info(header);
        }
        for (String detail : f.details()) {
            getLog().info("      " + detail);
        }
    }

    private void renderFooter(List<ReleaseStatusInspector.Finding> findings) {
        Map<ReleaseStatusInspector.Status, Integer> counts =
                new LinkedHashMap<>();
        for (var s : ReleaseStatusInspector.Status.values()) counts.put(s, 0);
        for (var f : findings) counts.merge(f.status(), 1, Integer::sum);

        int inFlight = counts.get(ReleaseStatusInspector.Status.IN_FLIGHT);
        int diverged = counts.get(ReleaseStatusInspector.Status.DIVERGED);
        int clean = counts.get(ReleaseStatusInspector.Status.CLEAN);
        int absent = counts.get(ReleaseStatusInspector.Status.ABSENT);

        getLog().info("Summary: "
                + clean + " clean, "
                + inFlight + " in-flight, "
                + diverged + " diverged, "
                + absent + " not checked out");
        getLog().info("");

        if (diverged == 0 && inFlight == 0) {
            getLog().info(Ansi.green(
                    "  ✓ No interrupted releases detected."));
            return;
        }

        getLog().info("Next action:");
        if (diverged > 0) {
            getLog().warn("  • Diverged subprojects need manual cleanup — the");
            getLog().warn("    release completed elsewhere. See "
                    + "IKE-RELEASE-RECOVERY.md → 'Diverged'.");
        }
        if (inFlight > 0) {
            getLog().warn("  • In-flight subprojects have unfinished release"
                    + " artifacts. See IKE-RELEASE-RECOVERY.md →"
                    + " 'In-flight: forward-fix vs rollback'.");
        }
        getLog().info("");
        getLog().info("  ws:release-resume / ws:release-rollback are not yet"
                + " implemented (Cycle 2+ of #187). Recover with the manual"
                + " git steps in IKE-RELEASE-RECOVERY.md until they ship.");
    }

    private String buildMarkdownReport(
            List<ReleaseStatusInspector.Finding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Subproject | Status | Version | Branch | Notes |\n");
        sb.append("|-----------|--------|---------|--------|-------|\n");
        for (ReleaseStatusInspector.Finding f : findings) {
            sb.append("| ").append(f.subprojectName())
                    .append(" | ").append(f.status().badge())
                    .append(' ').append(f.status().label())
                    .append(" | ").append(f.currentVersion())
                    .append(" | ").append(f.currentBranch())
                    .append(" | ").append(String.join("<br>", f.details()))
                    .append(" |\n");
        }
        return sb.toString();
    }
}
