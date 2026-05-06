package network.ike.plugin.ws;

import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;

/**
 * AST-aware POM manipulation using OpenRewrite's XML LST.
 *
 * <p>Replaces regex-based POM editing with lossless semantic tree
 * (LST) transformations that preserve formatting, comments, and
 * whitespace. Each method parses the POM, applies a targeted change,
 * and serializes back to text.
 *
 * <p>Usage:
 * <pre>{@code
 * String updated = PomRewriter.updateDependencyVersion(
 *     pomContent, "network.ike", "ike-bom", "84");
 * }</pre>
 *
 * @see PomModel for read-only POM access via Maven 4 model API
 */
final class PomRewriter {

    private PomRewriter() {}

    private static final XmlParser PARSER = new XmlParser();

    /**
     * Update the version of a specific dependency identified by
     * {@code groupId:artifactId} anywhere in the POM (both
     * {@code <dependencies>} and {@code <dependencyManagement>}).
     *
     * @param pomContent the raw POM text
     * @param groupId    dependency groupId to match
     * @param artifactId dependency artifactId to match
     * @param newVersion the version to set
     * @return updated POM text, or unchanged if no match
     */
    static String updateDependencyVersion(String pomContent,
                                           String groupId,
                                           String artifactId,
                                           String newVersion) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"dependency".equals(t.getName())) return t;

                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                if (groupId.equals(gid) && artifactId.equals(aid)) {
                    return t.withChildValue("version", newVersion);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Update the parent version for a matching
     * {@code groupId:artifactId} in the POM's {@code <parent>} block.
     *
     * <p>Matching requires <strong>both</strong> groupId and
     * artifactId to match. This prevents cross-coordinate mutation
     * when the same artifactId lives under multiple groupIds
     * (e.g. {@code network.ike.platform:ike-parent} vs.
     * {@code network.ike.pipeline:ike-parent}) — see issue #241.
     *
     * @param pomContent       the raw POM text
     * @param parentGroupId    the parent groupId to match (required)
     * @param parentArtifactId the parent artifactId to match (required)
     * @param newVersion       the new version to set
     * @return updated POM text, or unchanged if no match
     */
    static String updateParentVersion(String pomContent,
                                       String parentGroupId,
                                       String parentArtifactId,
                                       String newVersion) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"parent".equals(t.getName())) return t;

                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                if (parentGroupId.equals(gid)
                        && parentArtifactId.equals(aid)) {
                    return t.withChildValue("version", newVersion);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Update a version property value in the POM's {@code <properties>}
     * block.
     *
     * @param pomContent   the raw POM text
     * @param propertyName the property name (e.g., "tinkar-core.version")
     * @param newValue     the new property value
     * @return updated POM text, or unchanged if no match
     */
    static String updateProperty(String pomContent,
                                  String propertyName,
                                  String newValue) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        XPathMatcher propertiesMatcher = new XPathMatcher(
                "/project/properties/" + propertyName);
        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (propertiesMatcher.matches(getCursor())) {
                    return t.withValue(newValue);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Update the version of a specific plugin identified by
     * {@code groupId:artifactId} anywhere in the POM (both
     * {@code <plugins>} and {@code <pluginManagement>}).
     *
     * @param pomContent the raw POM text
     * @param groupId    plugin groupId to match
     * @param artifactId plugin artifactId to match
     * @param newVersion the version to set
     * @return updated POM text, or unchanged if no match
     */
    static String updatePluginVersion(String pomContent,
                                       String groupId,
                                       String artifactId,
                                       String newVersion) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"plugin".equals(t.getName())) return t;

                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                if (groupId.equals(gid) && artifactId.equals(aid)) {
                    return t.withChildValue("version", newVersion);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Update the workspace root POM's own coordinates — its top-level
     * {@code <groupId>}, {@code <artifactId>}, and {@code <version>}
     * elements (the ones not nested under {@code <parent>} or any
     * {@code <dependency>} / {@code <plugin>}). Used by
     * {@link WsAdoptRootMojo} to migrate the legacy
     * {@code local.aggregate:<name>:1.0.0-SNAPSHOT} placeholder to a
     * real GAV (ike-issues#184).
     *
     * <p>Pass {@code null} for any field to leave it unchanged; pass a
     * non-null replacement to rewrite that single element. Top-level
     * matching uses the XPath {@code /project/<element>} so nested
     * artifact references are not affected.
     *
     * @param pomContent    the raw POM text
     * @param newGroupId    the project's new groupId, or null to skip
     * @param newArtifactId the project's new artifactId, or null to skip
     * @param newVersion    the project's new version, or null to skip
     * @return updated POM text, or unchanged when no element matches
     *         and no rewrite was attempted
     */
    static String updateProjectCoordinates(String pomContent,
                                           String newGroupId,
                                           String newArtifactId,
                                           String newVersion) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        XPathMatcher gidMatcher = new XPathMatcher("/project/groupId");
        XPathMatcher aidMatcher = new XPathMatcher("/project/artifactId");
        XPathMatcher verMatcher = new XPathMatcher("/project/version");

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (newGroupId != null && gidMatcher.matches(getCursor())) {
                    return t.withValue(newGroupId);
                }
                if (newArtifactId != null && aidMatcher.matches(getCursor())) {
                    return t.withValue(newArtifactId);
                }
                if (newVersion != null && verMatcher.matches(getCursor())) {
                    return t.withValue(newVersion);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Remove the {@code <version>} child from a dependency matched by
     * {@code groupId:artifactId}. Used to eliminate intra-reactor
     * version pins where the reactor resolves the version automatically.
     *
     * @param pomContent the raw POM text
     * @param groupId    dependency groupId to match
     * @param artifactId dependency artifactId to match
     * @return updated POM text with version tag removed, or unchanged if no match
     */
    static String removeDependencyVersion(String pomContent,
                                           String groupId,
                                           String artifactId) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"dependency".equals(t.getName())) return t;

                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                if (groupId.equals(gid) && artifactId.equals(aid)
                        && t.getChild("version").isPresent()) {
                    // Filter out the <version> element from content
                    var filtered = t.getContent().stream()
                            .filter(c -> !(c instanceof Xml.Tag child
                                    && "version".equals(child.getName())))
                            .toList();
                    return t.withContent(filtered);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Parse POM content into an OpenRewrite XML document.
     */
    private static Xml.Document parse(String pomContent) {
        return PARSER.parse(pomContent)
                .findFirst()
                .map(t -> (Xml.Document) t)
                .orElse(null);
    }

    /**
     * Serialize an XML document back to a string.
     */
    private static String print(Xml.Document doc) {
        return doc.printAll();
    }
}
