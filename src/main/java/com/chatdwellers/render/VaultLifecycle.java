package com.chatdwellers.render;

/**
 * Pure detector of the player crossing in/out of a Vault dimension. In VH3 Remastered each
 * run is an instanced dimension {@code the_vault:vault_<uuid>}, so we match by the prefix
 * {@code the_vault:vault} (covers the template id and every instance) rather than an exact id.
 * Sibling dims {@code the_vault:arena} / {@code the_vault:the_other_side} are NOT vaults.
 * Fed the current dimension id (or null when there is no level) each client tick; reports the
 * edge. Correctness depends only on the LEFT_VAULT edge.
 */
public final class VaultLifecycle {

    /** Any dimension whose id starts with this is treated as "in a vault". */
    public static final String VAULT_PREFIX = "the_vault:vault";

    public enum Transition { NONE, ENTERED_VAULT, LEFT_VAULT }

    private String last = null;

    public synchronized Transition update(String currentDim) {
        boolean wasVault = inVault(last);
        boolean nowVault = inVault(currentDim);
        last = currentDim;
        if (nowVault && !wasVault) return Transition.ENTERED_VAULT;
        if (!nowVault && wasVault) return Transition.LEFT_VAULT;
        return Transition.NONE;
    }

    private static boolean inVault(String dim) {
        return dim != null && dim.startsWith(VAULT_PREFIX);
    }
}
