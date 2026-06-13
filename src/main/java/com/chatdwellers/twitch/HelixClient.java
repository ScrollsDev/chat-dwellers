package com.chatdwellers.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Thin JSON wrapper over the five Helix endpoints we use. */
public final class HelixClient {

    private static final String BASE = "https://api.twitch.tv/helix";

    private final JsonHttp http;
    private final Supplier<String> clientId;
    private final Supplier<String> accessToken;
    private final Runnable refreshToken;

    public HelixClient(JsonHttp http, Supplier<String> clientId,
                       Supplier<String> accessToken, Runnable refreshToken) {
        this.http = http;
        this.clientId = clientId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public String getBroadcasterId() throws IOException {
        JsonHttp.Response r = sendWithRefresh(builder(BASE + "/users").GET().build());
        return firstDataId(r.body());
    }

    public String createReward(String broadcasterId, String name, int cost, String prompt) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("title", name);
        body.addProperty("cost", cost);
        body.addProperty("prompt", prompt);
        body.addProperty("is_user_input_required", true);
        body.addProperty("is_enabled", true);

        HttpRequest req = builder(BASE + "/channel_points/custom_rewards?broadcaster_id=" + broadcasterId)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        JsonHttp.Response r = sendWithRefresh(req);
        if (r.status() == 400 && r.body().toUpperCase().contains("DUPLICATE")) {
            throw new DuplicateRewardException(r.body());
        }
        return firstDataId(r.body());
    }

    /**
     * Returns the id of a reward <em>this app created</em> (i.e. one we're allowed to manage) whose
     * title matches {@code title}, or "" if there is none. Lets us re-adopt our own reward after the
     * locally stored reward id has been lost, instead of trying to create a duplicate.
     *
     * <p>{@code only_manageable_rewards=true} scopes the listing to rewards created by the calling
     * Client-Id, so a reward made by hand in the dashboard (or by another app) is correctly excluded.
     */
    public String findManageableRewardId(String broadcasterId, String title) throws IOException {
        String url = BASE + "/channel_points/custom_rewards"
            + "?broadcaster_id=" + broadcasterId
            + "&only_manageable_rewards=true";
        JsonHttp.Response r = sendWithRefresh(builder(url).GET().build());
        if (r.status() < 200 || r.status() >= 300) {
            throw new IOException("listManageableRewards HTTP " + r.status() + ": " + r.body());
        }
        JsonObject obj = JsonParser.parseString(r.body()).getAsJsonObject();
        JsonArray data = obj.getAsJsonArray("data");
        if (data == null) return "";
        for (int i = 0; i < data.size(); i++) {
            JsonObject e = data.get(i).getAsJsonObject();
            if (e.has("title") && title.equalsIgnoreCase(e.get("title").getAsString())) {
                return e.get("id").getAsString();
            }
        }
        return "";
    }

    public List<PendingRedemption> listPending(String broadcasterId, String rewardId) throws IOException {
        List<PendingRedemption> all = new ArrayList<>();
        String cursor = null;
        do {
            String url = BASE + "/channel_points/custom_rewards/redemptions"
                + "?broadcaster_id=" + broadcasterId
                + "&reward_id=" + rewardId
                + "&status=UNFULFILLED"
                + "&first=50"
                + (cursor == null ? "" : "&after=" + cursor);
            JsonHttp.Response r = sendWithRefresh(builder(url).GET().build());
            JsonObject obj = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonArray data = obj.getAsJsonArray("data");
            if (data != null) {
                for (int i = 0; i < data.size(); i++) {
                    JsonObject e = data.get(i).getAsJsonObject();
                    all.add(new PendingRedemption(
                        e.get("id").getAsString(),
                        e.get("user_id").getAsString(),
                        e.get("user_name").getAsString(),
                        e.has("user_input") ? e.get("user_input").getAsString() : "",
                        e.get("redeemed_at").getAsString()
                    ));
                }
            }
            JsonObject page = obj.has("pagination") ? obj.getAsJsonObject("pagination") : null;
            cursor = (page != null && page.has("cursor") && !page.get("cursor").isJsonNull())
                ? page.get("cursor").getAsString() : null;
        } while (cursor != null && !cursor.isBlank());
        return all;
    }

    public void updateRedemption(String broadcasterId, String rewardId, String redemptionId,
                                 String status) throws IOException {
        String url = BASE + "/channel_points/custom_rewards/redemptions"
            + "?broadcaster_id=" + broadcasterId
            + "&reward_id=" + rewardId
            + "&id=" + redemptionId;
        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        HttpRequest req = builder(url)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        sendWithRefresh(req);
    }

    /** Updates the live channel-point reward's cost. */
    public void updateRewardCost(String broadcasterId, String rewardId, int cost) throws IOException {
        String url = BASE + "/channel_points/custom_rewards"
            + "?broadcaster_id=" + broadcasterId
            + "&id=" + rewardId;
        JsonObject body = new JsonObject();
        body.addProperty("cost", cost);
        HttpRequest req = builder(url)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        JsonHttp.Response r = sendWithRefresh(req);
        if (r.status() < 200 || r.status() >= 300) {
            throw new IOException("updateRewardCost HTTP " + r.status() + ": " + r.body());
        }
    }

    /**
     * Sends a chat message to {@code broadcasterId}'s channel as {@code senderId}. For our use
     * the broadcaster posts as themselves ({@code senderId == broadcasterId}). Requires the user
     * token to carry the {@code user:write:chat} scope.
     */
    public void sendChatMessage(String broadcasterId, String senderId, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("broadcaster_id", broadcasterId);
        body.addProperty("sender_id", senderId);
        body.addProperty("message", message);
        HttpRequest req = builder(BASE + "/chat/messages")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        JsonHttp.Response r = sendWithRefresh(req);
        if (r.status() < 200 || r.status() >= 300) {
            throw new IOException("sendChatMessage HTTP " + r.status() + ": " + r.body());
        }
    }

    /**
     * Best-effort cleanup: deletes every EventSub subscription owned by this client. Stale
     * websocket subscriptions from prior sessions linger in {@code websocket_disconnected} status
     * for ~1h and count toward the "websocket transports limit", causing subscribe to 429. Clearing
     * them before connecting a fresh socket lets the new subscribe succeed. Returns how many we
     * deleted. Run before opening the websocket.
     */
    public int deleteAllEventSubSubscriptions() throws IOException {
        JsonHttp.Response r = sendWithRefresh(builder(BASE + "/eventsub/subscriptions").GET().build());
        if (r.status() < 200 || r.status() >= 300) {
            throw new IOException("listEventSub HTTP " + r.status() + ": " + r.body());
        }
        JsonObject obj = JsonParser.parseString(r.body()).getAsJsonObject();
        JsonArray data = obj.getAsJsonArray("data");
        if (data == null) return 0;
        int deleted = 0;
        for (int i = 0; i < data.size(); i++) {
            String id = data.get(i).getAsJsonObject().get("id").getAsString();
            JsonHttp.Response d = sendWithRefresh(
                builder(BASE + "/eventsub/subscriptions?id=" + id).DELETE().build());
            if (d.status() >= 200 && d.status() < 300) deleted++;
        }
        return deleted;
    }

    public void subscribeEventSub(String sessionId, String broadcasterId, String rewardId) throws IOException {
        JsonObject condition = new JsonObject();
        condition.addProperty("broadcaster_user_id", broadcasterId);
        condition.addProperty("reward_id", rewardId);
        JsonObject transport = new JsonObject();
        transport.addProperty("method", "websocket");
        transport.addProperty("session_id", sessionId);
        JsonObject body = new JsonObject();
        body.addProperty("type", "channel.channel_points_custom_reward_redemption.add");
        body.addProperty("version", "1");
        body.add("condition", condition);
        body.add("transport", transport);

        HttpRequest req = builder(BASE + "/eventsub/subscriptions")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        JsonHttp.Response r = sendWithRefresh(req);
        if (r.status() < 200 || r.status() >= 300) {
            throw new IOException("subscribeEventSub HTTP " + r.status() + ": " + r.body());
        }
    }

    private HttpRequest.Builder builder(String url) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + accessToken.get())
            .header("Client-Id", clientId.get());
    }

    private JsonHttp.Response sendWithRefresh(HttpRequest req) throws IOException {
        JsonHttp.Response r = http.send(req);
        if (r.status() == 401) {
            refreshToken.run();
            HttpRequest retry = HttpRequest.newBuilder(req.uri())
                .method(req.method(), req.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken.get())
                .header("Client-Id", clientId.get())
                .header("Content-Type", req.headers().firstValue("Content-Type").orElse("application/json"))
                .build();
            r = http.send(retry);
        }
        return r;
    }

    private static String firstDataId(String body) {
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        JsonArray data = obj.getAsJsonArray("data");
        if (data == null || data.size() == 0) return "";
        return data.get(0).getAsJsonObject().get("id").getAsString();
    }

    /** Thrown when Helix rejects createReward because the title already exists. */
    public static class DuplicateRewardException extends IOException {
        public DuplicateRewardException(String body) {
            super("Reward with this title already exists: " + body);
        }
    }
}
