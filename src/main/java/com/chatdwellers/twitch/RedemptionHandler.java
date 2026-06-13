package com.chatdwellers.twitch;

import com.chatdwellers.config.Blacklist;
import com.chatdwellers.pool.DwellerPool;
import com.chatdwellers.pool.PendingViewer;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Pure dispatch: validates an incoming redemption and either enqueues a viewer, rejects it
 *  via the blacklist (refund + Twitch-chat message), or refunds (CANCELED) duplicates / full /
 *  invalid names. No Minecraft/Forge types, no network. */
public final class RedemptionHandler {

    /** Asks Helix to mark a redemption as FULFILLED or CANCELED. */
    public interface Updater {
        void updateRedemption(String redemptionId, String status) throws IOException;
    }

    /** Returns true if the MC name resolves on Mojang (i.e. is a real account). */
    public interface NameResolver {
        boolean exists(String mcName) throws IOException;
    }

    private final DwellerPool pool;
    private final Updater helix;
    private final NameResolver mojang;
    private final Consumer<String> chatNote;
    private final Consumer<String> twitchChat;
    private final Supplier<Blacklist> blacklist;
    private final int maxPoolSize;

    public RedemptionHandler(DwellerPool pool, Updater helix, NameResolver mojang,
                             Consumer<String> chatNote, Consumer<String> twitchChat,
                             Supplier<Blacklist> blacklist, int maxPoolSize) {
        this.pool = pool;
        this.helix = helix;
        this.mojang = mojang;
        this.chatNote = chatNote;
        this.twitchChat = twitchChat;
        this.blacklist = blacklist;
        this.maxPoolSize = maxPoolSize;
    }

    public void handle(PendingRedemption r) throws IOException {
        // Blacklist wins over everything: refund the points and post the custom Twitch-chat line.
        Optional<String> blocked = blacklist.get().messageFor(r.userInput());
        if (blocked.isPresent()) {
            helix.updateRedemption(r.id(), "CANCELED");
            twitchChat.accept(blocked.get());
            return;
        }

        PendingViewer attempt = new PendingViewer(r.userId(), r.userName(), r.userInput(), r.id());
        DwellerPool.EnqueueResult result = pool.enqueue(attempt, maxPoolSize);
        switch (result) {
            case ADDED -> {
                if (!mojang.exists(r.userInput())) {
                    // Walk-and-restore: pool has no removeById; remove the bad one by purging
                    // and re-enqueueing the rest. Invalid names are rare so the O(n) cost is fine.
                    var remaining = new java.util.ArrayList<PendingViewer>();
                    for (PendingViewer v : pool.purge()) {
                        if (!v.twitchId().equals(attempt.twitchId())) remaining.add(v);
                    }
                    for (PendingViewer v : remaining) pool.enqueue(v, maxPoolSize);
                    helix.updateRedemption(r.id(), "CANCELED");
                    chatNote.accept(
                        r.userName() + " redeemed but '" + r.userInput() + "' is not a Minecraft account.");
                }
            }
            case DUPLICATE, FULL -> helix.updateRedemption(r.id(), "CANCELED");
        }
    }
}
