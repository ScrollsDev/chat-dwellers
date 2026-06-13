package com.chatdwellers.twitch;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EventSubRouterTest {

    static class Calls {
        final List<String> welcomes = new ArrayList<>();
        final List<PendingRedemption> redemptions = new ArrayList<>();
        final List<String> reconnects = new ArrayList<>();
        int keepalives = 0;
        final List<String> revocations = new ArrayList<>();
    }

    private static EventSubRouter router(Calls c) {
        return new EventSubRouter(
            c.welcomes::add,
            c.redemptions::add,
            c.reconnects::add,
            () -> c.keepalives++,
            c.revocations::add);
    }

    @Test
    void welcomeExtractsSessionId() {
        Calls c = new Calls();
        router(c).route(
            "{\"metadata\":{\"message_type\":\"session_welcome\"}," +
            "\"payload\":{\"session\":{\"id\":\"SESS-1\",\"status\":\"connected\"}}}");
        assertEquals(List.of("SESS-1"), c.welcomes);
    }

    @Test
    void notificationParsesRedemption() {
        Calls c = new Calls();
        router(c).route(
            "{\"metadata\":{\"message_type\":\"notification\"}," +
            "\"payload\":{\"event\":{\"id\":\"r1\",\"user_id\":\"u1\",\"user_name\":\"Alice\"," +
            "\"user_input\":\"jeb_\",\"redeemed_at\":\"2026-06-03T00:00:00Z\"}}}");
        assertEquals(1, c.redemptions.size());
        PendingRedemption r = c.redemptions.get(0);
        assertEquals("r1", r.id());
        assertEquals("Alice", r.userName());
        assertEquals("jeb_", r.userInput());
    }

    @Test
    void reconnectExtractsUrl() {
        Calls c = new Calls();
        router(c).route(
            "{\"metadata\":{\"message_type\":\"session_reconnect\"}," +
            "\"payload\":{\"session\":{\"id\":\"SESS-2\",\"reconnect_url\":\"wss://x/\"}}}");
        assertEquals(List.of("wss://x/"), c.reconnects);
    }

    @Test
    void keepaliveIncrementsCount() {
        Calls c = new Calls();
        router(c).route("{\"metadata\":{\"message_type\":\"session_keepalive\"}}");
        assertEquals(1, c.keepalives);
    }

    @Test
    void revocationReportsReason() {
        Calls c = new Calls();
        router(c).route(
            "{\"metadata\":{\"message_type\":\"revocation\"}," +
            "\"payload\":{\"subscription\":{\"status\":\"authorization_revoked\"}}}");
        assertEquals(List.of("authorization_revoked"), c.revocations);
    }

    @Test
    void unknownMessageTypeIsIgnored() {
        Calls c = new Calls();
        router(c).route("{\"metadata\":{\"message_type\":\"unknown_future_thing\"}}");
        assertTrue(c.welcomes.isEmpty());
        assertTrue(c.redemptions.isEmpty());
        assertEquals(0, c.keepalives);
    }
}
