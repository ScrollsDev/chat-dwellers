package com.chatdwellers.twitch;

import com.chatdwellers.ChatDwellers;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

/**
 * Popup shown when Twitch device-code authorization starts. Offers Copy (link to clipboard),
 * Yes (open the link in the browser), and No (dismiss — covers an accidental popup). The
 * background polling in {@link TwitchAuthManager} keeps running regardless; re-open with
 * {@code /cd reconnect}.
 */
public final class TwitchActivateScreen extends Screen {

    private final String url;
    private final String userCode;

    public TwitchActivateScreen(String verificationUri, String verificationUriComplete, String userCode) {
        super(new TextComponent("Activate ChatDwellers on Twitch"));
        this.url = ActivationLink.best(verificationUri, verificationUriComplete);
        this.userCode = userCode;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int row = this.height / 2;

        // Copy button next to the instruction (for people who want to paste it themselves).
        this.addRenderableWidget(new Button(cx + 60, row - 24, 60, 20,
            new TextComponent("Copy"), b -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(url);
            }));

        // Yes — open the (code-prefilled) link in the default browser.
        this.addRenderableWidget(new Button(cx - 105, row + 20, 100, 20,
            new TextComponent("Yes, open it"), b -> {
                try {
                    Util.getPlatform().openUri(url);
                } catch (Exception e) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] failed to open browser: {}", e.toString());
                }
                onClose();
            }));

        // No — dismiss.
        this.addRenderableWidget(new Button(cx + 5, row + 20, 100, 20,
            new TextComponent("No"), b -> onClose()));
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(pose);
        drawCenteredString(pose, this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        drawCenteredString(pose, this.font,
            new TextComponent("Authorize, then continue on Twitch."),
            this.width / 2, this.height / 2 - 44, 0xA0A0A0);
        Component code = new TextComponent("Code: " + userCode);
        drawString(pose, this.font, code, this.width / 2 - 100, this.height / 2 - 19, 0xFFFF55);
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
