package com.chatdwellers.render;

import net.minecraft.network.chat.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side per-dweller tag store:
 * <ul>
 *   <li>which entity shows which floating Twitch nametag, and</li>
 *   <li>which Minecraft skin (by username) each tagged dweller should wear.</li>
 * </ul>
 * The skin map is what {@link DwellerSpawnTracker}'s tick handler re-asserts every client tick so
 * Vault's own {@code FighterEntity.tick()} can't reclaim the dweller and overwrite the viewer skin.
 */
public final class DwellerSkins {

    private static final Map<Integer, Component> NAMES = new ConcurrentHashMap<>();
    private static final Map<Integer, String> SKINS = new ConcurrentHashMap<>();

    private DwellerSkins() {}

    public static void setName(int entityId, Component name) {
        NAMES.put(entityId, name);
    }

    public static Component getName(int entityId) {
        return NAMES.get(entityId);
    }

    /** Records the Minecraft username whose skin {@code entityId} should wear. */
    public static void setSkin(int entityId, String mcName) {
        SKINS.put(entityId, mcName);
    }

    public static String getSkin(int entityId) {
        return SKINS.get(entityId);
    }

    /** Live view of all tagged dwellers (entity id → Minecraft username); safe to iterate. */
    public static Map<Integer, String> skins() {
        return SKINS;
    }

    public static void clear(int entityId) {
        NAMES.remove(entityId);
        SKINS.remove(entityId);
    }

    public static void clearAll() {
        NAMES.clear();
        SKINS.clear();
    }
}
