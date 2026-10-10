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
 * <p><strong>Kept out of git without touching the tree:</strong> before
 * writing, this class checks that {@code ws꞉*.md} is listed in the
 * {@code .gitignore} of the nearest {@code .git} ancestor. The committed
 * glob is the scaffold convention's business (the workspace reconciler
 * commits it); when it is missing, the pattern goes into the repository's
 * local {@code .git/info/exclude}, which git honours and never tracks, so
 * a report never leaves an uncommitted change behind for the next publish
 * to refuse (IKE-Network/ike-issues#1283, #1109). A fresh clone becomes
 * report-ready the first time a {@code ws:*} goal runs, either way.
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
     * Write a goal's report to its per-goal file at the working set's root,
     * overwriting any previous content, and keep it out of git without
     * modifying the tracked tree (see {@link #ensureGitignored}).
     *
     * @param workspaceRoot the working set's root: the workspace root, or
     *                      the repository in a working set of one
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
                    + "_" + timestamp + " · ike-workspace-maven-plugin "
                    + pluginVersion() + "_\n\n"
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
     * Walk up from {@code workspaceRoot} looking for a {@code .git} entry
     * and make sure {@link #GITIGNORE_PATTERN} is ignored there: by the
     * sibling {@code .gitignore} when it already lists the glob, otherwise
     * by the repository's local {@code .git/info/exclude}, created or
     * appended as needed, so the tracked tree is never modified
     * (IKE-Network/ike-issues#1283). When {@code .git} is a file (a linked
     * worktree or a submodule) there is no local exclude beside it, and the
     * glob is appended to {@code .gitignore} as before. No-op when no
     * {@code .git} ancestor is found (the folder is not yet a repository).
     *
     * @param workspaceRoot the workspace root to search from
     * @param log           Maven logger (null-safe)
     * @throws IOException if an ignore file cannot be read or written
     */
    static void ensureGitignored(Path workspaceRoot, Log log)
            throws IOException {
        Path gitRoot = findGitRoot(workspaceRoot);
        if (gitRoot == null) return;

        Path gitignore = gitRoot.resolve(".gitignore");
        if (Files.exists(gitignore)
                && listsPattern(gitignore, GITIGNORE_PATTERN)) {
            return;
        }

        Path gitEntry = gitRoot.resolve(".git");
        if (Files.isDirectory(gitEntry)) {
            Path exclude = gitEntry.resolve("info").resolve("exclude");
            if (Files.exists(exclude)
                    && listsPattern(exclude, GITIGNORE_PATTERN)) {
                return;
            }
            Files.createDirectories(exclude.getParent());
            String existing = Files.exists(exclude)
                    ? Files.readString(exclude, StandardCharsets.UTF_8) : "";
            String appended = existing.isEmpty() || existing.endsWith("\n")
                    ? existing : existing + "\n";
            Files.writeString(exclude,
                    appended + "# ws:* goal reports (local; the scaffold "
                            + "convention commits the glob to .gitignore)\n"
                            + GITIGNORE_PATTERN + "\n",
                    StandardCharsets.UTF_8);
            if (log != null) {
                log.info(GITIGNORE_PATTERN + " is not in .gitignore — "
                        + "excluded locally in .git/info/exclude");
            }
            return;
        }

        if (Files.exists(gitignore)) {
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
     * Whether an ignore file lists the pattern, comments and blanks
     * skipped, a leading slash tolerated.
     *
     * @param ignoreFile the {@code .gitignore} or {@code exclude} file
     * @param pattern    the normalized pattern
     * @return {@code true} when a line covers the pattern
     * @throws IOException if the file cannot be read
     */
    private static boolean listsPattern(Path ignoreFile, String pattern)
            throws IOException {
        List<String> lines = Files.readAllLines(ignoreFile,
                StandardCharsets.UTF_8);
        for (String line : lines) {
            if (matchesPattern(line.trim(), pattern)) {
                return true;
            }
        }
        return false;
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
     * Read the plugin version from the JAR manifest's
     * {@code Implementation-Version} attribute (set by
     * {@code maven-jar-plugin}'s {@code addDefaultImplementationEntries}).
     * Reports persist across plugin upgrades, so embedding the version
     * in the header lets a reader tell which release generated an old
     * file when the goal's output format or semantics has shifted.
     *
     * @return the plugin version, or {@code "(unknown)"} when the
     *         manifest attribute is absent (e.g. during in-IDE test runs)
     */
    static String pluginVersion() {
        String v = WorkspaceReport.class.getPackage()
                .getImplementationVersion();
        return v != null ? v : "(unknown)";
    }

    /**
     * Strip the "ws:" prefix from a goal name for use in filenames.
     * "ws:overview" → "overview", "ws:feature-start-draft" → "feature-start-draft"
     */
    private static String stripPrefix(String goalName) {
        String prefix = WsGoal.PLUGIN_PREFIX + ":";
        if (goalName.startsWith(prefix)) {
            return goalName.substring(prefix.length());
        }
        return goalName;
    }
}
