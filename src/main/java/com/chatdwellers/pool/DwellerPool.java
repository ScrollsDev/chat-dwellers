package com.chatdwellers.pool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Whole-vault viewer rotation. Every spawning dweller pulls the next viewer via
 * {@link #nextForSpawn()}, which prefers viewers not yet shown this vault, then cycles the
 * already-shown ones (so with fewer viewers than dwellers, viewers repeat and no dweller is
 * left default-skinned while anyone is queued). At vault exit, {@link #retainUnshown()} drops
 * the shown viewers (to be fulfilled on Twitch) and keeps the unshown ones for the next vault.
 */
public class DwellerPool {

    public enum EnqueueResult { ADDED, DUPLICATE, FULL }

    private static final class Entry {
        final PendingViewer viewer;
        Entry(PendingViewer viewer) { this.viewer = viewer; }
    }

    private final Deque<Entry> unshown = new ArrayDeque<>();
    private final Deque<Entry> shown = new ArrayDeque<>();
    private final Set<String> queuedTwitchIds = new HashSet<>();

    public synchronized EnqueueResult enqueue(PendingViewer viewer, int maxSize) {
        if (queuedTwitchIds.contains(viewer.twitchId())) {
            return EnqueueResult.DUPLICATE;
        }
        if (size() >= maxSize) {
            return EnqueueResult.FULL;
        }
        unshown.addLast(new Entry(viewer));
        queuedTwitchIds.add(viewer.twitchId());
        return EnqueueResult.ADDED;
    }

    /**
     * Viewer to skin onto a spawning dweller, or empty if the queue is empty. Unshown viewers
     * go first (FIFO); once all have been shown, the shown segment rotates round-robin.
     */
    public synchronized Optional<PendingViewer> nextForSpawn() {
        Entry e = unshown.pollFirst();
        if (e == null) {
            e = shown.pollFirst();
        }
        if (e == null) {
            return Optional.empty();
        }
        shown.addLast(e);
        return Optional.of(e.viewer);
    }

    /** Drops viewers shown this vault (returned for fulfillment); keeps unshown ones queued. */
    public synchronized List<PendingViewer> retainUnshown() {
        List<PendingViewer> dropped = new ArrayList<>(shown.size());
        for (Entry e : shown) {
            dropped.add(e.viewer);
            queuedTwitchIds.remove(e.viewer.twitchId());
        }
        shown.clear();
        return dropped;
    }

    public synchronized List<PendingViewer> purge() {
        List<PendingViewer> all = snapshot();
        unshown.clear();
        shown.clear();
        queuedTwitchIds.clear();
        return all;
    }

    public synchronized int size() {
        return unshown.size() + shown.size();
    }

    /** Unshown first (in queue order), then the shown rotation. */
    public synchronized List<PendingViewer> snapshot() {
        List<PendingViewer> out = new ArrayList<>(size());
        for (Entry e : unshown) out.add(e.viewer);
        for (Entry e : shown) out.add(e.viewer);
        return out;
    }
}
