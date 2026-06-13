package com.chatdwellers.render;

import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.config.Config;
import com.chatdwellers.pool.PendingViewer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.EntityLeaveWorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.Map;

// Registered explicitly from ChatDwellers#clientSetup (not via @Mod.EventBusSubscriber).
public final class DwellerSpawnTracker {

    private DwellerSpawnTracker() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinWorldEvent event) {
        if (!event.getWorld().isClientSide()) return;
        if (!Config.enabled()) return;

        Entity entity = event.getEntity();
        ResourceLocation type = EntityType.getKey(entity.getType());
        if (!type.getNamespace().equals("the_vault")) return;
        if (!type.getPath().startsWith("vault_fighter")) return;

        // Assignment is bound here, at spawn, and never applied retroactively. Queue empty ->
        // this dweller keeps the default skin for its lifetime (never revisited).
        PendingViewer viewer = ChatDwellersClient.pool.nextForSpawn().orElse(null);
        if (viewer == null) return;

        int id = entity.getId();
        DwellerSkins.setName(id, new TextComponent(Config.formatNametag(viewer.twitchName(), viewer.mcName())));
        DwellerSkins.setSkin(id, viewer.mcName());
        VaultSkinSupport.maintain(entity, viewer.mcName());
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveWorldEvent event) {
        if (!event.getWorld().isClientSide()) return;
        DwellerSkins.clear(event.getEntity().getId());
    }

    /**
     * Re-asserts each tagged dweller's skin at the start of every client tick — before Vault's
     * {@code FighterEntity.tick()} runs — so Vault can't re-derive the skin from the custom name
     * and overwrite the viewer's. {@link VaultSkinSupport#maintain} is idempotent.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!Config.enabled()) return;
        if (DwellerSkins.skins().isEmpty()) return;

        var mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (Map.Entry<Integer, String> tag : DwellerSkins.skins().entrySet()) {
            Entity entity = mc.level.getEntity(tag.getKey());
            if (entity != null) {
                VaultSkinSupport.maintain(entity, tag.getValue());
            }
        }
    }
}
