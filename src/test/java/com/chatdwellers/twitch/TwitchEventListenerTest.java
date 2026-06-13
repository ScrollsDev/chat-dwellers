package com.chatdwellers.twitch;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the reconnect bookkeeping that previously let a single Twitch-directed
 * reconnect multiply into many connections (and many EventSub subscribe calls → HTTP 429).
 *
 * <p>Drives the listener through a fake {@link TwitchEventListener.Connector} and
 * {@link TwitchEventListener.ReconnectScheduler} so connects/closes are synchronous and the
 * backoff delay never actually elapses.
 */
class TwitchEventListenerTest {

    /** Records every connect and exposes the listener handed to each, plus the URI. */
    static final class FakeConnector implements TwitchEventListener.Connector {
        final List<WebSocket.Listener> listeners = new ArrayList<>();
        final List<URI> uris = new ArrayList<>();
        final List<FakeWebSocket> sockets = new ArrayList<>();

        @Override
        public CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener) {
            uris.add(uri);
            listeners.add(listener);
            FakeWebSocket ws = new FakeWebSocket();
            sockets.add(ws);
            return CompletableFuture.completedFuture(ws);
        }

        int connectCount() { return listeners.size(); }
    }

    /** Records scheduled reconnect tasks without waiting; tests run them on demand. */
    static final class CapturingScheduler implements TwitchEventListener.ReconnectScheduler {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void schedule(Runnable task, long delaySeconds) { tasks.add(task); }
        int count() { return tasks.size(); }
        void runAll() { List<Runnable> copy = new ArrayList<>(tasks); tasks.clear(); copy.forEach(Runnable::run); }
    }

    private static EventSubRouter noopRouter() {
        return new EventSubRouter(s -> {}, r -> {}, u -> {}, () -> {}, s -> {});
    }

    @Test
    void reconnectHandoffDoesNotSpawnDuplicateConnections() {
        FakeConnector conn = new FakeConnector();
        CapturingScheduler sched = new CapturingScheduler();
        TwitchEventListener l = new TwitchEventListener(noopRouter(), conn, sched);

        l.start();                                   // connect #1 (gen 1)
        assertEquals(1, conn.connectCount());

        l.reconnectTo("wss://new-edge/");            // supersede gen 1, connect #2 (gen 2)
        assertEquals(2, conn.connectCount());
        assertEquals("wss://new-edge/", conn.uris.get(1).toString());

        // The old socket (gen 1) now reports the close we initiated during the hand-off.
        // Before the fix this scheduled its own reconnect, doubling connections every time.
        conn.listeners.get(0).onClose(conn.sockets.get(0), WebSocket.NORMAL_CLOSURE, "reconnect");

        assertEquals(0, sched.count(), "superseded socket must not schedule a reconnect");
        assertEquals(2, conn.connectCount(), "no extra connection should be opened");
    }

    @Test
    void reconnectWelcomeSkipsResubscribeButFreshWelcomeDoesNot() {
        FakeConnector conn = new FakeConnector();
        TwitchEventListener l = new TwitchEventListener(noopRouter(), conn, new CapturingScheduler());

        l.start();
        assertFalse(l.consumeMigration(), "fresh connection welcome must subscribe");

        l.reconnectTo("wss://new-edge/");
        assertTrue(l.consumeMigration(), "reconnect-migration welcome must skip subscribe");
        assertFalse(l.consumeMigration(), "migration flag is one-shot");
    }

    @Test
    void genuineDropSchedulesExactlyOneReconnectToAFreshSession() {
        FakeConnector conn = new FakeConnector();
        CapturingScheduler sched = new CapturingScheduler();
        TwitchEventListener l = new TwitchEventListener(noopRouter(), conn, sched);

        l.start();                                   // connect #1
        // Twitch drops the active socket abnormally.
        conn.listeners.get(0).onClose(conn.sockets.get(0), 1006, "abnormal");
        assertEquals(1, sched.count(), "active socket drop should schedule one reconnect");

        sched.runAll();                              // connect #2 → DEFAULT_URL, fresh session
        assertEquals(2, conn.connectCount());
        assertFalse(l.consumeMigration(), "a reconnect after a real drop is a fresh session — must subscribe");
    }

    @Test
    void stopPreventsReconnect() {
        FakeConnector conn = new FakeConnector();
        CapturingScheduler sched = new CapturingScheduler();
        TwitchEventListener l = new TwitchEventListener(noopRouter(), conn, sched);

        l.start();
        l.stop();
        conn.listeners.get(0).onClose(conn.sockets.get(0), WebSocket.NORMAL_CLOSURE, "shutdown");
        assertEquals(0, sched.count(), "stopped listener must not reconnect");
    }

    /** Minimal WebSocket whose only meaningful behavior is being a no-op sink. */
    static final class FakeWebSocket implements WebSocket {
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) { return done(); }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return done(); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return done(); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return done(); }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { return done(); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { }
        private CompletableFuture<WebSocket> done() { return CompletableFuture.completedFuture(this); }
    }
}
