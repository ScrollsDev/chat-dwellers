package com.chatdwellers.render;

import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.action.ChatDwellersActions;
import com.chatdwellers.config.Config;
import com.chatdwellers.render.VaultLifecycle.Transition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Watches the player's dimension each client tick. On leaving the Vault, clears the queue per
 * the {@code clearQueueOnVaultExit} toggle: when ON, viewers who appeared this vault are
 * fulfilled on Twitch and dropped (un-shown carry over); when OFF, the queue is left intact so
 * the same viewers keep appearing until /cd claim or /cd purge.
 *
 * <p>Registered explicitly from {@code ChatDwellers#clientSetup} (not via @Mod.EventBusSubscriber).
 */
public final class VaultLifecycleHandler {

    private static final VaultLifecycle LIFECYCLE = new VaultLifecycle();

    private VaultLifecycleHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        String dim = (level == null) ? null : level.dimension().location().toString();

        if (LIFECYCLE.update(dim) == Transition.LEFT_VAULT) {
            onVaultExit();
        }
    }

    private static void onVaultExit() {
        DwellerSkins.clearAll();
        if (!Config.clearQueueOnVaultExit()) return;
        if (Config.enabled()) {
            ChatDwellersActions.claimShown();
        } else {
            // Mod off: drop the shown viewers locally but don't touch Twitch.
            ChatDwellersClient.pool.retainUnshown();
        }
    }
}
