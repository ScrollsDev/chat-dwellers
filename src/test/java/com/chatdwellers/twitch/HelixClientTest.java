package com.chatdwellers.twitch;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class HelixClientTest {

    /** Records requests; returns canned (status, body) pairs in order. */
    static class RecordingHttp implements JsonHttp {
        final List<HttpRequest> requests = new ArrayList<>();
        final List<int[]> statuses = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        int idx = 0;

        RecordingHttp(int status, String body) { add(status, body); }
        void add(int status, String body) {
            statuses.add(new int[]{status});
            bodies.add(body);
        }

        @Override
        public Response send(HttpRequest req) throws IOException {
            requests.add(req);
            int i = Math.min(idx++, statuses.size() - 1);
            return new Response(statuses.get(i)[0], bodies.get(i));
        }
    }

    private static HelixClient build(JsonHttp http) {
        Supplier<String> token = () -> "TOKEN";
        Runnable refresh = () -> {};
        return new HelixClient(http, () -> "CLIENT_ID", token, refresh);
    }

    @Test
    void getBroadcasterIdReadsDataZeroId() throws IOException {
        RecordingHttp http = new RecordingHttp(200,
            "{\"data\":[{\"id\":\"123456\",\"login\":\"streamer\"}]}");
        HelixClient h = build(http);
        assertEquals("123456", h.getBroadcasterId());

        HttpRequest req = http.requests.get(0);
        assertEquals("GET", req.method());
        assertEquals(URI.create("https://api.twitch.tv/helix/users"), req.uri());
        assertEquals("Bearer TOKEN", req.headers().firstValue("Authorization").orElse(""));
        assertEquals("CLIENT_ID", req.headers().firstValue("Client-Id").orElse(""));
    }

    @Test
    void createRewardPostsAndReturnsId() throws IOException {
        RecordingHttp http = new RecordingHttp(200,
            "{\"data\":[{\"id\":\"reward-abc\"}]}");
        HelixClient h = build(http);
        String id = h.createReward("bid-1", "Become a Vault Dweller", 500, "Type your MC name");
        assertEquals("reward-abc", id);

        HttpRequest req = http.requests.get(0);
        assertEquals("POST", req.method());
        assertEquals(
            URI.create("https://api.twitch.tv/helix/channel_points/custom_rewards?broadcaster_id=bid-1"),
            req.uri());
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void listPendingPagesUntilEmptyCursor() throws IOException {
        RecordingHttp http = new RecordingHttp(200,
            "{\"data\":[{\"id\":\"r1\",\"user_id\":\"u1\",\"user_name\":\"Alice\",\"user_input\":\"alice_mc\",\"redeemed_at\":\"2026-06-03T00:00:00Z\"}]," +
            "\"pagination\":{\"cursor\":\"CUR\"}}");
        http.add(200,
            "{\"data\":[{\"id\":\"r2\",\"user_id\":\"u2\",\"user_name\":\"Bob\",\"user_input\":\"bob_mc\",\"redeemed_at\":\"2026-06-03T00:00:01Z\"}]," +
            "\"pagination\":{}}");
        HelixClient h = build(http);

        List<PendingRedemption> all = h.listPending("bid-1", "rew-1");
        assertEquals(2, all.size());
        assertEquals("r1", all.get(0).id());
        assertEquals("Alice", all.get(0).userName());
        assertEquals("alice_mc", all.get(0).userInput());
        assertEquals("r2", all.get(1).id());

        HttpRequest second = http.requests.get(1);
        assertTrue(second.uri().toString().contains("after=CUR"),
            "second request must page with cursor: " + second.uri());
    }

    @Test
    void updateRedemptionPatchesWithStatus() throws IOException {
        RecordingHttp http = new RecordingHttp(200, "{\"data\":[]}");
        HelixClient h = build(http);
        h.updateRedemption("bid-1", "rew-1", "redemption-1", "FULFILLED");

        HttpRequest req = http.requests.get(0);
        assertEquals("PATCH", req.method());
        String uri = req.uri().toString();
        assertTrue(uri.contains("broadcaster_id=bid-1"), uri);
        assertTrue(uri.contains("reward_id=rew-1"), uri);
        assertTrue(uri.contains("id=redemption-1"), uri);
    }

    @Test
    void updateRewardCostPatchesCostWithIds() throws IOException {
        RecordingHttp http = new RecordingHttp(200, "{\"data\":[{\"id\":\"rew-1\",\"cost\":750}]}");
        HelixClient h = build(http);
        h.updateRewardCost("bid-1", "rew-1", 750);

        HttpRequest req = http.requests.get(0);
        assertEquals("PATCH", req.method());
        String uri = req.uri().toString();
        assertTrue(uri.contains("broadcaster_id=bid-1"), uri);
        assertTrue(uri.contains("id=rew-1"), uri);
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void subscribeEventSubPostsCorrectShape() throws IOException {
        RecordingHttp http = new RecordingHttp(202,
            "{\"data\":[{\"id\":\"sub-1\",\"status\":\"enabled\"}]}");
        HelixClient h = build(http);
        h.subscribeEventSub("SESSION-XYZ", "bid-1", "rew-1");

        HttpRequest req = http.requests.get(0);
        assertEquals("POST", req.method());
        assertEquals(
            URI.create("https://api.twitch.tv/helix/eventsub/subscriptions"),
            req.uri());
    }

    @Test
    void findManageableRewardIdMatchesTitleCaseInsensitively() throws IOException {
        RecordingHttp http = new RecordingHttp(200,
            "{\"data\":[{\"id\":\"rew-1\",\"title\":\"Some Other Reward\"}," +
            "{\"id\":\"rew-2\",\"title\":\"Become a Vault Dweller\"}]}");
        HelixClient h = build(http);

        assertEquals("rew-2", h.findManageableRewardId("bid-1", "become a vault dweller"));

        HttpRequest req = http.requests.get(0);
        assertEquals("GET", req.method());
        String uri = req.uri().toString();
        assertTrue(uri.contains("broadcaster_id=bid-1"), uri);
        assertTrue(uri.contains("only_manageable_rewards=true"), uri);
    }

    @Test
    void findManageableRewardIdReturnsEmptyWhenNoTitleMatch() throws IOException {
        RecordingHttp http = new RecordingHttp(200,
            "{\"data\":[{\"id\":\"rew-1\",\"title\":\"Some Other Reward\"}]}");
        HelixClient h = build(http);
        assertEquals("", h.findManageableRewardId("bid-1", "Become a Vault Dweller"));
    }

    @Test
    void sendChatMessagePostsToChatMessagesEndpoint() throws IOException {
        RecordingHttp http = new RecordingHttp(200,
            "{\"data\":[{\"message_id\":\"m1\",\"is_sent\":true}]}");
        HelixClient h = build(http);
        h.sendChatMessage("bid-1", "bid-1", "hello chat");

        HttpRequest req = http.requests.get(0);
        assertEquals("POST", req.method());
        assertEquals(URI.create("https://api.twitch.tv/helix/chat/messages"), req.uri());
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""));
        assertEquals("Bearer TOKEN", req.headers().firstValue("Authorization").orElse(""));
    }

    @Test
    void on401RefreshesAndRetriesOnce() throws IOException {
        RecordingHttp http = new RecordingHttp(401, "{\"message\":\"unauthorized\"}");
        http.add(200, "{\"data\":[{\"id\":\"123\",\"login\":\"x\"}]}");

        final int[] refreshCount = {0};
        HelixClient h = new HelixClient(
            http, () -> "CLIENT_ID", () -> "TOKEN",
            () -> refreshCount[0]++);

        assertEquals("123", h.getBroadcasterId());
        assertEquals(1, refreshCount[0]);
        assertEquals(2, http.requests.size());
    }
}
