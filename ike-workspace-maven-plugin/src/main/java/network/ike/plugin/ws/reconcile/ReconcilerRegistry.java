package network.ike.plugin.ws.reconcile;

import java.util.List;

/**
 * Compile-time registry of the workspace-level reconcilers iterated
 * by {@code ws:scaffold-draft} (report) and {@code ws:scaffold-publish}
 * (apply).
 *
 * <p>Order matters: reconcilers run in the order returned by
 * {@link #all()}. Pure normalization (no version bumps, no clones)
 * runs first; version-bump reconcilers run next; clone-and-bootstrap
 * reconcilers run last. The ordering is enforced by code (not by
 * runtime configuration) to keep the convergence behavior
 * predictable and inspectable.
 *
 * @see Reconciler
 */
public final class ReconcilerRegistry {

    private ReconcilerRegistry() {}

    /**
     * @return the ordered list of all registered reconcilers
     */
    public static List<Reconciler> all() {
        return List.of(
                new FieldNormalizationReconciler(),
                new ParentVersionReconciler(),
                // Feature-branch version qualification runs after the
                // parent cascade and before AlignmentReconciler: it
                // qualifies each subproject's own version to its branch
                // slug, then alignment propagates the new version into
                // consumers' references (IKE-Network/ike-issues#574).
                new FeatureVersionReconciler(),
                // Scaffold-convention upgrades run after parent bumps so
                // that any parent-cascade-driven POM rewrites happen
                // first, and the scaffold layer reconciles against the
                // updated POMs.
                new ScaffoldConventionReconciler(),
                // Reactor-membership convergence runs after the scaffold
                // layer (which guarantees root="true" on the reactor POM)
                // and is independent of alignment/version state: it only
                // syncs the top-level <subprojects> block to
                // workspace.yaml and retires the legacy with-* profile
                // pattern (IKE-Network/ike-issues#696, completing #460).
                new ReactorSubprojectsReconciler(),
                // Maven wrapper version convergence groups with the other
                // "workspace infrastructure" reconcilers (reactor membership
                // above). Independent of alignment/version state — it pins
                // each subproject's .mvn/wrapper to defaults.maven-version so
                // a subproject onboarded with a stale wrapper can't break the
                // Maven 4 enforcer (IKE-Network/ike-issues#701).
                new MavenWrapperReconciler(),
                // Inter-subproject alignment runs after scaffold so the
                // POMs it rewrites already reflect the current
                // ike-tooling.version property and parent cascade. The
                // standalone ws:align-{draft,publish} goals also wrap
                // this reconciler — see AlignmentReconciler's class
                // javadoc for why both entry points coexist.
                new AlignmentReconciler(),
                // .mvn/extensions.xml — keep the literal version of
                // ike-workspace-extension in lockstep with the
                // ike-parent property (#460). Maven 4 does not
                // interpolate POM properties in extensions.xml at
                // extension-load time, so the literal is rewritten.
                new ExtensionsXmlReconciler(),
                // GOALS.md / WS-REFERENCE.md regeneration runs last —
                // a pure documentation pass that depends on no other
                // reconciler's output and produces no further drift
                // (IKE-Network/ike-issues#452). Before this entry,
                // cheatsheets refreshed only on ws:scaffold-init.
                new CheatsheetReconciler()
                // Future reconcilers added here in the order they
                // should run (see #393 for the full migration plan).
        );
    }
}
