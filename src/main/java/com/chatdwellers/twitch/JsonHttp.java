package com.chatdwellers.twitch;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Injectable HTTP boundary for unit tests. */
@FunctionalInterface
public interface JsonHttp {

    record Response(int status, String body) {}

    Response send(HttpRequest request) throws IOException;

    /** Production impl backed by java.net.http.HttpClient. */
    static JsonHttp prod() {
        HttpClient client = HttpClient.newBuilder().build();
        return req -> {
            try {
                HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
                return new Response(r.statusCode(), r.body() == null ? "" : r.body());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("HTTP interrupted", ie);
            }
        };
    }
}
