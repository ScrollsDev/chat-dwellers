package com.chatdwellers.twitch;

import com.chatdwellers.ChatDwellers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Long-lived WebSocket client to Twitch EventSub. Owns reconnect + backoff;
 *  delegates message parsing to EventSubRouter.
 *
 *  <p>Every connection attempt gets a monotonically increasing <em>generation</em>.
 *  Only the listener whose generation is still the latest is allowed to trigger a
 *  reconnect. This makes superseded sockets (closed during a hand-off, or beaten by
 *  a newer connect) go quiet instead of each rescheduling their own reconnect — which
 *  previously multiplied connections and hammered the EventSub subscribe endpoint. */
public final class TwitchEventListener {

    private static final String DEFAULT_URL = "wss://eventsub.wss.twitch.tv/ws";
    private static final long[] BACKOFF_SEC = {1, 2, 4, 8, 16, 30, 60};

    private final EventSubRouter router;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chatdwellers-eventsub");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final AtomicReference<WebSocket> current = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong(0);

    /** Opens a socket. Seam so tests can supply a fake instead of a live network connection. */
    @FunctionalInterface
    interface Connector {
        CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener);
    }

    /** Schedules a deferred reconnect. Seam so tests can capture the task instead of waiting
     *  out the real backoff delay. */
    @FunctionalInterface
    interface ReconnectScheduler {
        void schedule(Runnable task, long delaySeconds);
    }

    private final Connector connector;
    private final ReconnectScheduler reconnectScheduler;

    private volatile int backoffIdx = 0;
    private volatile String currentUrl = DEFAULT_URL;
    private volatile boolean closed = false;
    /** True while the next welcome belongs to a Twitch-directed reconnect (subscriptions
     *  are carried over, so we must NOT re-subscribe). Cleared once consumed, and also
     *  whenever we fall back to a fresh DEFAULT_URL session via {@link #scheduleReconnect()}. */
    private volatile boolean migrating = false;

    public TwitchEventListener(EventSubRouter router) {
        this(router, null, null);
    }

    /** Test seam: pass a fake {@code connector}/{@code reconnectScheduler}; null falls back to the
     *  live HttpClient socket and the real backoff scheduler. */
    TwitchEventListener(EventSubRouter router, Connector connector, ReconnectScheduler reconnectScheduler) {
        this.router = router;
        this.connector = connector != null ? connector
            : (uri, l) -> httpClient.newWebSocketBuilder().buildAsync(uri, l);
        this.reconnectScheduler = reconnectScheduler != null ? reconnectScheduler
            : (task, delay) -> scheduler.schedule(task, delay, TimeUnit.SECONDS);
    }

    public void start() {
        closed = false;
        migrating = false;
        currentUrl = DEFAULT_URL;
        backoffIdx = 0;
        connect(currentUrl);
    }

    /** Closes the socket and stops accepting reconnects, but leaves the scheduler
     *  alive so start() can resume the listener later (used by /chatdwellers off|on). */
    public void stop() {
        closed = true;
        WebSocket ws = current.get();
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
    }

    /** Called by the bootstrap once an EventSub subscribe actually succeeds; resets the backoff so
     *  a later genuine drop reconnects quickly. Until a subscribe succeeds we keep growing the
     *  backoff, which is what breaks the open→unused-close→reconnect storm when Twitch keeps
     *  returning 429 (websocket transports limit exceeded). */
    public void onSubscribed() {
        backoffIdx = 0;
    }

    /** Returns true exactly once after a reconnect hand-off, telling the welcome handler
     *  to skip the (redundant, rate-limit-burning) re-subscribe. */
    public boolean consumeMigration() {
        boolean m = migrating;
        migrating = false;
        return m;
    }

    private void connect(String url) {
        connect(url, generation.incrementAndGet());
    }

    private void connect(String url, long gen) {
        if (closed) return;
        currentUrl = url;
        ChatDwellers.LOGGER.debug("[ChatDwellers] EventSub connecting to {} (gen {})", url, gen);
        connector.connect(URI.create(url), new Listener(gen))
            .whenComplete((ws, err) -> {
                if (err != null) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] EventSub connect failed: {}", err.toString());
                    if (gen == generation.get()) scheduleReconnect();
                } else if (gen != generation.get()) {
                    // A newer connect superseded us before we finished opening; close this orphan.
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "superseded");
                } else {
                    current.set(ws);
                    // NOTE: do NOT reset backoff here. The socket opening is not success — the
                    // *subscribe* afterward can still 429 ("websocket transports limit exceeded"),
                    // after which Twitch closes the unused socket and we reconnect. Resetting on
                    // open made every reconnect immediate, producing a tight open→close→reconnect
                    // storm. Backoff is reset only once a subscribe actually succeeds (onSubscribed).
                    ChatDwellers.LOGGER.debug("[ChatDwellers] EventSub WebSocket open (gen {})", gen);
                }
            });
    }

    private void scheduleReconnect() {
        if (closed) return;
        // Any auto-reconnect lands on a brand-new DEFAULT_URL session, which DOES need a
        // fresh subscribe — so clear any pending migration skip.
        migrating = false;
        long delay = BACKOFF_SEC[Math.min(backoffIdx, BACKOFF_SEC.length - 1)];
        backoffIdx++;
        reconnectScheduler.schedule(() -> connect(DEFAULT_URL), delay);
    }

    /** Hand-off from EventSubRouter via {@code onReconnect}: supersede the current socket,
     *  close it, and open the new one. Bumping the generation <em>before</em> closing the old
     *  socket ensures its {@code onClose} is recognized as stale and does not reconnect. */
    public void reconnectTo(String url) {
        migrating = true;
        long gen = generation.incrementAndGet();
        WebSocket old = current.get();
        if (old != null) old.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
        connect(url, gen);
    }

    private final class Listener implements WebSocket.Listener {
        private final long gen;
        private final StringBuilder partial = new StringBuilder();

        Listener(long gen) {
            this.gen = gen;
        }

        /** True only for the socket that is still the active generation. */
        private boolean isCurrent() {
            return !closed && gen == generation.get();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String msg = partial.toString();
                partial.setLength(0);
                ChatDwellers.LOGGER.debug("[ChatDwellers] EventSub <- {}",
                    msg.length() > 300 ? msg.substring(0, 300) + "..." : msg);
                try {
                    router.route(msg);
                } catch (Exception e) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] router threw for {}: {}", msg, e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ChatDwellers.LOGGER.warn("[ChatDwellers] EventSub WS error (gen {}): {}", gen, error.toString());
            if (isCurrent()) scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ChatDwellers.LOGGER.info("[ChatDwellers] EventSub WS closed (gen {}): {} {}", gen, statusCode, reason);
            if (isCurrent()) scheduleReconnect();
            return null;
        }
    }
}
