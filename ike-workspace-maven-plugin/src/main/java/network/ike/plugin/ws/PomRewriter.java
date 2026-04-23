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
     * Update the parent version for a matching artifactId in the
     * POM's {@code <parent>} block.
     *
     * @param pomContent       the raw POM text
     * @param parentArtifactId the parent artifactId to match
     * @param newVersion       the new version to set
     * @return updated POM text, or unchanged if no match
     */
    static String updateParentVersion(String pomContent,
                                       String parentArtifactId,
                                       String newVersion) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"parent".equals(t.getName())) return t;

                String aid = t.getChildValue("artifactId").orElse(null);
                if (parentArtifactId.equals(aid)) {
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
