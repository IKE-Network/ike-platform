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
                // Scaffold-convention upgrades run after parent bumps so
                // that any parent-cascade-driven POM rewrites happen
                // first, and the scaffold layer reconciles against the
                // updated POMs.
                new ScaffoldConventionReconciler(),
                // Inter-subproject alignment runs after scaffold so the
                // POMs it rewrites already reflect the current
                // ike-tooling.version property and parent cascade. The
                // standalone ws:align-{draft,publish} goals also wrap
                // this reconciler — see AlignmentReconciler's class
                // javadoc for why both entry points coexist.
                new AlignmentReconciler()
                // Future reconcilers added here in the order they
                // should run (see #393 for the full migration plan).
        );
    }
}
