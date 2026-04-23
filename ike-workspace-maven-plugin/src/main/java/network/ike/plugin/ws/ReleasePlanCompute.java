package network.ike.plugin.ws;

import network.ike.plugin.ws.PomSiteScanner.PomSiteSurvey;
import network.ike.plugin.ws.ReactorWalker.ReactorScan;
import network.ike.plugin.ws.ReleasePlan.ArtifactReleasePlan;
import network.ike.plugin.ws.ReleasePlan.GA;
import network.ike.plugin.ws.ReleasePlan.PropertyReleasePlan;
import network.ike.plugin.ws.ReleasePlan.ReferenceSite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;

/**
 * Computes an immutable {@link ReleasePlan} from a reactor scan and a
 * caller-supplied list of artifact release intents.
 *
 * <p>This is the single source of truth for what a release or align
 * operation will do. No heuristics, no mid-flight reinterpretation —
 * every substitution later becomes a blind lookup in the returned
 * plan.
 *
 * <p>Invariants enforced at compute time (fail fast, loud):
 * <ul>
 *   <li>No {@code releaseValue} ends in {@code -SNAPSHOT}.</li>
 *   <li>No duplicate artifact intents for the same GA.</li>
 *   <li>A property that references multiple in-cascade artifacts must
 *       agree on their {@code releaseValue}; otherwise the property is
 *       ambiguous and the compute fails.</li>
 *   <li>The {@code releaseValue} of a {@link PropertyReleasePlan} must
 *       equal the {@code releaseValue} of the artifact it tracks — the
 *       direct guard against the
 *       <a href="https://github.com/IKE-Network/ike-issues/issues/209">#209</a>
 *       class of regression (where a property tracking an upstream
 *       artifact was bumped to the releasing subproject's own
 *       version).</li>
 * </ul>
 *
 * <p>Thread-safe: all methods are stateless.
 *
 * @see ReleasePlan
 * @see ReactorWalker
 */
final class ReleasePlanCompute {

    private ReleasePlanCompute() {}

    /**
     * A subproject in the workspace, by name and root POM path.
     *
     * <p>Used to map a declaring POM path back to its subproject name
     * when building {@link PropertyReleasePlan} entries for POMs that
     * are not themselves being released this cascade.
     *
     * @param name        workspace subproject name
     * @param rootPomPath absolute path to the subproject's root pom.xml
     */
    record SubprojectRoot(String name, Path rootPomPath) {

        SubprojectRoot {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(rootPomPath, "rootPomPath");
        }
    }

    /**
     * One artifact the caller intends to release this cascade.
     *
     * @param ga                  Maven coordinates
     * @param producingSubproject workspace subproject name
     * @param rootPomPath         absolute path to the subproject's
     *                            root pom.xml, where this artifact's
     *                            own {@code <version>} lives
     * @param preReleaseValue     the artifact's version before this
     *                            cascade
     * @param releaseValue        the artifact's released value; must
     *                            not end in {@code -SNAPSHOT}
     * @param postReleaseValue    the value to restore after the
     *                            release is published
     */
    record ArtifactReleaseIntent(
            GA ga,
            String producingSubproject,
            Path rootPomPath,
            String preReleaseValue,
            String releaseValue,
            String postReleaseValue) {

        ArtifactReleaseIntent {
            Objects.requireNonNull(ga, "ga");
            Objects.requireNonNull(producingSubproject, "producingSubproject");
            Objects.requireNonNull(rootPomPath, "rootPomPath");
            Objects.requireNonNull(releaseValue, "releaseValue");
            if (releaseValue.endsWith("-SNAPSHOT")) {
                throw new IllegalArgumentException(
                        "releaseValue must not end in -SNAPSHOT: "
                                + ga + " → " + releaseValue);
            }
        }
    }

    /**
     * Compute the plan. See class-level Javadoc for the invariants
     * enforced.
     *
     * @param scan        the reactor scan: one survey per POM in the
     *                    workspace
     * @param subprojects all subprojects in the workspace by
     *                    (name, rootPomPath) — used to attribute
     *                    property declarations in POMs that are not
     *                    themselves being released
     * @param intents     the artifacts being released this cascade, in
     *                    release order
     * @return the immutable release plan
     */
    static ReleasePlan compute(
            ReactorScan scan,
            List<SubprojectRoot> subprojects,
            List<ArtifactReleaseIntent> intents) {

        // ── Index intents by GA (duplicate check) ───────────────────
        Map<GA, ArtifactReleaseIntent> byGa = new LinkedHashMap<>();
        for (ArtifactReleaseIntent intent : intents) {
            if (byGa.put(intent.ga(), intent) != null) {
                throw new IllegalArgumentException(
                        "duplicate release intent for " + intent.ga());
            }
        }

        // ── Extend in-cascade coverage to co-released sub-artifacts ──
        // A released subproject produces more than just the intent's GA:
        // any POM in that subproject's reactor (the root POM plus every
        // <module>/<subproject> descendant) is co-released at the same
        // version. Without this, a property like ${ike-tooling.version}
        // whose references target sub-artifacts (ike-maven-plugin,
        // ike-build-standards) would not resolve to the ike-tooling
        // intent, and the property would silently stay at its pre-value.
        //
        // coveredGa: every GA produced anywhere under any intent's
        // rootPomPath's parent directory → that intent
        Map<GA, ArtifactReleaseIntent> coveredGa = new LinkedHashMap<>();
        coveredGa.putAll(byGa);
        for (ArtifactReleaseIntent intent : intents) {
            Path intentReactorDir = intent.rootPomPath().getParent()
                    .toAbsolutePath().normalize();
            for (PomSiteSurvey survey : scan.surveys()) {
                GA selfGa = survey.selfGa();
                if (selfGa == null) continue;
                Path surveyPath = survey.pomPath().toAbsolutePath().normalize();
                if (!surveyPath.startsWith(intentReactorDir)) continue;
                ArtifactReleaseIntent existing = coveredGa.putIfAbsent(
                        selfGa, intent);
                if (existing != null && existing != intent
                        && !existing.releaseValue().equals(intent.releaseValue())) {
                    throw new IllegalStateException(
                            "GA " + selfGa + " is produced under multiple"
                                    + " releasing subprojects with conflicting"
                                    + " releaseValues: "
                                    + existing.ga() + " → " + existing.releaseValue()
                                    + " and "
                                    + intent.ga() + " → " + intent.releaseValue());
                }
            }
        }

        // ── Build artifact plans: every site targeting this GA ──────
        SequencedMap<GA, ArtifactReleasePlan> artifactPlans =
                new LinkedHashMap<>();
        for (ArtifactReleaseIntent intent : intents) {
            List<ReferenceSite> sites = new ArrayList<>();
            for (PomSiteSurvey survey : scan.surveys()) {
                for (ReferenceSite site : survey.sites()) {
                    if (intent.ga().equals(site.targetGa())) {
                        sites.add(site);
                    }
                }
            }
            artifactPlans.put(intent.ga(), new ArtifactReleasePlan(
                    intent.ga(),
                    intent.producingSubproject(),
                    intent.rootPomPath(),
                    intent.preReleaseValue(),
                    intent.releaseValue(),
                    intent.postReleaseValue(),
                    sites));
        }

        // ── Find properties that track an in-cascade artifact ───────
        // propertyName → set of target GAs referenced via ${propertyName}.
        // Maven built-in expressions like ${project.version} or
        // ${project.parent.version} are excluded: they resolve locally
        // per POM at build time and never track a workspace-level
        // property declaration. Including them would falsely conflate
        // self-references across different releasing subprojects.
        Map<String, Set<GA>> propertyTargets = new LinkedHashMap<>();
        for (PomSiteSurvey survey : scan.surveys()) {
            for (ReferenceSite site : survey.sites()) {
                String text = site.textAtSite();
                if (text == null
                        || !text.startsWith("${")
                        || !text.endsWith("}")) continue;
                String propName = text.substring(2, text.length() - 1);
                if (isMavenBuiltinExpression(propName)) continue;
                propertyTargets
                        .computeIfAbsent(propName, k -> new LinkedHashSet<>())
                        .add(site.targetGa());
            }
        }

        // propertyName → the in-cascade artifact it tracks (if any).
        // Matches against coveredGa (direct intents + co-released
        // sub-artifacts), not just the intent GA.
        Map<String, ArtifactReleaseIntent> propertyTracks =
                new LinkedHashMap<>();
        for (Map.Entry<String, Set<GA>> entry : propertyTargets.entrySet()) {
            String propName = entry.getKey();
            ArtifactReleaseIntent tracked = null;
            for (GA targetGa : entry.getValue()) {
                ArtifactReleaseIntent candidate = coveredGa.get(targetGa);
                if (candidate == null) continue;
                if (tracked != null && tracked != candidate
                        && !tracked.releaseValue().equals(candidate.releaseValue())) {
                    throw new IllegalStateException(
                            "property ${" + propName + "} references multiple"
                                    + " in-cascade artifacts with conflicting"
                                    + " releaseValues: "
                                    + tracked.ga() + " → " + tracked.releaseValue()
                                    + " and "
                                    + candidate.ga() + " → " + candidate.releaseValue());
                }
                tracked = candidate;
            }
            if (tracked != null) propertyTracks.put(propName, tracked);
        }

        // ── Build property plans ────────────────────────────────────
        List<PropertyReleasePlan> propertyPlans = new ArrayList<>();
        for (PomSiteSurvey survey : scan.surveys()) {
            for (Map.Entry<String, String> decl :
                    survey.propertyDeclarations().entrySet()) {
                String propName = decl.getKey();
                ArtifactReleaseIntent tracked = propertyTracks.get(propName);
                if (tracked == null) continue;

                String preValue = decl.getValue();
                String releaseValue = tracked.releaseValue();
                String postReleaseValue = tracked.releaseValue();

                List<ReferenceSite> sites = new ArrayList<>();
                String placeholder = "${" + propName + "}";
                for (PomSiteSurvey s : scan.surveys()) {
                    for (ReferenceSite site : s.sites()) {
                        if (placeholder.equals(site.textAtSite())) {
                            sites.add(site);
                        }
                    }
                }

                String declaringSubproject =
                        attributeSubproject(survey.pomPath(), subprojects);

                PropertyReleasePlan plan = new PropertyReleasePlan(
                        propName,
                        survey.pomPath(),
                        declaringSubproject,
                        preValue,
                        releaseValue,
                        postReleaseValue,
                        sites);

                // Invariant: property's releaseValue MUST equal the
                // tracked artifact's releaseValue. Redundant with
                // construction above, but explicit — documents intent
                // and catches any future refactor that divorces them.
                if (!plan.releaseValue().equals(tracked.releaseValue())) {
                    throw new IllegalStateException(
                            "property ${" + propName + "} releaseValue "
                                    + plan.releaseValue()
                                    + " does not match tracked artifact "
                                    + tracked.ga() + " → "
                                    + tracked.releaseValue()
                                    + " (issue #209 shape)");
                }

                propertyPlans.add(plan);
            }
        }

        return new ReleasePlan(artifactPlans, propertyPlans);
    }

    /**
     * Whether {@code expr} is a Maven-built-in expression (resolves at
     * build time from the evaluator, not from any
     * {@code <properties>} block).
     *
     * <p>These expressions are local to each POM — their values differ
     * per subproject and must not be treated as in-cascade property
     * trackers. Excludes the {@code project.*}, {@code pom.*},
     * {@code env.*}, and {@code settings.*} namespaces, plus the
     * top-level {@code basedir}.
     *
     * @param expr the expression inside {@code ${...}}
     * @return true if Maven resolves this expression internally
     */
    static boolean isMavenBuiltinExpression(String expr) {
        return expr.startsWith("project.")
                || expr.startsWith("pom.")
                || expr.startsWith("env.")
                || expr.startsWith("settings.")
                || expr.equals("basedir")
                || expr.equals("project.basedir")
                || expr.equals("project.version")
                || expr.equals("project.groupId")
                || expr.equals("project.artifactId");
    }

    /**
     * Longest-prefix match: find the subproject whose root POM's
     * parent directory is an ancestor of {@code pomPath}. Returns the
     * empty string if no subproject contains this POM.
     */
    private static String attributeSubproject(
            Path pomPath, List<SubprojectRoot> subprojects) {
        Path normalized = pomPath.toAbsolutePath().normalize();
        SubprojectRoot best = null;
        int bestDepth = -1;
        for (SubprojectRoot sp : subprojects) {
            Path rootDir = sp.rootPomPath().getParent()
                    .toAbsolutePath().normalize();
            if (!normalized.startsWith(rootDir)) continue;
            int depth = rootDir.getNameCount();
            if (depth > bestDepth) {
                best = sp;
                bestDepth = depth;
            }
        }
        return best != null ? best.name() : "";
    }
}
