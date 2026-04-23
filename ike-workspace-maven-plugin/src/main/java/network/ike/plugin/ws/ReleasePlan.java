package network.ike.plugin.ws;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.SequencedMap;

/**
 * Immutable plan for a workspace release-and-align cascade.
 *
 * <p>Computed once at the start of any release or align operation by
 * {@link ReleasePlanCompute#compute}.
 * Every substitution thereafter is a blind lookup — no heuristics,
 * no mid-flight reinterpretation, no re-reading of mutated POM state.
 * Written to {@code plan.yaml} at the workspace root as an audit
 * artifact before any mutation.
 *
 * <p>Design topic:
 * <a href="https://github.com/IKE-Network/ike-issues/issues/212">#212</a>;
 * <a href="../../../../../../../../ike-lab-documents/topics/src/docs/asciidoc/topics/dev/release-plan.adoc">dev-release-plan</a>.
 *
 * @param artifacts  released subprojects, keyed by their Maven GA;
 *                   insertion order is the release order
 * @param properties version properties referenced by POMs in the
 *                   cascade; iteration order is parent-declarations
 *                   first then child overrides. A property declared in
 *                   multiple POMs (parent + override) produces multiple
 *                   entries — reactor inheritance is represented
 *                   explicitly, not implicitly
 */
record ReleasePlan(
        SequencedMap<GA, ArtifactReleasePlan> artifacts,
        List<PropertyReleasePlan> properties) {

    ReleasePlan {
        artifacts = Collections.unmodifiableSequencedMap(artifacts);
        properties = List.copyOf(properties);
    }

    /**
     * A Maven artifact coordinate. {@code groupId:artifactId} is the
     * unique key for an artifact release plan.
     */
    record GA(String groupId, String artifactId) {
        @Override
        public String toString() {
            return groupId + ":" + artifactId;
        }
    }

    /**
     * Kind of version-bearing site in a POM.
     *
     * <p>{@code PLUGIN_LITERAL} is intentionally absent — the
     * 2026-04-22 empirical test showed that extensions plugins resolve
     * user properties, so every in-cascade site can be a
     * {@code ${property}} reference. See
     * {@code ike-lab-documents/.../release-plan.adoc} for the
     * evidence.
     */
    enum ReferenceKind {
        /** {@code <parent><version>...</version>} */
        PARENT,
        /** {@code <build><plugins|pluginManagement>/plugin/<version>} */
        PLUGIN,
        /** {@code <dependencies|dependencyManagement>/dependency/<version>} */
        DEPENDENCY
    }

    /**
     * One site in a POM that references an artifact version.
     *
     * <p>Captured during plan compute; used for audit output in
     * {@code plan.yaml}.
     * Does not drive substitution writes — those target property
     * declarations and artifact self-version elements directly.
     *
     * @param pomPath     absolute path to the POM containing the site
     * @param kind        whether the site is a parent, plugin, or
     *                    dependency reference
     * @param targetGa    the groupId:artifactId the site references
     * @param textAtSite  the literal text at the site's
     *                    {@code <version>} — a property reference like
     *                    {@code ${ike-tooling.version}}, a literal like
     *                    {@code 124}, or {@code null} when the version
     *                    is inherited from parent pluginManagement
     *                    and absent at this site
     */
    record ReferenceSite(
            Path pomPath,
            ReferenceKind kind,
            GA targetGa,
            String textAtSite) {}

    /**
     * Plan for one subproject being released this cascade.
     *
     * <p>Values follow the pre/release/post naming because they are
     * not always snapshots.
     * IKE uses single-segment versions; values may be
     * {@code 125-SNAPSHOT},
     * {@code 125},
     * {@code 126-SNAPSHOT},
     * or any other form the subproject's version convention produces.
     *
     * @param ga                  the artifact's Maven coordinates
     * @param producingSubproject the workspace subproject name that
     *                            produces this artifact
     * @param rootPomPath         the subproject's root POM, where the
     *                            artifact's own {@code <version>}
     *                            element lives
     * @param preReleaseValue     the version before this cascade ran
     *                            (typically a {@code -SNAPSHOT})
     * @param releaseValue        the version being released this
     *                            cascade; what appears on the tag and
     *                            in the deployed consumer POM; never
     *                            ends in {@code -SNAPSHOT}
     * @param postReleaseValue    the version to restore the source POM
     *                            to after the release (typically the
     *                            next {@code -SNAPSHOT})
     * @param referenceSites      every site in the workspace that
     *                            targets this artifact by GA (audit)
     */
    record ArtifactReleasePlan(
            GA ga,
            String producingSubproject,
            Path rootPomPath,
            String preReleaseValue,
            String releaseValue,
            String postReleaseValue,
            List<ReferenceSite> referenceSites) {

        ArtifactReleasePlan {
            referenceSites = List.copyOf(referenceSites);
        }
    }

    /**
     * Plan for one version-tracking property used by the cascade.
     *
     * <p>A property typically tracks an upstream subproject's release
     * version.
     * When the upstream subproject is released,
     * this property's value in its declaring POM is updated to match
     * the upstream's {@code releaseValue}.
     * Child modules that redeclare the same property produce a second
     * {@code PropertyReleasePlan} entry (reactor inheritance is
     * represented explicitly, not implicitly).
     *
     * @param propertyName        the property name
     *                            (e.g., {@code ike-tooling.version})
     * @param declaringPomPath    the POM where the property is
     *                            declared; where the write targets
     * @param declaringSubproject the workspace subproject name
     *                            containing the declaring POM
     * @param preReleaseValue     the property value before this
     *                            cascade ran
     * @param releaseValue        the property value after the upstream
     *                            is released this cascade
     * @param postReleaseValue    the property value to settle on after
     *                            the full cascade; usually equals
     *                            {@code releaseValue}
     * @param referenceSites      every site in the workspace that uses
     *                            {@code ${propertyName}} (audit)
     */
    record PropertyReleasePlan(
            String propertyName,
            Path declaringPomPath,
            String declaringSubproject,
            String preReleaseValue,
            String releaseValue,
            String postReleaseValue,
            List<ReferenceSite> referenceSites) {

        PropertyReleasePlan {
            referenceSites = List.copyOf(referenceSites);
        }
    }
}
