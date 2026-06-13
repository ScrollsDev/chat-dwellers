package com.chatdwellers.twitch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RewardManagerTest {

    /** Returns canned (status, body) pairs in order; records requests. */
    static class RecordingHttp implements JsonHttp {
        final List<HttpRequest> requests = new ArrayList<>();
        final List<Integer> statuses = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        int idx = 0;

        RecordingHttp(int status, String body) { add(status, body); }
        void add(int status, String body) { statuses.add(status); bodies.add(body); }

        @Override
        public Response send(HttpRequest req) {
            requests.add(req);
            int i = Math.min(idx++, statuses.size() - 1);
            return new Response(statuses.get(i), bodies.get(i));
        }
    }

    @TempDir Path tmp;

    private RewardManager build(JsonHttp http, TokenStore store, List<String> notes) {
        HelixClient helix = new HelixClient(http, () -> "CLIENT_ID", store::accessToken, () -> {});
        return new RewardManager(helix, store, notes::add,
            () -> "Become a Vault Dweller", () -> 500, () -> "Type your MC name");
    }

    private TokenStore store() {
        return TokenStore.atPath(tmp.resolve("secret-" + System.nanoTime() + ".toml"));
    }

    @Test
    void returnsStoredRewardIdWithoutAnyNetworkCall() {
        TokenStore store = store();
        store.setRewardId("rew-stored");
        RecordingHttp http = new RecordingHttp(500, "must not be called");
        RewardManager rm = build(http, store, new ArrayList<>());

        assertEquals("rew-stored", rm.ensureReward());
        assertEquals(0, http.requests.size());
    }

    @Test
    void adoptsExistingManageableRewardInsteadOfCreating() {
        TokenStore store = store();
        store.setBroadcasterId("bid-1");
        RecordingHttp http = new RecordingHttp(200,
            "{\"data\":[{\"id\":\"rew-existing\",\"title\":\"Become a Vault Dweller\"}]}");
        List<String> notes = new ArrayList<>();
        RewardManager rm = build(http, store, notes);

        assertEquals("rew-existing", rm.ensureReward());
        assertEquals("rew-existing", store.rewardId());
        // Only the manageable-rewards lookup happened — no create POST.
        assertEquals(1, http.requests.size());
        assertEquals("GET", http.requests.get(0).method());
    }

    @Test
    void createsRewardWhenNoneExistYet() {
        TokenStore store = store();
        store.setBroadcasterId("bid-1");
        RecordingHttp http = new RecordingHttp(200, "{\"data\":[]}");          // lookup: none
        http.add(200, "{\"data\":[{\"id\":\"rew-new\"}]}");                    // create
        RewardManager rm = build(http, store, new ArrayList<>());

        assertEquals("rew-new", rm.ensureReward());
        assertEquals("rew-new", store.rewardId());
        assertEquals(2, http.requests.size());
        assertEquals("POST", http.requests.get(1).method());
    }

    @Test
    void unmanageableDuplicateTellsUserToDelete() {
        TokenStore store = store();
        store.setBroadcasterId("bid-1");
        RecordingHttp http = new RecordingHttp(200, "{\"data\":[]}");          // no manageable match
        http.add(400, "{\"error\":\"Bad Request\",\"message\":\"CREATE_CUSTOM_REWARD_DUPLICATE_REWARD\"}");
        List<String> notes = new ArrayList<>();
        RewardManager rm = build(http, store, notes);

        assertEquals("", rm.ensureReward());
        assertTrue(store.rewardId().isEmpty());
        assertTrue(notes.stream().anyMatch(n ->
                n.contains("Delete it") && n.contains("not created by ChatDwellers")),
            "expected a delete-and-reconnect note, got: " + notes);
    }
}
