package com.chatdwellers.config;

public final class NametagFormatter {

    private NametagFormatter() {}

    public static String format(String format, String twitch, String mc) {
        return format
            .replace("{twitch}", twitch == null ? "" : twitch)
            .replace("{mc}", mc == null ? "" : mc);
    }
}
