package com.chatdwellers.twitch;

import com.chatdwellers.ChatDwellers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Twitch Device Code OAuth client. Single-instance; calls into TokenStore. */
public final class TwitchAuthManager {

    private static final String SCOPES = "channel:manage:redemptions user:write:chat";
    private static final String DEVICE_URL = "https://id.twitch.tv/oauth2/device";
    private static final String TOKEN_URL  = "https://id.twitch.tv/oauth2/token";
    private static final long REFRESH_LEAD_TIME_SEC = 60;

    /** Surfaces the device-code data so the client can show an activation popup. */
    public interface ActivationPrompt {
        void show(String verificationUri, String verificationUriComplete, String userCode);
    }

    private final JsonHttp http;
    private final TokenStore store;
    private final Supplier<String> clientId;
    private final Consumer<String> chatNote;
    private final ActivationPrompt prompt;

    public TwitchAuthManager(JsonHttp http, TokenStore store, Supplier<String> clientId,
                             Consumer<String> chatNote, ActivationPrompt prompt) {
        this.http = http;
        this.store = store;
        this.clientId = clientId;
        this.chatNote = chatNote;
        this.prompt = prompt;
    }

    /** Returns true if we currently have a usable (non-expired) access token. */
    public boolean hasValidToken() {
        return !store.accessToken().isEmpty()
            && store.tokenExpiresAtEpochSeconds() > Instant.now().getEpochSecond() + REFRESH_LEAD_TIME_SEC;
    }

    /** The scopes this build requires; used by the bootstrap to decide whether to re-prompt. */
    public static String requiredScopes() { return SCOPES; }

    /** True if the stored token was granted every space-separated scope in {@code required}. */
    public boolean hasGrantedScopes(String required) {
        java.util.Set<String> have = new java.util.HashSet<>(
            java.util.Arrays.asList(store.scopes().trim().split("\\s+")));
        for (String s : required.trim().split("\\s+")) {
            if (!s.isEmpty() && !have.contains(s)) return false;
        }
        return true;
    }

    /** Begins the device-code dance; returns a future that completes when granted (or fails). */
    public CompletableFuture<Void> startDeviceCode() {
        return CompletableFuture.runAsync(() -> {
            try {
                JsonObject dev = postForm(DEVICE_URL,
                    "client_id=" + clientId.get()
                    + "&scopes=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8));
                String deviceCode = dev.get("device_code").getAsString();
                String userCode   = dev.get("user_code").getAsString();
                String uri        = dev.has("verification_uri")
                    ? dev.get("verification_uri").getAsString()
                    : "https://twitch.tv/activate";
                String uriComplete = dev.has("verification_uri_complete")
                    ? dev.get("verification_uri_complete").getAsString()
                    : null;
                int interval      = dev.has("interval") ? dev.get("interval").getAsInt() : 5;
                int expiresIn     = dev.has("expires_in") ? dev.get("expires_in").getAsInt() : 1800;

                // Backstop for headless/no-screen contexts; the popup is the primary UX.
                ChatDwellers.LOGGER.info("[ChatDwellers] Authorize Twitch: visit {} and enter code {}",
                    uri, userCode);
                prompt.show(uri, uriComplete, userCode);

                long deadline = Instant.now().getEpochSecond() + expiresIn;
                while (Instant.now().getEpochSecond() < deadline) {
                    Thread.sleep(interval * 1000L);
                    JsonHttp.Response r = http.send(formRequest(TOKEN_URL,
                        "client_id=" + clientId.get()
                        + "&scopes=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8)
                        + "&device_code=" + deviceCode
                        + "&grant_type=urn:ietf:params:oauth:grant-type:device_code"));
                    if (r.status() == 200) {
                        persistTokens(JsonParser.parseString(r.body()).getAsJsonObject());
                        chatNote.accept("[ChatDwellers] Twitch authorization successful.");
                        return;
                    }
                    String body = r.body() == null ? "" : r.body();
                    if (body.contains("authorization_pending")) continue;
                    if (body.contains("slow_down")) { interval++; continue; }
                    if (body.contains("expired_token")) {
                        chatNote.accept("[ChatDwellers] Device code expired. /chatdwellers reconnect to retry.");
                        return;
                    }
                    if (body.contains("access_denied")) {
                        chatNote.accept("[ChatDwellers] Authorization denied.");
                        return;
                    }
                    ChatDwellers.LOGGER.warn("[ChatDwellers] unexpected token poll status {} body {}", r.status(), body);
                }
                chatNote.accept("[ChatDwellers] Authorization timed out. /chatdwellers reconnect to retry.");
            } catch (Exception e) {
                ChatDwellers.LOGGER.error("[ChatDwellers] device-code flow failed", e);
                chatNote.accept("[ChatDwellers] Authorization failed: " + e.getMessage());
            }
        });
    }

    /** Synchronous refresh; called from HelixClient on 401. */
    public synchronized void refresh() {
        if (store.refreshToken().isEmpty()) return;
        try {
            JsonHttp.Response r = http.send(formRequest(TOKEN_URL,
                "client_id=" + clientId.get()
                + "&grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(store.refreshToken(), StandardCharsets.UTF_8)));
            if (r.status() == 200) {
                persistTokens(JsonParser.parseString(r.body()).getAsJsonObject());
            } else {
                ChatDwellers.LOGGER.warn("[ChatDwellers] refresh failed ({}): {}", r.status(), r.body());
                store.setAccessToken("");
                store.setRefreshToken("");
                store.setTokenExpiresAtEpochSeconds(0);
                chatNote.accept("[ChatDwellers] Twitch session expired. /chatdwellers reconnect to re-authorize.");
            }
        } catch (Exception e) {
            ChatDwellers.LOGGER.error("[ChatDwellers] refresh threw", e);
        }
    }

    private void persistTokens(JsonObject body) {
        store.setAccessToken(body.get("access_token").getAsString());
        if (body.has("refresh_token")) {
            store.setRefreshToken(body.get("refresh_token").getAsString());
        }
        long expiresIn = body.has("expires_in") ? body.get("expires_in").getAsLong() : 3600L;
        store.setTokenExpiresAtEpochSeconds(Instant.now().getEpochSecond() + expiresIn);
        store.setScopes(SCOPES);
    }

    private HttpRequest formRequest(String url, String form) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    }

    private JsonObject postForm(String url, String form) throws IOException {
        JsonHttp.Response r = http.send(formRequest(url, form));
        if (r.status() < 200 || r.status() >= 300) {
            throw new IOException("HTTP " + r.status() + " from " + url + ": " + r.body());
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }
}
