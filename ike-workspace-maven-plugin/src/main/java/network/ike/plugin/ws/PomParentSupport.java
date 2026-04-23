package network.ike.plugin.ws;

import org.apache.maven.api.model.Parent;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Utilities for reading and updating {@code <parent>} blocks in POM files.
 *
 * <p>Reading uses the Maven 4 model API via {@link PomModel}.
 * Writing uses the OpenRewrite LST via {@link PomRewriter}.
 * Thread-safe — all methods are stateless.
 */
final class PomParentSupport {

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
     * Update the parent version for a matching artifactId.
     * Delegates to {@link PomRewriter} for AST-aware manipulation.
     *
     * @param pomContent      POM XML as a string
     * @param parentArtifactId the artifactId to match in the parent block
     * @param newVersion      the new version to set
     * @return updated POM content (unchanged if no match)
     */
    static String updateParentVersion(String pomContent,
                                       String parentArtifactId,
                                       String newVersion) {
        return PomRewriter.updateParentVersion(
                pomContent, parentArtifactId, newVersion);
    }

    /**
     * Parsed parent block from a POM file.
     *
     * @param groupId    parent groupId
     * @param artifactId parent artifactId
     * @param version    parent version
     */
    record ParentInfo(String groupId, String artifactId, String version) {}
}
