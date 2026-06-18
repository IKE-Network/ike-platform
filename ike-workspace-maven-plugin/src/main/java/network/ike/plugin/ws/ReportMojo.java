package network.ike.plugin.ws;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * List and open the {@code ws꞉*.md} goal reports at the workspace root.
 *
 * <p>Each {@code ws:*} goal writes its latest output to a per-goal file
 * alongside {@code workspace.yaml} and the aggregator {@code pom.xml}
 * (for example {@code ws꞉overview.md}, {@code ws꞉release-draft.md}).
 * This goal lists those reports newest-first and opens the workspace
 * root in the default file manager so you can browse them.
 *
 * <p><strong>Single repo (no {@code workspace.yaml})</strong>: lists the
 * {@code ws꞉*.md} reports at the current repository's root — a working set
 * of one (IKE-Network/ike-issues#704).
 *
 * <p>Usage:
 * <pre>
 *   mvn ws:report                    # list and open
 *   mvn ws:report -Dws.report.printOnly=true   # list only
 * </pre>
 */
@Mojo(name = "report", projectRequired = false, aggregator = true)
public class ReportMojo extends AbstractWorkspaceMojo {

    /** Prefix shared by every {@code ws:*} goal report filename. */
    private static final String REPORT_PREFIX = "ws\uA789";
    /** Suffix shared by every {@code ws:*} goal report filename. */
    private static final String REPORT_SUFFIX = ".md";

    /** Creates this goal instance. */
    public ReportMojo() {}

    /**
     * Skip opening the file manager; just print the paths.
     */
    @Parameter(property = "ws.report.printOnly", defaultValue = "false")
    private boolean printOnly;

    @Override
    protected WorkspaceReportSpec runGoal() throws MojoException {
        // Scope the report scan to the working set's root — the workspace
        // root, or a single repository (a working set of one). Previously
        // anchored on workspaceRoot(), which threw outside a workspace
        // (IKE-Network/ike-issues#704).
        Path root = resolveWorkingSet().root();
        List<Path> reports = findReports(root);

        if (reports.isEmpty()) {
            getLog().info("No ws꞉*.md reports at " + root
                    + ". Run a ws: goal first.");
            return new WorkspaceReportSpec(WsGoal.REPORT,
                    "No `ws꞉*.md` goal reports at the workspace root yet.\n");
        }

        getLog().info("Workspace reports at " + root + ":");
        StringBuilder body = new StringBuilder();
        body.append(reports.size()).append(" goal report(s) at the workspace root, "
                + "newest first:\n\n");
        for (Path report : reports) {
            getLog().info("  " + root.relativize(report));
            body.append("- `").append(root.relativize(report)).append("`\n");
        }

        if (!printOnly) {
            boolean opened = WorkspaceReport.openInBrowser(root, getLog());
            if (opened) {
                getLog().info("Opened workspace root in file manager.");
            } else {
                getLog().info("Could not open file manager — browse directly: "
                        + root);
            }
        }

        return new WorkspaceReportSpec(WsGoal.REPORT, body.toString());
    }

    /**
     * List {@code ws꞉*.md} files at the workspace root sorted newest-first.
     *
     * @param root the workspace root directory
     * @return reports sorted by modification time, newest first; empty if
     *         the directory is missing or contains no matching files
     */
    private static List<Path> findReports(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(ReportMojo::isReportFile)
                    .sorted(Comparator.comparing(ReportMojo::lastModified)
                            .reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isReportFile(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(REPORT_PREFIX) && name.endsWith(REPORT_SUFFIX);
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0L);
        }
    }
}
