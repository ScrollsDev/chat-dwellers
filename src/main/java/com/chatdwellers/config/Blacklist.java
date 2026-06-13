package com.chatdwellers.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable view of the redemption blacklist: maps a lower-cased Minecraft name to the message
 * posted when that name is rejected. Built from config entries of the form
 * {@code mcname=custom message}; an entry with no '=' uses the generic template ({@code {name}}
 * substituted). Matching is case-insensitive.
 */
public final class Blacklist {

    private final Map<String, String> byName; // lower-cased name -> resolved message

    private Blacklist(Map<String, String> byName) {
        this.byName = byName;
    }

    public static Blacklist of(List<? extends String> entries, String genericTemplate) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String raw : entries) {
            if (raw == null) continue;
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            int eq = entry.indexOf('=');
            String name = (eq >= 0 ? entry.substring(0, eq) : entry).trim();
            if (name.isEmpty()) continue;
            String custom = eq >= 0 ? entry.substring(eq + 1).trim() : "";
            String message = custom.isEmpty() ? genericTemplate.replace("{name}", name) : custom;
            map.put(name.toLowerCase(Locale.ROOT), message);
        }
        return new Blacklist(map);
    }

    /** The rejection message for {@code mcName}, or empty if it is not blacklisted. */
    public Optional<String> messageFor(String mcName) {
        if (mcName == null) return Optional.empty();
        return Optional.ofNullable(byName.get(mcName.toLowerCase(Locale.ROOT)));
    }
}
