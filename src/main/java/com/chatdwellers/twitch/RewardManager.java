package com.chatdwellers.twitch;

import com.chatdwellers.ChatDwellers;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class RewardManager {

    private final HelixClient helix;
    private final TokenStore store;
    private final Consumer<String> chatNote;
    private final Supplier<String> rewardName;
    private final IntSupplier rewardCost;
    private final Supplier<String> rewardPrompt;

    public RewardManager(HelixClient helix, TokenStore store, Consumer<String> chatNote,
                         Supplier<String> rewardName, IntSupplier rewardCost, Supplier<String> rewardPrompt) {
        this.helix = helix;
        this.store = store;
        this.chatNote = chatNote;
        this.rewardName = rewardName;
        this.rewardCost = rewardCost;
        this.rewardPrompt = rewardPrompt;
    }

    /**
     * Ensures the reward exists and that we can manage it. Returns the reward id, or empty string if
     * a same-titled reward exists that we didn't create (so Twitch won't let us manage it).
     *
     * <p>Order of resolution:
     * <ol>
     *   <li>If we already have a stored id, use it.</li>
     *   <li>Otherwise look for a reward <em>we created</em> with this title and re-adopt it — this
     *       recovers gracefully when the locally stored id was lost but the reward still exists.</li>
     *   <li>Otherwise create it fresh.</li>
     * </ol>
     */
    public String ensureReward() {
        if (!store.rewardId().isEmpty()) return store.rewardId();
        String name = rewardName.get();
        try {
            if (store.broadcasterId().isEmpty()) {
                store.setBroadcasterId(helix.getBroadcasterId());
            }

            String existing = helix.findManageableRewardId(store.broadcasterId(), name);
            if (!existing.isEmpty()) {
                store.setRewardId(existing);
                chatNote.accept("[ChatDwellers] Reconnected to your existing reward '" + name + "'.");
                return existing;
            }

            String id = helix.createReward(
                store.broadcasterId(), name, rewardCost.getAsInt(), rewardPrompt.get());
            store.setRewardId(id);
            chatNote.accept("[ChatDwellers] Reward '" + name + "' created.");
            chatNote.accept("[ChatDwellers] Manage it at: Creator Dashboard > Viewer Rewards > Channel Points");
            return id;
        } catch (HelixClient.DuplicateRewardException dup) {
            // The title is taken by a reward we can't manage (made by hand, or by another app).
            chatNote.accept("[ChatDwellers] A reward titled '" + name + "' already exists but was "
                + "not created by ChatDwellers, so it can't be managed. Delete it in the Twitch "
                + "dashboard and run /chatdwellers reconnect.");
            return "";
        } catch (IOException e) {
            ChatDwellers.LOGGER.error("[ChatDwellers] reward creation failed", e);
            chatNote.accept("[ChatDwellers] Reward setup failed: " + e.getMessage());
            return "";
        }
    }
}
