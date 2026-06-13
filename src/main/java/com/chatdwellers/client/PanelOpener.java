package com.chatdwellers.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Opens the {@link ChatDwellersScreen} on the next client tick rather than directly from the
 * command handler. Opening a Screen straight from a command races with the chat box closing
 * right after the command runs (which calls {@code setScreen(null)} and clobbers ours) — and
 * the command may run on the server thread. Deferring to an END-phase client tick sidesteps both.
 *
 * <p>Registered explicitly from {@code ChatDwellers#clientSetup}.
 */
public final class PanelOpener {

    private static volatile boolean requested = false;

    private PanelOpener() {}

    /** Ask for the panel to open on the next client tick. Safe to call from any thread. */
    public static void request() {
        requested = true;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!requested) return;
        requested = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new ChatDwellersScreen());
        }
    }
}
