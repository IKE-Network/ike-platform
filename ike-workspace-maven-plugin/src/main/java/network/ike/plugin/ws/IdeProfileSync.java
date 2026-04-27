package network.ike.plugin.ws;

import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Profile;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.model.v4.MavenStaxReader;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Write the active {@code with-*} profile list into
 * {@code .mvn/maven.config} so IntelliJ activates them on import.
 *
 * <p><b>Why.</b> Workspace aggregator POMs declare profiles like
 * {@code <profile><id>with-tinkar-core</id>} activated by an
 * {@code <exists>${project.basedir}/tinkar-core/pom.xml</exists>}
 * file-presence rule. The Maven CLI honors these reliably; IntelliJ's
 * Maven importer does not, leaving the user to tick boxes by hand on
 * every import. A {@code -P} arg in {@code .mvn/maven.config} is read
 * by both the CLI and the IntelliJ importer at parse time, so the
 * profiles activate without any IDE clicking.
 *
 * <p><b>Owned-block format.</b> The plugin manages exactly the lines
 * between two markers, leaving everything else verbatim:
 *
 * <pre>{@code
 * -T 1C
 * -pl
 * !.teamcity
 * # >>> ws:ide-sync managed >>>
 * -Pwith-tinkar-core,with-komet-bom
 * # <<< ws:ide-sync managed <<<
 * }</pre>
 *
 * <p>If no {@code with-*} profiles are present-and-active, the block is
 * removed entirely — leaving an empty file is fine. The {@code -P} arg
 * is a single comma-joined token; spaces around the value would split
 * into separate args under Maven 3.9+ line-based parsing.
 *
 * <p>Idempotent: running twice produces identical content.
 *
 * <p>See {@code IKE-Network/ike-issues#276}.
 */
final class IdeProfileSync {

    static final String BEGIN_MARKER = "# >>> ws:ide-sync managed >>>";
    static final String END_MARKER = "# <<< ws:ide-sync managed <<<";
    private static final String PROFILE_PREFIX = "with-";

    private IdeProfileSync() {}

    /**
     * Sync the profile list for the workspace at {@code workspaceRoot}.
     * No-op when the workspace POM declares no {@code with-*} profiles.
     *
     * @param workspaceRoot the workspace root directory (containing
     *                      {@code pom.xml} and {@code workspace.yaml})
     * @param log           plugin log for status messages
     */
    static void run(File workspaceRoot, Log log) {
        Path pomPath = workspaceRoot.toPath().resolve("pom.xml");
        if (!Files.isRegularFile(pomPath)) {
            log.debug("ide-sync: no pom.xml at " + workspaceRoot
                    + " — skipping");
            return;
        }

        List<String> activeProfiles;
        try {
            activeProfiles = computeActiveProfiles(workspaceRoot, pomPath);
        } catch (IOException e) {
            log.warn("ide-sync: cannot parse pom.xml — " + e.getMessage());
            return;
        }

        Path mavenConfig = workspaceRoot.toPath()
                .resolve(".mvn").resolve("maven.config");
        try {
            String existing = Files.isRegularFile(mavenConfig)
                    ? Files.readString(mavenConfig, StandardCharsets.UTF_8)
                    : "";
            String updated = rewriteOwnedBlock(existing, activeProfiles);
            if (updated.equals(existing)) {
                log.debug("ide-sync: .mvn/maven.config already up to date");
                return;
            }
            Files.createDirectories(mavenConfig.getParent());
            Files.writeString(mavenConfig, updated, StandardCharsets.UTF_8);
            if (activeProfiles.isEmpty()) {
                log.info("  ide-sync: cleared .mvn/maven.config -P block");
            } else {
                log.info("  ide-sync: .mvn/maven.config -P "
                        + String.join(",", activeProfiles));
            }
        } catch (IOException e) {
            log.warn("ide-sync: cannot update .mvn/maven.config — "
                    + e.getMessage());
        }
    }

    /**
     * Enumerate {@code <profile><id>with-X</id></profile>} entries in the
     * workspace POM whose {@code <activation><file><exists>} target is
     * present on disk. Returned in alphabetical order so the output is
     * stable regardless of POM declaration order.
     */
    private static List<String> computeActiveProfiles(File workspaceRoot,
                                                       Path pomPath)
            throws IOException {
        Model model;
        try {
            String content = Files.readString(pomPath, StandardCharsets.UTF_8);
            model = new MavenStaxReader().read(new StringReader(content));
        } catch (Exception e) {
            throw new IOException("Cannot parse " + pomPath + ": "
                    + e.getMessage(), e);
        }

        List<Profile> profiles = model.getProfiles();
        if (profiles == null || profiles.isEmpty()) {
            return List.of();
        }

        List<String> active = new ArrayList<>();
        for (Profile p : profiles) {
            String id = p.getId();
            if (id == null || !id.startsWith(PROFILE_PREFIX)) {
                continue;
            }
            Optional<String> existsPath = activationFileExists(p);
            if (existsPath.isEmpty()) {
                continue;
            }
            String resolved = existsPath.get()
                    .replace("${project.basedir}", workspaceRoot.toString());
            if (Files.exists(Path.of(resolved))) {
                active.add(id);
            }
        }
        active.sort(Comparator.naturalOrder());
        return active;
    }

    private static Optional<String> activationFileExists(Profile p) {
        if (p.getActivation() == null
                || p.getActivation().getFile() == null) {
            return Optional.empty();
        }
        String exists = p.getActivation().getFile().getExists();
        return (exists == null || exists.isBlank())
                ? Optional.empty()
                : Optional.of(exists);
    }

    /**
     * Replace (or remove) the owned block in the existing
     * {@code maven.config} content, preserving everything outside the
     * markers verbatim.
     *
     * <p>If {@code activeProfiles} is empty, the entire block (including
     * markers) is removed. Otherwise the block is rewritten with the
     * current profile list.
     *
     * @param existing       current file content (may be empty)
     * @param activeProfiles sorted list of profile ids to activate
     * @return new file content, suitable to write back as-is
     */
    static String rewriteOwnedBlock(String existing,
                                     List<String> activeProfiles) {
        String[] lines = existing.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inBlock = false;
        boolean blockEmitted = false;

        for (String line : lines) {
            if (!inBlock && line.equals(BEGIN_MARKER)) {
                inBlock = true;
                if (!activeProfiles.isEmpty()) {
                    appendBlock(out, activeProfiles);
                    blockEmitted = true;
                }
                continue;
            }
            if (inBlock) {
                if (line.equals(END_MARKER)) {
                    inBlock = false;
                }
                continue;
            }
            out.append(line).append('\n');
        }

        // Trailing newline from split is an empty token at end — strip the
        // final \n we appended for it, then re-normalize.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }

        if (!blockEmitted && !activeProfiles.isEmpty()) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            appendBlock(out, activeProfiles);
            // appendBlock leaves no trailing newline; add one for POSIX
            out.append('\n');
        } else if (out.length() > 0
                && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }

        return out.toString();
    }

    private static void appendBlock(StringBuilder out,
                                     List<String> activeProfiles) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        out.append(BEGIN_MARKER).append('\n');
        out.append("-P").append(String.join(",", activeProfiles)).append('\n');
        out.append(END_MARKER);
    }
}
