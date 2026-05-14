package network.ike.plugin.ws.reconcile;

import java.util.Map;
import java.util.Optional;

/**
 * Flag bag passed through {@link WorkspaceContext} to reconcilers.
 * Backs the convergence-pattern flag scheme defined in
 * IKE-Network/ike-issues#393:
 *
 * <ul>
 *   <li><b>Default</b> (no flag): reconcile to latest.</li>
 *   <li><b>Opt out</b>: {@code -D<optOutFlag>=false} — skip this
 *       reconciler entirely.</li>
 *   <li><b>Pin</b>: {@code -D<pinFlag>=<value>} — force a specific
 *       value rather than the latest discovered automatically.</li>
 * </ul>
 *
 * @param rawFlags map of flag-name → string value (already extracted
 *                 from Maven system properties by the calling Mojo)
 */
public record ReconcilerOptions(Map<String, String> rawFlags) {

    /**
     * @param flag flag name (without {@code -D} prefix)
     * @return true if the flag is present with value {@code "false"}
     */
    public boolean isOptedOut(String flag) {
        return "false".equals(rawFlags.get(flag));
    }

    /**
     * @param flag pin-flag name (without {@code -D} prefix)
     * @return the pinned value, or empty if no pin was provided
     */
    public Optional<String> pin(String flag) {
        return Optional.ofNullable(rawFlags.get(flag));
    }

    /**
     * @return an options bag with no flags set
     */
    public static ReconcilerOptions empty() {
        return new ReconcilerOptions(Map.of());
    }
}
