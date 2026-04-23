package network.ike.plugin.ws;

import network.ike.plugin.ws.ReleasePlan.GA;
import network.ike.plugin.ws.ReleasePlan.ReferenceKind;
import network.ike.plugin.ws.ReleasePlan.ReferenceSite;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.model.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Extracts the version-bearing reference sites from a single POM file.
 *
 * <p>Reads the POM via {@link PomModel} (Maven 4 model API,
 * location-tracking enabled) and walks the {@code <parent>} block,
 * the {@code <dependencies>} / {@code <dependencyManagement>}
 * sections, and the {@code <build><plugins>} /
 * {@code <build><pluginManagement>} sections.
 * For each site, records the target groupId:artifactId and the
 * literal text at the {@code <version>} element.
 *
 * <p>Property references are preserved verbatim.
 * A version of {@code ${ike-tooling.version}} is recorded as the text
 * {@code ${ike-tooling.version}} — no property resolution.
 * Property resolution is the plan-compute layer's job,
 * using the full reactor's declaration set.
 *
 * <p>Thread-safe: all methods are stateless.
 *
 * @see ReleasePlan
 * @see PomModel
 */
final class PomSiteScanner {

    private PomSiteScanner() {}

    /**
     * A scanned POM's declarations and version references.
     *
     * @param pomPath              absolute path to the scanned POM
     * @param selfGa               the artifact this POM produces —
     *                             groupId:artifactId, with groupId
     *                             falling back to the parent's when
     *                             inherited. {@code null} only if the
     *                             POM is malformed; well-formed POMs
     *                             always have an artifactId
     * @param propertyDeclarations {@code <properties>} entries in this
     *                             POM (name → value, verbatim — a
     *                             value of {@code ${other}} stays
     *                             unresolved)
     * @param sites                every parent, plugin, and dependency
     *                             version site in this POM
     */
    record PomSiteSurvey(
            Path pomPath,
            GA selfGa,
            Map<String, String> propertyDeclarations,
            List<ReferenceSite> sites) {

        PomSiteSurvey {
            propertyDeclarations = Map.copyOf(propertyDeclarations);
            sites = List.copyOf(sites);
        }
    }

    /**
     * Scan the POM at {@code pomPath} for version-bearing reference
     * sites and property declarations.
     *
     * @param pomPath absolute path to pom.xml
     * @return a survey of the POM's property declarations and
     *         reference sites
     * @throws IOException if the POM cannot be read or parsed
     */
    static PomSiteSurvey scan(Path pomPath) throws IOException {
        PomModel pom = PomModel.parse(pomPath);
        List<ReferenceSite> sites = new ArrayList<>();

        String selfGid = pom.groupId();
        String selfAid = pom.artifactId();
        GA selfGa = selfAid == null ? null : new GA(selfGid, selfAid);

        Parent parent = pom.parent();
        if (parent != null) {
            sites.add(new ReferenceSite(
                    pomPath,
                    ReferenceKind.PARENT,
                    new GA(parent.getGroupId(), parent.getArtifactId()),
                    parent.getVersion()));
        }

        for (Dependency dep : pom.allDependencies()) {
            sites.add(new ReferenceSite(
                    pomPath,
                    ReferenceKind.DEPENDENCY,
                    new GA(dep.getGroupId(), dep.getArtifactId()),
                    dep.getVersion()));
        }

        for (Plugin plugin : pom.allPlugins()) {
            String gid = plugin.getGroupId();
            if (gid == null) {
                gid = "org.apache.maven.plugins";
            }
            sites.add(new ReferenceSite(
                    pomPath,
                    ReferenceKind.PLUGIN,
                    new GA(gid, plugin.getArtifactId()),
                    plugin.getVersion()));
        }

        Map<String, String> props = pom.properties();
        return new PomSiteSurvey(
                pomPath,
                selfGa,
                props != null ? props : Map.of(),
                Collections.unmodifiableList(sites));
    }
}
