package com.chatdwellers.twitch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Resolves a Minecraft username to its UUID via Mojang. Used only to validate
 *  Twitch redemption inputs before enqueueing — skin loading is Vault's job. */
public class MojangApi {

    /** Returns the response body, or null for a 204/404 (treated as "not found"). */
    public interface Http {
        String getOrNull(String url) throws IOException;
    }

    private static final String NAME_URL = "https://api.mojang.com/users/profiles/minecraft/";

    private final Http http;

    public MojangApi(Http http) {
        this.http = http;
    }

    /** Production constructor: uses a real HttpURLConnection. */
    public MojangApi() {
        this(MojangApi::httpGetOrNull);
    }

    public Optional<String> resolveUuid(String mcName) throws IOException {
        String body = http.getOrNull(NAME_URL + mcName);
        if (body == null || body.isBlank()) return Optional.empty();
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        if (!obj.has("id")) return Optional.empty();
        return Optional.of(obj.get("id").getAsString());
    }

    private static String httpGetOrNull(String urlStr) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        int code = c.getResponseCode();
        if (code == 204 || code == 404) return null;
        if (code != 200) throw new IOException("HTTP " + code + " for " + urlStr);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
