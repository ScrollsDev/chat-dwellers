package com.chatdwellers.client;

import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.action.ChatDwellersActions;
import com.chatdwellers.config.Config;
import com.chatdwellers.pool.PendingViewer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import java.util.List;

/** Primary ChatDwellers UI: live status + buttons. Opened by typing {@code /cd}. */
public final class ChatDwellersScreen extends Screen {

    private EditBox costField;
    private EditBox simTwitch;
    private EditBox simMc;
    private Button toggleButton;
    private Button autoClearButton;

    public ChatDwellersScreen() {
        super(new TextComponent("ChatDwellers"));
    }

    private TextComponent toggleLabel() {
        return new TextComponent(Config.enabled() ? "Turn OFF" : "Turn ON");
    }

    private TextComponent autoClearLabel() {
        return new TextComponent(Config.clearQueueOnVaultExit() ? "Auto-clear: ON" : "Auto-clear: OFF");
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = 56;

        toggleButton = this.addRenderableWidget(new Button(cx - 154, top, 100, 20,
            toggleLabel(), b -> {
                Notify.toast(ChatDwellersActions.toggle());
                toggleButton.setMessage(toggleLabel());
            }));
        this.addRenderableWidget(new Button(cx - 50, top, 100, 20,
            new TextComponent("Reconnect"), b -> Notify.toast(ChatDwellersActions.reconnect())));
        this.addRenderableWidget(new Button(cx + 54, top, 100, 20,
            new TextComponent("Purge"), b -> Notify.toast(ChatDwellersActions.purge())));

        // Cost row
        costField = new EditBox(this.font, cx - 154, top + 44, 100, 20, new TextComponent("cost"));
        costField.setValue(Integer.toString(Config.rewardCost()));
        this.addRenderableWidget(costField);
        this.addRenderableWidget(new Button(cx - 50, top + 44, 100, 20,
            new TextComponent("Set cost"), b -> {
                try {
                    int v = Math.max(1, Integer.parseInt(costField.getValue().trim()));
                    Notify.toast(ChatDwellersActions.setCost(v));
                } catch (NumberFormatException e) {
                    Notify.toast("Cost must be a whole number.");
                }
            }));

        // Simulate (testing) row
        simTwitch = new EditBox(this.font, cx - 154, top + 88, 100, 20, new TextComponent("twitch"));
        simMc = new EditBox(this.font, cx - 50, top + 88, 100, 20, new TextComponent("mc"));
        this.addRenderableWidget(simTwitch);
        this.addRenderableWidget(simMc);
        this.addRenderableWidget(new Button(cx + 54, top + 88, 100, 20,
            new TextComponent("Add (test)"), b -> {
                if (!simTwitch.getValue().isBlank() && !simMc.getValue().isBlank()) {
                    Notify.toast(ChatDwellersActions.simulate(
                        simTwitch.getValue().trim(), simMc.getValue().trim()));
                }
            }));

        autoClearButton = this.addRenderableWidget(new Button(cx - 154, this.height - 32, 100, 20,
            autoClearLabel(), b -> {
                Notify.toast(ChatDwellersActions.setAutoClear(!Config.clearQueueOnVaultExit()));
                autoClearButton.setMessage(autoClearLabel());
            }));
        this.addRenderableWidget(new Button(cx - 50, this.height - 32, 100, 20,
            new TextComponent("Done"), b -> onClose()));
        this.addRenderableWidget(new Button(cx + 54, this.height - 32, 100, 20,
            new TextComponent("Claim shown"), b -> Notify.toast(ChatDwellersActions.claimShown())));
    }

    @Override
    public void tick() {
        costField.tick();
        simTwitch.tick();
        simMc.tick();
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(pose);
        int cx = this.width / 2;
        drawCenteredString(pose, this.font, this.title, cx, 16, 0xFFFFFF);
        drawCenteredString(pose, this.font,
            new TextComponent(ChatDwellersActions.statusLine()), cx, 34, 0xA0A0A0);
        drawString(pose, this.font, new TextComponent("Cost:"), cx - 154, 56 + 34, 0xFFFFFF);
        drawString(pose, this.font, new TextComponent("Test viewer (twitch / mc):"),
            cx - 154, 56 + 78, 0xFFFFFF);

        List<PendingViewer> q = ChatDwellersClient.pool.snapshot();
        int y = 56 + 120;
        drawString(pose, this.font, new TextComponent("Queue (" + q.size() + "):"),
            cx - 154, y, 0xFFFFFF);
        int shown = Math.min(q.size(), 8);
        for (int i = 0; i < shown; i++) {
            drawString(pose, this.font, new TextComponent("- " + q.get(i).twitchName()),
                cx - 150, y + 12 + i * 10, 0xC0C0C0);
        }
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }
}
