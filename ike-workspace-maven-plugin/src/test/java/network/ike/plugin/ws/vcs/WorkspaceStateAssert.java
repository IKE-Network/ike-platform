package network.ike.plugin.ws.vcs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test harness (ike-issues#691) that snapshots per-subproject git + POM state so
 * later tests (e.g. the ike-issues#667 / #689 orchestration tests) can assert
 * workspace invariants — "no stray tag landed at HEAD", "every subproject still
 * reports version X", "the branch didn't move" — against a single, comparable
 * value object.
 *
 * <p>For each named subproject (a subdirectory of the workspace root that is a
 * git repository) it captures the current {@link SubState#branch() branch}, the
 * {@link SubState#headSha() HEAD sha}, the set of {@link SubState#tagsAtHead()
 * tags pointing at HEAD}, and the project's own {@code <version>} from its
 * {@code pom.xml}. Git state is read by shelling out to {@code git} and
 * capturing stdout, mirroring the {@code exec}-style helper in
 * {@link VcsOperationsTest}; the POM version is read with a small regex, which
 * is adequate for the controlled fixtures these tests build.
 */
public final class WorkspaceStateAssert {

    private WorkspaceStateAssert() {
    }

    /**
     * Immutable snapshot of one subproject's git + POM state.
     *
     * @param name        the subproject directory name
     * @param branch      the current branch (or a detached-HEAD marker)
     * @param headSha     the full HEAD sha
     * @param tagsAtHead  the set of tags pointing exactly at HEAD
     * @param pomVersion  the project's own {@code <version>}, or {@code null} if
     *                    no {@code pom.xml} / no version was found
     */
    public record SubState(String name, String branch, String headSha,
                           Set<String> tagsAtHead, String pomVersion) {

        @Override
        public String toString() {
            return "SubState{name=" + name
                    + ", branch=" + branch
                    + ", headSha=" + headSha
                    + ", tagsAtHead=" + tagsAtHead
                    + ", pomVersion=" + pomVersion + "}";
        }
    }

    /**
     * Snapshot every named subproject under {@code workspaceRoot}.
     *
     * @param workspaceRoot    the workspace root directory; each name resolves
     *                         to a git-repo subdirectory beneath it
     * @param subprojectNames  the subproject directory names to capture
     * @return an insertion-ordered map from subproject name to its
     *         {@link SubState}
     */
    public static Map<String, SubState> snapshot(File workspaceRoot,
                                                 List<String> subprojectNames) {
        Map<String, SubState> states = new LinkedHashMap<>();
        for (String name : subprojectNames) {
            File dir = new File(workspaceRoot, name);
            states.put(name, snapshotOne(name, dir));
        }
        return states;
    }

    private static SubState snapshotOne(String name, File dir) {
        String branch = exec(dir, "git", "rev-parse", "--abbrev-ref", "HEAD");
        String headSha = exec(dir, "git", "rev-parse", "HEAD");
        String tagsRaw = exec(dir, "git", "tag", "--points-at", "HEAD");
        Set<String> tags = new TreeSet<>();
        for (String line : tagsRaw.split("\\R")) {
            if (!line.isBlank()) {
                tags.add(line.trim());
            }
        }
        String version = readPomVersion(new File(dir, "pom.xml"));
        return new SubState(name, branch, headSha, tags, version);
    }

    /**
     * Read a POM's own {@code <version>}: the first {@code <version>} after the
     * {@code </parent>} close tag, or — when the POM declares no parent — the
     * first {@code <version>} in the file (the top-level project version).
     *
     * <p>This is a deliberately small parse, sufficient for the controlled
     * fixtures these tests author. It is not a general-purpose POM reader.
     *
     * @param pom the {@code pom.xml} file
     * @return the project's own version, or {@code null} if the file is absent
     *         or contains no usable {@code <version>}
     */
    static String readPomVersion(File pom) {
        if (!pom.isFile()) {
            return null;
        }
        String xml;
        try {
            xml = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        int searchFrom = 0;
        int parentEnd = xml.indexOf("</parent>");
        if (parentEnd >= 0) {
            searchFrom = parentEnd + "</parent>".length();
        }
        Matcher m = Pattern.compile("<version>\\s*(.*?)\\s*</version>", Pattern.DOTALL)
                .matcher(xml);
        if (m.find(searchFrom)) {
            return m.group(1).trim();
        }
        return null;
    }

    // ── Convenience assertions ───────────────────────────────────

    /**
     * Assert that {@code tag} is NOT among the tags pointing at the snapshot's
     * HEAD.
     *
     * @param state the snapshot to check
     * @param tag   the tag that must not be present at HEAD
     */
    public static void assertNoTagAtHead(SubState state, String tag) {
        assertThat(state.tagsAtHead())
                .as("tags at HEAD of subproject '%s' (%s)", state.name(), state)
                .doesNotContain(tag);
    }

    /**
     * Assert that the snapshot's own POM version equals {@code expected}.
     *
     * @param state    the snapshot to check
     * @param expected the expected {@code <version>}
     */
    public static void assertVersion(SubState state, String expected) {
        assertThat(state.pomVersion())
                .as("pom version of subproject '%s' (%s)", state.name(), state)
                .isEqualTo(expected);
    }

    // ── git exec helper (mirrors VcsOperationsTest) ──────────────

    /**
     * Run a command in {@code dir} and return its combined output, trimmed.
     * Throws an unchecked exception on non-zero exit so a misconfigured fixture
     * fails loudly rather than yielding a silently empty snapshot.
     *
     * @param dir     the working directory
     * @param command the command and its arguments
     * @return the trimmed stdout/stderr of the command
     */
    private static String exec(File dir, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(
                        "Command failed (exit " + exit + "): "
                                + String.join(" ", command) + " in " + dir
                                + (output.isBlank() ? "" : "\n" + output));
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to execute " + Arrays.toString(command) + " in " + dir, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted running " + Arrays.toString(command) + " in " + dir, e);
        }
    }

    /**
     * Convenience overload accepting a {@link Path} workspace root.
     *
     * @param workspaceRoot   the workspace root path
     * @param subprojectNames the subproject directory names to capture
     * @return the per-subproject snapshot map
     */
    public static Map<String, SubState> snapshot(Path workspaceRoot,
                                                 List<String> subprojectNames) {
        return snapshot(workspaceRoot.toFile(), subprojectNames);
    }
}
