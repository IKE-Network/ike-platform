package network.ike.plugin.ws;

import network.ike.plugin.PomRewriter;

import org.apache.maven.api.model.Parent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Utilities for reading and updating {@code <parent>} blocks in POM files.
 *
 * <p>Reading uses the Maven 4 model API via {@link PomModel}.
 * Writing uses the OpenRewrite LST via {@link PomRewriter}.
 * Thread-safe — all methods are stateless.
 */
public final class PomParentSupport {

    private PomParentSupport() {}

    /**
     * Read the parent block from a POM file using the Maven 4 model API.
     *
     * @param pomFile path to pom.xml
     * @return the parent info, or null if no parent block
     * @throws IOException if the file cannot be read or parsed
     */
    static ParentInfo readParent(Path pomFile) throws IOException {
        PomModel model = PomModel.parse(pomFile);
        Parent parent = model.parent();
        if (parent == null) return null;
        return new ParentInfo(
                parent.getGroupId(),
                parent.getArtifactId(),
                parent.getVersion());
    }

    /**
     * Update the parent version for a matching
     * {@code groupId:artifactId}. Delegates to {@link PomRewriter}
     * for AST-aware manipulation.
     *
     * <p>Matching requires <strong>both</strong> groupId and
     * artifactId to match — see issue #241.
     *
     * @param pomContent       POM XML as a string
     * @param parentGroupId    the groupId to match in the parent block
     * @param parentArtifactId the artifactId to match in the parent block
     * @param newVersion       the new version to set
     * @return updated POM content (unchanged if no match)
     */
    static String updateParentVersion(String pomContent,
                                       String parentGroupId,
                                       String parentArtifactId,
                                       String newVersion) {
        return PomRewriter.updateParentVersion(
                pomContent, parentGroupId, parentArtifactId, newVersion);
    }

    /**
     * Check whether a POM's {@code <parent>} block declares an
     * empty {@code <relativePath/>} (self-closing or empty content).
     *
     * <p>An empty {@code relativePath} disables Maven's filesystem
     * lookup for the parent — required to prevent the parent-cycle
     * model-builder error when a subproject's {@code <parent>} GA
     * matches the workspace aggregator's own parent GA (ike-issues#324).
     *
     * <p>Detection is text-based rather than model-based: Maven 4's
     * Parent model assigns a default value (typically
     * {@code "../pom.xml"}) to absent {@code relativePath} elements,
     * so the parsed model cannot reliably distinguish
     * "absent" from "default" from "explicit empty". The raw XML
     * preserves that information.
     *
     * @param pomFile path to pom.xml
     * @return {@code true} when the POM's {@code <parent>} block
     *         contains an empty {@code <relativePath/>}; {@code false}
     *         when absent, non-empty, or the parent block itself is
     *         missing
     * @throws IOException if the file cannot be read
     */
    public static boolean hasEmptyRelativePath(Path pomFile) throws IOException {
        String content = Files.readString(pomFile, StandardCharsets.UTF_8);
        return hasEmptyRelativePathInContent(content);
    }

    /**
     * Same as {@link #hasEmptyRelativePath(Path)} but operating on
     * raw POM content. Used directly by tests and any caller that
     * already has the content in memory.
     *
     * @param pomContent raw POM XML
     * @return {@code true} when the {@code <parent>} block contains
     *         an empty {@code <relativePath/>}
     */
    public static boolean hasEmptyRelativePathInContent(String pomContent) {
        if (pomContent == null) return false;
        // Find the <parent>...</parent> block first; an empty
        // <relativePath/> outside <parent> isn't relevant.
        var parentMatcher = PARENT_BLOCK.matcher(pomContent);
        if (!parentMatcher.find()) return false;
        String parentBlock = parentMatcher.group(1);
        return EMPTY_RELATIVE_PATH.matcher(parentBlock).find();
    }

    private static final Pattern PARENT_BLOCK = Pattern.compile(
            "(?s)<parent\\b[^>]*>(.*?)</parent>");

    /**
     * Matches both self-closing {@code <relativePath/>} and empty
     * paired form {@code <relativePath></relativePath>}, with
     * optional whitespace inside. Does not match a non-empty
     * relativePath such as {@code <relativePath>../pom.xml</relativePath>}.
     */
    private static final Pattern EMPTY_RELATIVE_PATH = Pattern.compile(
            "<relativePath\\s*/>"
                    + "|<relativePath\\s*>\\s*</relativePath>");

    /**
     * Parsed parent block from a POM file.
     *
     * @param groupId    parent groupId
     * @param artifactId parent artifactId
     * @param version    parent version
     */
    record ParentInfo(String groupId, String artifactId, String version) {}
}
