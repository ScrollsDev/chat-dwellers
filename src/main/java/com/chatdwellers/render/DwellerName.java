package com.chatdwellers.render;

import java.util.regex.Pattern;

/**
 * Reproduces the nickname Vault derives from a dweller's custom name.
 *
 * <p>Vault's {@code iskallia.vault.entity.entity.FighterEntity.tick()} runs every client tick and,
 * whenever the derived name differs from its cached {@code lastName}, calls
 * {@code skin.updateSkin(name)} — re-resolving the dweller's skin from its (server-synced) custom
 * name. That is exactly what was wiping out the viewer skin ChatDwellers injects: the next tick
 * after a dweller spawns, Vault recomputes the skin from the contributor name on the entity.
 *
 * <p>To stop that, ChatDwellers writes this same derived value into the entity's {@code lastName}
 * field, so Vault's tick sees the custom name as already-processed and leaves our skin alone. The
 * logic here must therefore match Vault's byte-for-byte: strip any leading U+2712 (✒) characters,
 * trim, and if the remainder starts with {@code '['} take the part after the first {@code ']'}.
 */
public final class DwellerName {

    private static final String PEN = String.valueOf((char) 10022); // ✒ U+2712
    private static final Pattern CLOSE_BRACKET = Pattern.compile(Pattern.quote("]"));

    private DwellerName() {}

    /**
     * @param customName the entity's custom-name text (e.g. {@code getCustomName().getString()}),
     *                   or null if it has none
     * @return the nickname Vault would feed to {@code SkinProfile.updateSkin}
     */
    public static String derive(String customName) {
        String name = customName == null ? "" : customName;
        while (name.startsWith(PEN)) {
            name = name.substring(1);
        }
        name = name.trim();
        if (name.startsWith("[")) {
            String[] parts = CLOSE_BRACKET.split(name);
            // Vault indexes parts[1] unguarded (and would crash on a missing ']'); we don't.
            if (parts.length > 1) {
                name = parts[1].trim();
            }
        }
        return name;
    }
}
