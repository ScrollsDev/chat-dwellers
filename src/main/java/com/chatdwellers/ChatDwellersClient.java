package com.chatdwellers;

import com.chatdwellers.pool.DwellerPool;
import com.chatdwellers.twitch.HelixClient;
import com.chatdwellers.twitch.TokenStore;
import com.chatdwellers.twitch.TwitchBootstrap;

/** Holds the live runtime singletons; initialized during client setup. */
public final class ChatDwellersClient {

    public static DwellerPool pool;

    // Null until TwitchBootstrap.start has run:
    public static volatile TokenStore tokenStore;
    public static volatile HelixClient helixClient;
    public static volatile TwitchBootstrap bootstrap;

    private ChatDwellersClient() {}

    public static void init() {
        pool = new DwellerPool();
    }
}
