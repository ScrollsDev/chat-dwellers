package com.chatdwellers.twitch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.function.Consumer;

/** Parses an EventSub WebSocket message frame and dispatches to one of the
 *  per-type sinks. Pure: takes JSON string, calls sinks. No IO. */
public final class EventSubRouter {

    private final Consumer<String> onWelcome;
    private final Consumer<PendingRedemption> onNotification;
    private final Consumer<String> onReconnect;
    private final Runnable onKeepalive;
    private final Consumer<String> onRevocation;

    public EventSubRouter(Consumer<String> onWelcome,
                          Consumer<PendingRedemption> onNotification,
                          Consumer<String> onReconnect,
                          Runnable onKeepalive,
                          Consumer<String> onRevocation) {
        this.onWelcome = onWelcome;
        this.onNotification = onNotification;
        this.onReconnect = onReconnect;
        this.onKeepalive = onKeepalive;
        this.onRevocation = onRevocation;
    }

    public void route(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("metadata")) return;
        String type = root.getAsJsonObject("metadata").get("message_type").getAsString();
        JsonObject payload = root.has("payload") ? root.getAsJsonObject("payload") : null;
        switch (type) {
            case "session_welcome" -> {
                if (payload != null && payload.has("session")) {
                    onWelcome.accept(payload.getAsJsonObject("session").get("id").getAsString());
                }
            }
            case "notification" -> {
                if (payload != null && payload.has("event")) {
                    JsonObject e = payload.getAsJsonObject("event");
                    onNotification.accept(new PendingRedemption(
                        e.get("id").getAsString(),
                        e.get("user_id").getAsString(),
                        e.get("user_name").getAsString(),
                        e.has("user_input") ? e.get("user_input").getAsString() : "",
                        e.get("redeemed_at").getAsString()));
                }
            }
            case "session_reconnect" -> {
                if (payload != null && payload.has("session")) {
                    JsonObject s = payload.getAsJsonObject("session");
                    if (s.has("reconnect_url")) onReconnect.accept(s.get("reconnect_url").getAsString());
                }
            }
            case "session_keepalive" -> onKeepalive.run();
            case "revocation" -> {
                if (payload != null && payload.has("subscription")) {
                    JsonObject sub = payload.getAsJsonObject("subscription");
                    onRevocation.accept(sub.has("status") ? sub.get("status").getAsString() : "");
                }
            }
            default -> { /* unknown future message types — ignore */ }
        }
    }
}
