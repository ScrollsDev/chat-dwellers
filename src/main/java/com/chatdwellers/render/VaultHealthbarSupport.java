package com.chatdwellers.render;

import com.chatdwellers.ChatDwellers;
import net.minecraft.world.entity.Entity;
import java.lang.reflect.Method;

/**
 * Thin reflective bridge to Vault's own health-bar visibility check so we can lift the dweller's
 * nametag only while the bar is actually on screen. Vault decides bar visibility from its display
 * option (enabled / disabled / vault-only), render distance and line-of-sight — reproducing that
 * would be brittle, so we just ask it via {@code HealthbarRenderer.isShowingHealthbar(LivingEntity)}.
 *
 * <p>Resolution is cached for the JVM lifetime. If the API can't be found (Vault absent or a version
 * mismatch), {@link #available()} reports false and the caller falls back to its prior behaviour.
 */
public final class VaultHealthbarSupport {

    private static final String HEALTHBAR_RENDERER =
        "iskallia.vault.client.render.healthbar.HealthbarRenderer";
    private static final String LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity";

    private static volatile boolean resolved = false;
    private static volatile boolean available = false;
    private static Class<?> livingEntityClass;
    private static Method isShowingHealthbar;

    private VaultHealthbarSupport() {}

    /** Whether Vault's health-bar visibility API was found and is callable. */
    public static boolean available() {
        return resolve();
    }

    /**
     * @return true if Vault is currently drawing its health bar over {@code entity}. Returns false if
     *         the API is unavailable or {@code entity} isn't a LivingEntity — callers that want the
     *         old "always lift" behaviour should gate on {@link #available()} first.
     */
    public static boolean isShowingHealthbar(Entity entity) {
        if (!resolve()) return false;
        if (!livingEntityClass.isInstance(entity)) return false;
        try {
            return (Boolean) isShowingHealthbar.invoke(null, entity);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean resolve() {
        if (resolved) return available;
        synchronized (VaultHealthbarSupport.class) {
            if (resolved) return available;
            try {
                livingEntityClass = Class.forName(LIVING_ENTITY);
                Class<?> renderer = Class.forName(HEALTHBAR_RENDERER);
                isShowingHealthbar = renderer.getMethod("isShowingHealthbar", livingEntityClass);
                available = true;
            } catch (ReflectiveOperationException e) {
                ChatDwellers.LOGGER.info("[ChatDwellers] Vault health-bar API not available ({}); "
                    + "nametag will use a fixed offset", e.getClass().getSimpleName());
                available = false;
            }
            resolved = true;
            return available;
        }
    }
}
