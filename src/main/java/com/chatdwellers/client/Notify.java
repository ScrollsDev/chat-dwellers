package com.chatdwellers.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.ForgeIngameGui;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * ChatDwellers notifications, drawn as fading cards in the <b>bottom-right</b> corner (Minecraft's
 * built-in toasts are locked to the top-right, so we render our own HUD overlay instead). The
 * overlay is registered explicitly from {@code ChatDwellers#clientSetup} via {@code OverlayRegistry}.
 */
public final class Notify {

    private record Note(String text, long expiresAt) {}

    private static final ConcurrentLinkedDeque<Note> NOTES = new ConcurrentLinkedDeque<>();
    private static final long TTL_MS = 6000L;
    private static final int MAX = 6;
    private static final String PREFIX = "[ChatDwellers] ";

    private Notify() {}

    /** Queue a notification card. Safe to call from any thread. */
    public static void toast(String message) {
        String body = message.startsWith(PREFIX) ? message.substring(PREFIX.length()) : message;
        NOTES.addLast(new Note(body, System.currentTimeMillis() + TTL_MS));
        while (NOTES.size() > MAX) NOTES.pollFirst();
    }

    /** Forge HUD overlay: draws active notifications stacked in the bottom-right corner. */
    public static void renderOverlay(ForgeIngameGui gui, PoseStack pose,
                                     float partialTick, int width, int height) {
        long now = System.currentTimeMillis();
        NOTES.removeIf(n -> n.expiresAt() <= now);
        if (NOTES.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        Font font = mc.font;

        // Wrap each message to a narrow column so long lines (e.g. /cd status) stack taller
        // instead of running off the right edge.
        int wrapWidth = 150;
        List<FormattedCharSequence> body = new ArrayList<>();
        for (Note n : NOTES) {
            body.addAll(font.split(new TextComponent(n.text()), wrapWidth));
        }

        String title = "ChatDwellers";
        int maxW = font.width(title);
        for (FormattedCharSequence l : body) maxW = Math.max(maxW, font.width(l));

        int pad = 4;
        int lineH = 10;
        int boxW = maxW + pad * 2;
        int boxH = (body.size() + 1) * lineH + pad * 2;
        int x1 = width - 4;
        int y1 = height - 4;
        int x0 = x1 - boxW;
        int y0 = y1 - boxH;

        GuiComponent.fill(pose, x0, y0, x1, y1, 0xC0101010);
        font.drawShadow(pose, title, (float) (x0 + pad), (float) (y0 + pad), 0xFFFFAA66);
        int y = y0 + pad + lineH;
        for (FormattedCharSequence l : body) {
            font.drawShadow(pose, l, (float) (x0 + pad), (float) y, 0xFFFFFFFF);
            y += lineH;
        }
    }
}
