package network.ike.plugin.ws;

import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginManagement;
import org.apache.maven.model.v4.MavenStaxReader;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read-only POM model backed by Maven 4's {@code maven-api-model}.
 *
 * <p>Parses a POM file using {@link MavenStaxReader} with location
 * tracking enabled. Provides typed access to dependencies, properties,
 * and parent — replacing regex-based extraction throughout the
 * workspace plugin.
 *
 * <p>For writes, static utility methods perform targeted text
 * replacement on the raw POM content, preserving formatting.
 */
final class PomModel {

    private final Model model;
    private final String content;

    private PomModel(Model model, String content) {
        this.model = model;
        this.content = content;
    }

    /**
     * Parse a POM file into a model with location tracking.
     *
     * @param pomFile path to pom.xml
     * @return parsed model
     * @throws IOException if the file cannot be read or parsed
     */
    static PomModel parse(Path pomFile) throws IOException {
        String content = Files.readString(pomFile, StandardCharsets.UTF_8);
        MavenStaxReader reader = new MavenStaxReader();
        reader.setAddLocationInformation(true);
        try {
            Model model = reader.read(new StringReader(content), true, null);
            return new PomModel(model, content);
        } catch (XMLStreamException e) {
            throw new IOException("Cannot parse " + pomFile + ": "
                    + e.getMessage(), e);
        }
    }

    /** The underlying Maven 4 model. */
    Model model() { return model; }

    /** Raw POM text for targeted editing. */
    String content() { return content; }

    // ── Reading ────────────────────────────────────────────────────

    /**
     * All dependencies from both {@code <dependencies>} and
     * {@code <dependencyManagement>} sections.
     */
    List<Dependency> allDependencies() {
        List<Dependency> result = new ArrayList<>(model.getDependencies());
        DependencyManagement mgmt = model.getDependencyManagement();
        if (mgmt != null) {
            result.addAll(mgmt.getDependencies());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * All plugins from both {@code <build><plugins>} and
     * {@code <build><pluginManagement><plugins>} sections.
     */
    List<Plugin> allPlugins() {
        List<Plugin> result = new ArrayList<>();
        Build build = model.getBuild();
        if (build != null) {
            result.addAll(build.getPlugins());
            PluginManagement mgmt = build.getPluginManagement();
            if (mgmt != null) {
                result.addAll(mgmt.getPlugins());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Properties from {@code <properties>}. */
    Map<String, String> properties() {
        return model.getProperties();
    }

    /** Parent info, or null if no parent block. */
    Parent parent() {
        return model.getParent();
    }

    /** The project's own groupId (may be null if inherited). */
    String groupId() {
        String gid = model.getGroupId();
        if (gid != null) return gid;
        Parent p = model.getParent();
        return p != null ? p.getGroupId() : null;
    }

    /** The project's own artifactId. */
    String artifactId() {
        return model.getArtifactId();
    }

    /** The project's own version (may be null if inherited). */
    String version() {
        String v = model.getVersion();
        if (v != null) return v;
        Parent p = model.getParent();
        return p != null ? p.getVersion() : null;
    }

    /** Subprojects (Maven 4.1.0) or modules (Maven 4.0.0). */
    @SuppressWarnings("deprecation") // getModules() fallback for POM 4.0.0
    List<String> subprojects() {
        List<String> subs = model.getSubprojects();
        if (subs != null && !subs.isEmpty()) return subs;
        return model.getModules();
    }

    /**
     * BOM imports from {@code <dependencyManagement>} — dependencies
     * with {@code <type>pom</type>} and {@code <scope>import</scope>}.
     *
     * <p>Uses the Maven 4 model API for precise detection instead of
     * regex parsing (#47). Property references (e.g., {@code ${foo.version}})
     * are resolved against the POM's {@code <properties>} block.
     *
     * @return list of BOM import dependencies (unmodifiable)
     */
    List<Dependency> bomImports() {
        DependencyManagement mgmt = model.getDependencyManagement();
        if (mgmt == null) return List.of();

        Map<String, String> props = model.getProperties();
        return mgmt.getDependencies().stream()
                .filter(d -> "pom".equals(d.getType())
                        && "import".equals(d.getScope()))
                .map(d -> {
                    // Resolve ${property} in version
                    String version = d.getVersion();
                    if (version != null && version.startsWith("${")
                            && version.endsWith("}")) {
                        String propName = version.substring(2, version.length() - 1);
                        String resolved = props.get(propName);
                        if (resolved != null) {
                            return d.withVersion(resolved);
                        }
                    }
                    return d;
                })
                .toList();
    }

    // ── Writing (targeted text replacement) ────────────────────────

    /**
     * Update the version of a specific dependency identified by
     * {@code groupId:artifactId}. Uses OpenRewrite LST for
     * element-order-tolerant matching within dependency blocks.
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
        return PomRewriter.updateDependencyVersion(
                pomContent, groupId, artifactId, newVersion);
    }

    /**
     * Update a version property value in the POM content.
     * Uses OpenRewrite LST for precise property matching.
     *
     * @param pomContent   the raw POM text
     * @param propertyName the property name (e.g., "tinkar-core.version")
     * @param newValue     the new property value
     * @return updated POM text
     */
    static String updateProperty(String pomContent,
                                  String propertyName,
                                  String newValue) {
        return PomRewriter.updateProperty(
                pomContent, propertyName, newValue);
    }

    /**
     * Update the parent version in a POM's {@code <parent>} block.
     * Uses OpenRewrite LST for element-order-tolerant matching.
     *
     * @param pomContent      the raw POM text
     * @param parentArtifactId the parent artifactId to match
     * @param newVersion      the new version to set
     * @return updated POM text, or unchanged if no match
     */
    static String updateParentVersion(String pomContent,
                                       String parentArtifactId,
                                       String newVersion) {
        return PomRewriter.updateParentVersion(
                pomContent, parentArtifactId, newVersion);
    }

    /**
     * Update the version of a specific plugin identified by
     * {@code groupId:artifactId}. Uses OpenRewrite LST for
     * element-order-tolerant matching within plugin blocks,
     * including both {@code <plugins>} and {@code <pluginManagement>}.
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
        return PomRewriter.updatePluginVersion(
                pomContent, groupId, artifactId, newVersion);
    }

    /**
     * Remove the {@code <version>} tag from a dependency matched by
     * {@code groupId:artifactId}. Used to eliminate intra-reactor
     * version pins where the reactor resolves the version automatically.
     *
     * @param pomContent the raw POM text
     * @param groupId    dependency groupId to match
     * @param artifactId dependency artifactId to match
     * @return updated POM text with version removed, or unchanged if no match
     */
    static String removeDependencyVersion(String pomContent,
                                           String groupId,
                                           String artifactId) {
        return PomRewriter.removeDependencyVersion(
                pomContent, groupId, artifactId);
    }
}
