package network.ike.plugin.ws;

import org.apache.maven.api.plugin.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Per-goal report writer for {@code ws:*} goals.
 *
 * <p>Each goal writes its own file directly at the workspace root
 * (alongside {@code workspace.yaml} and the aggregator {@code pom.xml}).
 * Files are <b>overwritten</b> on each run (not appended), so the
 * content always reflects the latest execution.
 *
 * <p>Filenames use {@code ꞉} (U+A789 MODIFIER LETTER COLON) to cluster
 * visually as {@code ws꞉goal-name.md} in IDE file browsers. For
 * draft/publish goals, the filename includes the variant:
 * {@code ws꞉feature-start-draft.md}, {@code ws꞉feature-start-publish.md}.
 *
 * <p><strong>Self-healing gitignore:</strong> before writing, this class
 * ensures {@code ws꞉*.md} is listed in the {@code .gitignore} of the
 * nearest {@code .git} ancestor. If the pattern is missing, it is
 * appended. This keeps reports out of git without any manual setup —
 * a fresh clone of a workspace becomes report-ready the first time a
 * {@code ws:*} goal runs.
 *
 * <p>Parallels {@code network.ike.plugin.IkeReport} in the ike plugin;
 * both writers now target their respective project roots.
 */
public final class WorkspaceReport {

    /** U+A789 MODIFIER LETTER COLON — filesystem-safe visual colon. */
    private static final char COLON = '\uA789';

    /**
     * Glob appended to {@code .gitignore} when missing. Matches every
     * {@code ws꞉*.md} report at the workspace root.
     */
    static final String GITIGNORE_PATTERN = "ws\uA789*.md";

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WorkspaceReport() {}

    /**
     * Write a goal's report to its per-goal file at the workspace root,
     * overwriting any previous content. Self-heals the nearest
     * {@code .gitignore} so the report does not land in git.
     *
     * @param workspaceRoot the workspace root directory
     * @param goalName      the goal name including variant (e.g., "ws:feature-start-draft")
     * @param content       the markdown content to write
     * @param log           Maven logger (warnings only; null-safe)
     */
    public static void write(Path workspaceRoot, String goalName,
                              String content, Log log) {
        String filename = "ws" + COLON + stripPrefix(goalName) + ".md";
        Path reportFile = workspaceRoot.resolve(filename);

        try {
            ensureGitignored(workspaceRoot, log);

            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            String fullContent = "# " + goalName + "\n"
                    + "_" + timestamp + "_\n\n"
                    + content.stripTrailing() + "\n";

            Files.writeString(reportFile, fullContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (log != null) {
                log.debug("Could not write report " + filename + ": "
                        + e.getMessage());
            }
        }
    }

    /**
     * Resolve the report file path for a specific goal.
     *
     * @param workspaceRoot the workspace root directory
     * @param goalName      the goal name (e.g., "ws:overview")
     * @return path to the report file at the workspace root (may not exist yet)
     */
    public static Path reportPath(Path workspaceRoot, String goalName) {
        String filename = "ws" + COLON + stripPrefix(goalName) + ".md";
        return workspaceRoot.resolve(filename);
    }

    /**
     * Open the workspace root in the default file manager or IDE so
     * the user can browse the {@code ws꞉*.md} reports alongside
     * {@code workspace.yaml} and the aggregator {@code pom.xml}.
     *
     * @param workspaceRoot the workspace root directory
     * @param log           Maven logger
     * @return {@code true} if opened successfully
     */
    public static boolean openInBrowser(Path workspaceRoot, Log log) {
        if (!Files.isDirectory(workspaceRoot)) {
            if (log != null) {
                log.warn("Workspace root not found: " + workspaceRoot);
            }
            return false;
        }

        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
                new ProcessBuilder("open", workspaceRoot.toString()).start();
                return true;
            }
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(workspaceRoot.toFile());
                return true;
            }
        } catch (IOException e) {
            if (log != null) {
                log.warn("Could not open workspace root: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Walk up from {@code workspaceRoot} looking for a {@code .git}
     * directory; ensure its sibling {@code .gitignore} lists
     * {@link #GITIGNORE_PATTERN}. If the file is missing, create it.
     * If the pattern is missing, append it. No-op when no {@code .git}
     * ancestor is found (e.g. the workspace is not yet in a git repo —
     * pipeline-ws itself is a syncthing folder rather than a git repo).
     *
     * @param workspaceRoot the workspace root to search from
     * @param log           Maven logger (null-safe)
     * @throws IOException if the gitignore file cannot be read or written
     */
    static void ensureGitignored(Path workspaceRoot, Log log)
            throws IOException {
        Path gitRoot = findGitRoot(workspaceRoot);
        if (gitRoot == null) return;

        Path gitignore = gitRoot.resolve(".gitignore");
        if (Files.exists(gitignore)) {
            List<String> lines = Files.readAllLines(gitignore,
                    StandardCharsets.UTF_8);
            for (String line : lines) {
                if (matchesPattern(line.trim(), GITIGNORE_PATTERN)) {
                    return;
                }
            }
            String existing = Files.readString(gitignore,
                    StandardCharsets.UTF_8);
            String appended = existing.endsWith("\n") ? existing
                    : existing + "\n";
            Files.writeString(gitignore,
                    appended + "\n# ws:* goal reports\n"
                            + GITIGNORE_PATTERN + "\n",
                    StandardCharsets.UTF_8);
        } else {
            Files.writeString(gitignore,
                    "# ws:* goal reports\n"
                            + GITIGNORE_PATTERN + "\n",
                    StandardCharsets.UTF_8);
        }
        if (log != null) {
            log.info("Added " + GITIGNORE_PATTERN
                    + " to " + gitRoot.relativize(gitignore));
        }
    }

    /**
     * Walk up from {@code start} looking for a directory that contains
     * a {@code .git} entry (directory or file — the latter for
     * worktrees and submodules).
     *
     * @param start the starting directory for the search
     * @return the git root directory, or {@code null} if none is found
     */
    private static Path findGitRoot(Path start) {
        Path current = start.toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) return current;
            current = current.getParent();
        }
        return null;
    }

    /**
     * Test whether a {@code .gitignore} line matches the given pattern
     * after normalizing leading slashes and skipping comments or blanks.
     *
     * @param line    the {@code .gitignore} line, already trimmed
     * @param pattern the normalized pattern to match against
     * @return {@code true} if the line covers the pattern
     */
    private static boolean matchesPattern(String line, String pattern) {
        if (line.isEmpty() || line.startsWith("#")) return false;
        String normalized = line.startsWith("/") ? line.substring(1) : line;
        return normalized.equals(pattern);
    }

    /**
     * Strip the "ws:" prefix from a goal name for use in filenames.
     * "ws:overview" → "overview", "ws:feature-start-draft" → "feature-start-draft"
     */
    private static String stripPrefix(String goalName) {
        if (goalName.startsWith("ws:")) {
            return goalName.substring(3);
        }
        return goalName;
    }
}
