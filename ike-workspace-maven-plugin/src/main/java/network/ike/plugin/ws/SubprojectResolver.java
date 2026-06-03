package network.ike.plugin.ws;

import network.ike.workspace.Manifest;
import network.ike.workspace.PublishedArtifactSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a Maven coordinate ({@code groupId:artifactId}) to the
 * workspace subproject that <em>produces</em> it.
 *
 * <p>This is the single place that encodes the rule a POM coordinate
 * maps to a workspace subproject <strong>only</strong> on a full
 * {@code groupId} <em>and</em> {@code artifactId} match against that
 * subproject's published artifact set — never by groupId alone. A
 * coordinate that matches no subproject is <em>external</em> and
 * resolves to {@link Optional#empty()}; callers must leave external
 * coordinates untouched (no manifest {@code parent:}/{@code depends-on}
 * edge, no version rewrite).
 *
 * <p>groupId-alone matching mis-associates whenever two subprojects (or
 * a subproject and an external artifact) share a groupId — e.g.
 * {@code network.ike.platform} is shared by the external
 * {@code ike-parent} and the subproject {@code ike-commonmark-attributes}
 * (IKE-Network/ike-issues#565), and {@code dev.ikm.komet} is shared by
 * the {@code komet} and {@code komet-bom} subprojects (#566). Routing
 * every coordinate→subproject lookup through this resolver closes that
 * class of defect; it mirrors the GA-matching rule the parent-version
 * machinery already applies (#241).
 *
 * <p>Built once by scanning every cloned subproject's published
 * artifacts (regex-based, via {@link PublishedArtifactSet}). Uncloned
 * subprojects (no {@code pom.xml} on disk) contribute nothing — they
 * have no scannable POM, so their coordinates resolve as external until
 * cloned.
 *
 * <p>Thread-confined: build one per operation; the backing index is an
 * immutable snapshot of disk state at {@link #scan} time.
 */
public final class SubprojectResolver {

    /** {@code "groupId:artifactId"} → producing subproject name. */
    private final Map<String, String> index;

    private SubprojectResolver(Map<String, String> index) {
        this.index = index;
    }

    /**
     * Build a resolver by scanning each manifest subproject's published
     * artifact set.
     *
     * <p>When two subprojects pathologically publish the same
     * coordinate, the first in manifest iteration order wins (matching
     * the first-match behaviour of the call sites this replaces).
     *
     * @param wsDir    workspace root directory
     * @param manifest parsed workspace manifest
     * @return a resolver over the manifest's cloned subprojects
     * @throws IOException if a subproject POM cannot be read
     */
    public static SubprojectResolver scan(Path wsDir, Manifest manifest)
            throws IOException {
        Map<String, String> index = new LinkedHashMap<>();
        for (String name : manifest.subprojects().keySet()) {
            Path subDir = wsDir.resolve(name);
            if (!Files.exists(subDir.resolve("pom.xml"))) {
                continue;
            }
            for (PublishedArtifactSet.Artifact artifact
                    : PublishedArtifactSet.scan(subDir)) {
                index.putIfAbsent(
                        artifact.groupId() + ":" + artifact.artifactId(),
                        name);
            }
        }
        return new SubprojectResolver(index);
    }

    /**
     * The workspace subproject that publishes the given coordinate, or
     * empty when the coordinate is external (produced by no subproject,
     * or either coordinate is null).
     *
     * @param groupId    Maven groupId
     * @param artifactId Maven artifactId
     * @return the producing subproject name, or empty when external
     */
    public Optional<String> subprojectForCoordinate(String groupId,
                                                     String artifactId) {
        if (groupId == null || artifactId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(index.get(groupId + ":" + artifactId));
    }
}
