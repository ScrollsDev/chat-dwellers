package com.chatdwellers.twitch;

import com.chatdwellers.ChatDwellers;
import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.config.Config;
import com.chatdwellers.pool.DwellerPool;
import com.chatdwellers.pool.PendingViewer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Wires everything together at boot. Safe to call restart() at any time. */
public final class TwitchBootstrap {

    private final TokenStore store;
    private final TwitchAuthManager auth;
    private final HelixClient helix;
    private final RewardManager rewards;
    private final EventSubRouter router;
    // Not final because the router's onReconnect lambda references this.listener
    // before its assignment line below; the actual init happens before any lambda fires.
    private TwitchEventListener listener;
    private final RedemptionHandler redemptionHandler;
    /** Guards against spamming chat with the same subscribe error on every reconnect;
     *  reset to false after any successful subscribe. */
    private volatile boolean subscribeErrorReported = false;

    public TwitchBootstrap() {
        Path secretFile = FMLPaths.CONFIGDIR.get().resolve("chatdwellers-secret.toml");
        this.store = TokenStore.atPath(secretFile);

        JsonHttp http = JsonHttp.prod();
        Supplier<String> clientId = Config::twitchClientId;
        this.auth = new TwitchAuthManager(http, store, clientId, TwitchBootstrap::sendChat,
            TwitchBootstrap::showActivation);

        Supplier<String> accessToken = store::accessToken;
        this.helix = new HelixClient(http, clientId, accessToken, auth::refresh);
        this.rewards = new RewardManager(helix, store, TwitchBootstrap::sendChat,
            Config::rewardName, Config::rewardCost, Config::rewardPrompt);

        DwellerPool pool = ChatDwellersClient.pool;
        MojangApi mojang = new MojangApi();
        // Retry once on transient Mojang failure per the spec's error-handling table;
        // on permanent failure, treat as "not found" so the handler refunds the viewer.
        RedemptionHandler.NameResolver nameResolver = name -> {
            try {
                return mojang.resolveUuid(name).isPresent();
            } catch (java.io.IOException first) {
                try { Thread.sleep(1000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                try {
                    return mojang.resolveUuid(name).isPresent();
                } catch (java.io.IOException second) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] Mojang lookup failed for {}: {}",
                        name, second.toString());
                    return false;
                }
            }
        };
        RedemptionHandler.Updater updater = (redemptionId, status) ->
            helix.updateRedemption(store.broadcasterId(), store.rewardId(), redemptionId, status);
        java.util.function.Consumer<String> twitchChat = msg -> {
            String bid = store.broadcasterId();
            if (bid.isEmpty()) {
                ChatDwellers.LOGGER.warn("[ChatDwellers] cannot send Twitch chat (no broadcaster id): {}", msg);
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    helix.sendChatMessage(bid, bid, msg);
                } catch (Exception e) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] failed to send Twitch chat: {}", e.toString());
                }
            });
        };
        this.redemptionHandler = new RedemptionHandler(pool, updater, nameResolver,
            TwitchBootstrap::sendChat, twitchChat, Config::blacklist, Config.maxPoolSize());

        this.router = new EventSubRouter(
            sessionId -> CompletableFuture.runAsync(() -> {
                // A welcome that follows a Twitch-directed reconnect carries the existing
                // subscriptions over — re-subscribing here is redundant and burns the
                // EventSub subscribe rate limit (HTTP 429), so skip it.
                if (listener.consumeMigration()) {
                    ChatDwellers.LOGGER.info(
                        "[ChatDwellers] reconnect welcome sid={}, subscriptions retained; skipping subscribe",
                        sessionId);
                    return;
                }
                ChatDwellers.LOGGER.info("[ChatDwellers] session_welcome sid={}, subscribing", sessionId);
                try {
                    helix.subscribeEventSub(sessionId, store.broadcasterId(), store.rewardId());
                    subscribeErrorReported = false;
                    listener.onSubscribed(); // confirmed subscribe → reset reconnect backoff
                    ChatDwellers.LOGGER.info("[ChatDwellers] subscribe OK for reward {} bid {}",
                        store.rewardId(), store.broadcasterId());
                } catch (Exception e) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] subscribe FAILED: {}", e.toString());
                    // Surface to chat once; don't spam a line on every retry/reconnect.
                    if (!subscribeErrorReported) {
                        subscribeErrorReported = true;
                        sendChat("[ChatDwellers] EventSub subscribe failed: " + e.getMessage());
                    }
                }
            }),
            r -> {
                ChatDwellers.LOGGER.info("[ChatDwellers] notification received: id={} user={} input='{}'",
                    r.id(), r.userName(), r.userInput());
                dispatch(r);
            },
            url -> this.listener.reconnectTo(url),
            () -> {},
            reason -> sendChat("[ChatDwellers] EventSub subscription revoked: " + reason));
        this.listener = new TwitchEventListener(router);
    }

    public void start() {
        if (!Config.enabled()) {
            ChatDwellers.LOGGER.info("[ChatDwellers] disabled via config; skipping Twitch bootstrap.");
            return;
        }
        if (Config.twitchClientId().isBlank()) {
            sendChat("[ChatDwellers] Set 'twitchClientId' in chatdwellers-local.toml then /chatdwellers reconnect.");
            return;
        }
        CompletableFuture.runAsync(() -> {
            if (!auth.hasValidToken()
                || !auth.hasGrantedScopes(TwitchAuthManager.requiredScopes())) {
                try { auth.startDeviceCode().get(); } catch (Exception e) { return; }
            }
            String rewardId = rewards.ensureReward();
            if (rewardId.isEmpty()) return;
            resyncPending();
            // Clear stale websocket subscriptions from prior sessions before connecting, so the
            // fresh subscribe doesn't 429 with "websocket transports limit exceeded".
            try {
                int cleared = helix.deleteAllEventSubSubscriptions();
                if (cleared > 0) {
                    ChatDwellers.LOGGER.info("[ChatDwellers] cleared {} stale EventSub subscription(s)", cleared);
                }
            } catch (Exception e) {
                ChatDwellers.LOGGER.warn("[ChatDwellers] EventSub subscription cleanup failed: {}", e.toString());
            }
            listener.start();
        });
    }

    public void restart() {
        listener.stop();
        start();
    }

    /** Stops the EventSub listener without clearing tokens. Restartable via start(). */
    public void stop() {
        listener.stop();
    }

    public TokenStore store() { return store; }
    public HelixClient helix() { return helix; }

    private void resyncPending() {
        try {
            DwellerPool pool = ChatDwellersClient.pool;
            for (PendingRedemption r : helix.listPending(store.broadcasterId(), store.rewardId())) {
                pool.enqueue(new PendingViewer(r.userId(), r.userName(), r.userInput(), r.id()),
                    Config.maxPoolSize());
            }
        } catch (Exception e) {
            ChatDwellers.LOGGER.warn("[ChatDwellers] resync failed: {}", e.toString());
        }
    }

    private void dispatch(PendingRedemption r) {
        try { redemptionHandler.handle(r); }
        catch (Exception e) { ChatDwellers.LOGGER.warn("[ChatDwellers] handler threw: {}", e.toString()); }
    }

    private static void showActivation(String uri, String uriComplete, String userCode) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(
            new com.chatdwellers.twitch.TwitchActivateScreen(uri, uriComplete, userCode)));
    }

    private static void sendChat(String message) {
        ChatDwellers.LOGGER.info(message);
        com.chatdwellers.client.Notify.toast(message);
    }
}
