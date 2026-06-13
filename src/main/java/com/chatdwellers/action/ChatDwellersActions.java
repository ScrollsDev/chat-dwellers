package com.chatdwellers.action;

import com.chatdwellers.ChatDwellers;
import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.config.Config;
import com.chatdwellers.pool.DwellerPool;
import com.chatdwellers.pool.PendingViewer;
import com.chatdwellers.render.DwellerSkins;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** Side-effecting actions shared by the typed commands and the control panel. */
public final class ChatDwellersActions {

    private ChatDwellersActions() {}

    /** Flips enabled state, restarting/stopping the Twitch bootstrap. Returns a result message. */
    public static String toggle() {
        boolean now = !Config.enabled();
        Config.setEnabled(now);
        if (ChatDwellersClient.bootstrap != null) {
            if (now) ChatDwellersClient.bootstrap.restart();
            else ChatDwellersClient.bootstrap.stop();
        }
        return now ? "Enabled."
            : "Disabled. Pending redemptions stay until re-enabled or purged.";
    }

    public static String reconnect() {
        if (ChatDwellersClient.bootstrap == null) return "Twitch bootstrap not initialized.";
        ChatDwellersClient.bootstrap.restart();
        return "Reconnecting to Twitch...";
    }

    public static String purge() {
        DwellerPool pool = ChatDwellersClient.pool;
        List<PendingViewer> drained = pool.purge();
        DwellerSkins.clearAll();
        int refunded = 0;
        if (ChatDwellersClient.helixClient != null
            && ChatDwellersClient.tokenStore != null
            && !ChatDwellersClient.tokenStore.rewardId().isEmpty()) {
            String rewardId = ChatDwellersClient.tokenStore.rewardId();
            String broadcasterId = ChatDwellersClient.tokenStore.broadcasterId();
            for (PendingViewer v : drained) {
                if (v.redemptionId().startsWith("sim:")) continue;
                refunded++;
                final String redemptionId = v.redemptionId();
                CompletableFuture.runAsync(() -> {
                    try {
                        ChatDwellersClient.helixClient.updateRedemption(
                            broadcasterId, rewardId, redemptionId, "CANCELED");
                    } catch (Exception e) {
                        ChatDwellers.LOGGER.warn("[ChatDwellers] failed to refund {}: {}",
                            redemptionId, e.toString());
                    }
                });
            }
        }
        return "Purged " + drained.size() + " viewer(s)"
            + (refunded > 0 ? " (refunding " + refunded + " on Twitch)" : "") + ".";
    }

    /** Fulfills (on Twitch) and drops every viewer shown so far, keeping the un-shown ones
     *  queued. Shared by the manual /cd claim and by the vault-exit auto-clear path. */
    public static String claimShown() {
        DwellerPool pool = ChatDwellersClient.pool;
        List<PendingViewer> shown = pool.retainUnshown();
        int kept = pool.size();
        int fulfilled = 0;
        if (ChatDwellersClient.helixClient != null
            && ChatDwellersClient.tokenStore != null
            && !ChatDwellersClient.tokenStore.rewardId().isEmpty()) {
            String rewardId = ChatDwellersClient.tokenStore.rewardId();
            String broadcasterId = ChatDwellersClient.tokenStore.broadcasterId();
            for (PendingViewer v : shown) {
                if (v.redemptionId().startsWith("sim:")) continue;
                fulfilled++;
                final String redemptionId = v.redemptionId();
                CompletableFuture.runAsync(() -> {
                    try {
                        ChatDwellersClient.helixClient.updateRedemption(
                            broadcasterId, rewardId, redemptionId, "FULFILLED");
                    } catch (Exception e) {
                        ChatDwellers.LOGGER.warn("[ChatDwellers] failed to fulfill {} on claim: {}",
                            redemptionId, e.toString());
                    }
                });
            }
        }
        return "Claimed " + shown.size() + " shown viewer(s)"
            + (fulfilled > 0 ? " (fulfilling " + fulfilled + " on Twitch)" : "")
            + "; " + kept + " kept queued.";
    }

    public static String setAutoClear(boolean on) {
        Config.setClearQueueOnVaultExit(on);
        return "Auto-clear on vault exit: " + (on ? "ON" : "OFF") + ".";
    }

    public static String addBlacklist(String name, String message) {
        Config.addBlacklist(name, message);
        return "Blacklisted '" + name + "'.";
    }

    public static String removeBlacklist(String name) {
        return Config.removeBlacklist(name)
            ? "Removed '" + name + "' from blacklist."
            : "'" + name + "' was not blacklisted.";
    }

    public static String listBlacklist() {
        List<String> entries = Config.blacklistEntries();
        return entries.isEmpty() ? "Blacklist is empty." : "Blacklist: " + String.join(", ", entries);
    }

    public static String setCost(int amount) {
        Config.setRewardCost(amount);
        boolean canPush = ChatDwellersClient.helixClient != null
            && ChatDwellersClient.tokenStore != null
            && !ChatDwellersClient.tokenStore.rewardId().isEmpty();
        if (canPush) {
            String rewardId = ChatDwellersClient.tokenStore.rewardId();
            String broadcasterId = ChatDwellersClient.tokenStore.broadcasterId();
            CompletableFuture.runAsync(() -> {
                try {
                    ChatDwellersClient.helixClient.updateRewardCost(broadcasterId, rewardId, amount);
                } catch (Exception e) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] failed to update reward cost: {}",
                        e.toString());
                }
            });
        }
        return "Reward cost set to " + amount + " points"
            + (canPush ? " (updating on Twitch)." : " (saved; applies on reconnect).");
    }

    public static String simulate(String twitch, String mc) {
        PendingViewer viewer = new PendingViewer(
            twitch.toLowerCase(Locale.ROOT), twitch, mc, "sim:" + UUID.randomUUID());
        DwellerPool.EnqueueResult result =
            ChatDwellersClient.pool.enqueue(viewer, Config.maxPoolSize());
        return "simulate " + twitch + " / " + mc + " -> " + result;
    }

    /** One-line live status, used by /cd status and drawn in the panel header. */
    public static String statusLine() {
        DwellerPool pool = ChatDwellersClient.pool;
        String names = pool.snapshot().stream()
            .map(PendingViewer::twitchName).collect(Collectors.joining(", "));
        String enabledState = Config.enabled() ? "ON" : "OFF";
        String twitchState = "DISABLED";
        if (ChatDwellersClient.tokenStore != null) {
            if (ChatDwellersClient.tokenStore.accessToken().isEmpty()) twitchState = "UNAUTHORIZED";
            else if (ChatDwellersClient.tokenStore.rewardId().isEmpty()) twitchState = "REWARD-MISSING";
            else twitchState = "CONNECTED";
        }
        return enabledState + " | Twitch: " + twitchState
            + " | auto-clear: " + (Config.clearQueueOnVaultExit() ? "ON" : "OFF")
            + " | reward: '" + Config.rewardName() + "' (" + Config.rewardCost() + " pts)"
            + " | queue (" + pool.size() + "): " + names;
    }
}
