package com.chatdwellers.twitch;

import com.chatdwellers.config.Blacklist;
import com.chatdwellers.pool.DwellerPool;
import com.chatdwellers.pool.PendingViewer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RedemptionHandlerTest {

    static class FakeHelix implements RedemptionHandler.Updater {
        final List<String[]> updates = new ArrayList<>();
        @Override
        public void updateRedemption(String redemptionId, String status) {
            updates.add(new String[]{redemptionId, status});
        }
    }

    static class FakeMojang implements RedemptionHandler.NameResolver {
        @Override
        public boolean exists(String mcName) {
            return mcName.equals("jeb_") || mcName.equals("Notch");
        }
    }

    private DwellerPool pool;
    private FakeHelix helix;
    private FakeMojang mojang;
    private List<String> chatNotes;
    private List<String> twitchNotes;
    private Blacklist blacklist;

    @BeforeEach
    void setup() {
        pool = new DwellerPool();
        helix = new FakeHelix();
        mojang = new FakeMojang();
        chatNotes = new ArrayList<>();
        twitchNotes = new ArrayList<>();
        blacklist = Blacklist.of(List.of(), "{name} can't be a Vault Dweller.");
    }

    private RedemptionHandler newHandler() {
        return new RedemptionHandler(pool, helix, mojang, chatNotes::add, twitchNotes::add,
            () -> blacklist, 100);
    }

    private PendingRedemption red(String id, String userId, String userName, String mcInput) {
        return new PendingRedemption(id, userId, userName, mcInput, "2026-06-03T00:00:00Z");
    }

    @Test
    void validNewViewerIsEnqueued() throws IOException {
        newHandler().handle(red("r1", "u1", "Alice", "jeb_"));
        assertEquals(1, pool.size());
        assertEquals(0, helix.updates.size(), "no refund on success");
        assertTrue(chatNotes.isEmpty());
    }

    @Test
    void duplicateViewerIsRefunded() throws IOException {
        RedemptionHandler h = newHandler();
        h.handle(red("r1", "u1", "Alice", "jeb_"));
        h.handle(red("r2", "u1", "Alice", "Notch"));

        assertEquals(1, pool.size(), "duplicate must not enqueue");
        assertEquals(1, helix.updates.size());
        assertEquals("r2", helix.updates.get(0)[0]);
        assertEquals("CANCELED", helix.updates.get(0)[1]);
    }

    @Test
    void poolAtCapacityRefuses() throws IOException {
        RedemptionHandler h = new RedemptionHandler(pool, helix, mojang, chatNotes::add,
            twitchNotes::add, () -> blacklist, 1);
        h.handle(red("r1", "u1", "Alice", "jeb_"));
        h.handle(red("r2", "u2", "Bob", "Notch"));

        assertEquals(1, pool.size());
        assertEquals(1, helix.updates.size());
        assertEquals("r2", helix.updates.get(0)[0]);
        assertEquals("CANCELED", helix.updates.get(0)[1]);
    }

    @Test
    void invalidMcNameRefundsAndChatNotes() throws IOException {
        newHandler().handle(red("r1", "u1", "Alice", "totally_not_a_player"));

        assertEquals(0, pool.size());
        assertEquals(1, helix.updates.size());
        assertEquals("r1", helix.updates.get(0)[0]);
        assertEquals("CANCELED", helix.updates.get(0)[1]);
        assertEquals(1, chatNotes.size());
        assertTrue(chatNotes.get(0).contains("totally_not_a_player"),
            "chat note must mention the offending name: " + chatNotes.get(0));
    }

    @Test
    void blacklistedNameIsRefundedMessagedAndNotEnqueued() throws IOException {
        blacklist = Blacklist.of(
            List.of("technoblade=Technoblade can't possibly be a dweller since he never dies."),
            "{name} can't be a Vault Dweller.");
        newHandler().handle(red("r1", "u1", "Alice", "Technoblade"));

        assertEquals(0, pool.size());
        assertEquals(1, helix.updates.size());
        assertEquals("r1", helix.updates.get(0)[0]);
        assertEquals("CANCELED", helix.updates.get(0)[1]);
        assertEquals(1, twitchNotes.size());
        assertTrue(twitchNotes.get(0).contains("never dies"), twitchNotes.get(0));
        assertTrue(chatNotes.isEmpty(), "blacklist path uses Twitch chat, not the local note");
    }

    @Test
    void blacklistShortCircuitsBeforeMojang() throws IOException {
        blacklist = Blacklist.of(List.of("technoblade=nope"), "{name} can't be a Vault Dweller.");
        // "technoblade" is not a FakeMojang-valid name, but the blacklist check runs first.
        newHandler().handle(red("r1", "u1", "Alice", "technoblade"));

        assertEquals(1, helix.updates.size());
        assertEquals("CANCELED", helix.updates.get(0)[1]);
        assertEquals(1, twitchNotes.size());
        assertTrue(chatNotes.isEmpty(), "Mojang invalid-name path must not run");
    }
}
