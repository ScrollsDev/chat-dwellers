package com.chatdwellers.render;

import com.chatdwellers.config.Config;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RenderNameplateEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// Registered explicitly from ChatDwellers#clientSetup (not via @Mod.EventBusSubscriber).
public final class NametagHandler {

    private NametagHandler() {}

    @SubscribeEvent
    public static void onRenderNameplate(RenderNameplateEvent event) {
        Component name = DwellerSkins.getName(event.getEntity().getId());
        if (name != null) {
            // Sit at the normal nameplate height, and only lift clear of Vault's health-bar overlay
            // while that bar is actually on screen. If Vault's visibility API is unavailable we can't
            // tell, so we keep lifting (the prior behaviour) to avoid overlapping a bar we can't see.
            boolean barShowing = !VaultHealthbarSupport.available()
                || VaultHealthbarSupport.isShowingHealthbar(event.getEntity());
            if (barShowing) {
                event.getPoseStack().translate(0.0, Config.nametagYOffset(), 0.0);
            }
            event.setContent(name);
            event.setResult(Event.Result.ALLOW);
        }
    }
}
