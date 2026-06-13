package com.chatdwellers.render;

import com.chatdwellers.ChatDwellers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Populates Vault's own per-entity skin slot so the FighterRenderer naturally picks the right
 * PlayerModel (slim or classic) with full overlay layers. We reach through reflection because
 * Vault's classes aren't on our compile classpath.
 *
 * <p><b>Why this isn't a one-shot.</b> Vault's {@code FighterEntity.tick()} runs every client tick
 * and, whenever the dweller's (server-synced) custom name differs from the entity's cached
 * {@code lastName}, calls {@code skin.updateSkin(customName)} — re-deriving the dweller's skin from
 * its custom name. A freshly spawned dweller has {@code lastName = "Vault Dweller"} while its real
 * custom name (a contributor's username) arrives a moment later over the wire, so the very next
 * tick after we inject a viewer's skin Vault overwrites it again. That was the bug: redeemed skins
 * flashed for a single tick and reverted to the default dweller skin.
 *
 * <p>{@link #maintain} fixes it by also writing {@link DwellerName#derive the derived custom name}
 * into {@code lastName}, so Vault's tick sees the custom name as already-processed and leaves our
 * skin alone. {@link DwellerSpawnTracker} calls it on spawn and again each client tick, so the tag
 * is self-healing even if a late entity-data sync slips a clobber through.
 *
 * <p>Resolution targets the first call and is cached for the lifetime of the JVM.
 */
public final class VaultSkinSupport {

    private static final String FIGHTER_ENTITY = "iskallia.vault.entity.entity.FighterEntity";
    private static final String SKIN_PROFILE = "iskallia.vault.util.SkinProfile";

    private static volatile boolean resolved = false;
    private static volatile boolean available = false;
    private static Class<?> fighterEntityClass;
    private static Constructor<?> skinProfileCtor;
    private static Method updateSkin;
    private static Method getLatestNickname;
    private static Field skinField;
    private static Field lastNameField;

    private VaultSkinSupport() {}

    /**
     * Ensures {@code entity} (a Vault dweller) wears {@code mcName}'s Mojang skin and keeps Vault's
     * own tick from reclaiming it. Safe to call every tick for any Entity — re-resolves the skin
     * only when it has drifted off {@code mcName}, and silently no-ops if the entity isn't a
     * FighterEntity or if Vault isn't loaded.
     */
    public static void maintain(Entity entity, String mcName) {
        if (!resolve()) return;
        if (!fighterEntityClass.isInstance(entity)) return;
        try {
            Object skin = skinField.get(entity);
            String current = skin == null ? null : (String) getLatestNickname.invoke(skin);
            if (skin == null || !mcName.equals(current)) {
                Object skinProfile = skinProfileCtor.newInstance();
                updateSkin.invoke(skinProfile, mcName);
                skinField.set(entity, skinProfile);
            }
            // Mark the current custom name as already-processed so Vault's FighterEntity.tick()
            // won't call updateSkin() and clobber the skin we just set.
            lastNameField.set(entity, derivedCustomName(entity));
        } catch (ReflectiveOperationException e) {
            ChatDwellers.LOGGER.warn("[ChatDwellers] failed to set Vault skin for {}: {}", mcName, e.toString());
        }
    }

    private static String derivedCustomName(Entity entity) {
        Component name = entity.getCustomName();
        return DwellerName.derive(name == null ? null : name.getString());
    }

    private static boolean resolve() {
        if (resolved) return available;
        synchronized (VaultSkinSupport.class) {
            if (resolved) return available;
            try {
                fighterEntityClass = Class.forName(FIGHTER_ENTITY);
                Class<?> spClass = Class.forName(SKIN_PROFILE);
                skinProfileCtor = spClass.getConstructor();
                updateSkin = spClass.getMethod("updateSkin", String.class);
                getLatestNickname = spClass.getMethod("getLatestNickname");
                skinField = fighterEntityClass.getField("skin");
                lastNameField = fighterEntityClass.getField("lastName");
                available = true;
            } catch (ReflectiveOperationException e) {
                ChatDwellers.LOGGER.info("[ChatDwellers] Vault skin API not available ({}); dwellers will use default skin", e.getClass().getSimpleName());
                available = false;
            }
            resolved = true;
            return available;
        }
    }
}
