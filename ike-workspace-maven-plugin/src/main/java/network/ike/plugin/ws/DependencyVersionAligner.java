package network.ike.plugin.ws;

import org.openrewrite.Cursor;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AST-aware alignment of a POM's dependency versions to workspace
 * reactor versions, using OpenRewrite's XML LST (no regex on POMs).
 *
 * <p>Owns the {@code ws:add} version-alignment edit
 * (IKE-Network/ike-issues#826). The contract:
 * <ul>
 *   <li><b>Property indirection is preserved.</b> A dependency whose
 *       {@code <version>} is a {@code ${...}} property reference is
 *       never rewritten to a literal. When the property's resolved
 *       value already matches the reactor version, the POM is left
 *       untouched; when it is stale, the mismatch is reported via
 *       {@link Result#propertyUpdates()} so the caller can update the
 *       property <em>value</em> where it is declared.</li>
 *   <li><b>Only literal versions are rewritten in place</b>, and only
 *       for dependencies outside {@code <dependencyManagement>} whose
 *       {@code groupId:artifactId} matches a workspace artifact.</li>
 *   <li>Built-in references (any {@code ${project.*}}) and mixed
 *       expressions (literal text combined with {@code ${...}}) are
 *       never touched.</li>
 * </ul>
 *
 * @see network.ike.plugin.PomRewriter
 */
final class DependencyVersionAligner {

    private DependencyVersionAligner() {}

    private static final XmlParser PARSER = new XmlParser();

    /** A whole-string single property reference: {@code ${name}}. */
    private static final Pattern SINGLE_PROPERTY_REFERENCE =
            Pattern.compile("\\$\\{\\s*([^}]+?)\\s*}");

    /**
     * Outcome of one alignment pass over a single POM.
     *
     * @param pom             the (possibly updated) POM text; equal to
     *                        the input when no literal rewrite applied
     * @param propertyUpdates property name → reactor version for each
     *                        property-referenced dependency version
     *                        whose resolved value did not match the
     *                        reactor version; the caller updates these
     *                        where the property is declared
     */
    record Result(String pom, Map<String, String> propertyUpdates) {}

    /**
     * Align dependency versions in one POM against the workspace
     * artifact-version map.
     *
     * <p>Dependencies inside {@code <dependencyManagement>} are never
     * modified (BOM imports and version constraints are not build
     * edges — see {@code ws:add}'s derivation contract, #239).
     *
     * @param pomContent          the raw POM text
     * @param artifactVersions    {@code groupId:artifactId} → reactor
     *                            version for all workspace-produced
     *                            artifacts
     * @param effectiveProperties the property values in scope for this
     *                            POM (its own {@code <properties>}
     *                            overlaid on the subproject root's),
     *                            used to decide whether a property
     *                            reference is already aligned
     * @return the updated POM text plus any stale-property updates the
     *         caller must apply where the properties are declared
     */
    static Result align(String pomContent,
                        Map<String, String> artifactVersions,
                        Map<String, String> effectiveProperties) {
        Xml.Document doc = PARSER.parse(pomContent)
                .findFirst()
                .map(t -> (Xml.Document) t)
                .orElse(null);
        if (doc == null) {
            return new Result(pomContent, Map.of());
        }

        Map<String, String> propertyUpdates = new LinkedHashMap<>();

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"dependency".equals(t.getName())
                        || insideDependencyManagement(getCursor())) {
                    return t;
                }

                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                String target = artifactVersions.get(gid + ":" + aid);
                if (target == null) {
                    return t;
                }

                String declared = t.getChildValue("version").orElse(null);
                if (declared == null || declared.isBlank()) {
                    return t;
                }

                String propertyName = singlePropertyReference(declared);
                if (propertyName != null) {
                    // Never replace a property reference with a literal
                    // (#826). ${project.*} is built-in and never ours to
                    // manage; anything else that resolves to a stale
                    // value is aligned at its declaration site instead.
                    if (!propertyName.startsWith("project.")) {
                        String resolved =
                                effectiveProperties.get(propertyName);
                        if (resolved != null && !resolved.equals(target)) {
                            propertyUpdates.putIfAbsent(propertyName, target);
                        }
                    }
                    return t;
                }
                if (declared.contains("${")) {
                    // Mixed literal/expression version — too ambiguous
                    // to rewrite safely; leave as declared.
                    return t;
                }
                if (!declared.equals(target)) {
                    return t.withChildValue("version", target);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return new Result(updated.printAll(), propertyUpdates);
    }

    /**
     * Extract the property name when the value is exactly one
     * {@code ${...}} reference (ignoring surrounding whitespace), or
     * null for literals and mixed expressions.
     *
     * @param value the declared version text
     * @return the referenced property name, or null
     */
    static String singlePropertyReference(String value) {
        Matcher m = SINGLE_PROPERTY_REFERENCE.matcher(value.trim());
        return (m.matches()) ? m.group(1) : null;
    }

    /**
     * Whether the cursor's current tag sits anywhere inside a
     * {@code <dependencyManagement>} block.
     *
     * @param cursor the visitor cursor positioned at a tag
     * @return true when an ancestor tag is {@code dependencyManagement}
     */
    private static boolean insideDependencyManagement(Cursor cursor) {
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) {
            if (c.getValue() instanceof Xml.Tag tag
                    && "dependencyManagement".equals(tag.getName())) {
                return true;
            }
        }
        return false;
    }
}
