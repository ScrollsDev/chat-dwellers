package com.chatdwellers.pool;

import com.chatdwellers.pool.DwellerPool.EnqueueResult;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DwellerPoolTest {

    private PendingViewer viewer(String id, String name) {
        return new PendingViewer(id, name, name + "_mc", "red-" + id);
    }

    private List<String> assign(DwellerPool pool, int dwellers) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < dwellers; i++) {
            out.add(pool.nextForSpawn().map(PendingViewer::twitchName).orElse("DEFAULT"));
        }
        return out;
    }

    @Test
    void enqueueAddsAndReportsSize() {
        DwellerPool pool = new DwellerPool();
        assertEquals(EnqueueResult.ADDED, pool.enqueue(viewer("a", "Ann"), 100));
        assertEquals(1, pool.size());
    }

    @Test
    void duplicateTwitchIdIsRejected() {
        DwellerPool pool = new DwellerPool();
        pool.enqueue(viewer("a", "Ann"), 100);
        assertEquals(EnqueueResult.DUPLICATE, pool.enqueue(viewer("a", "Ann2"), 100));
        assertEquals(1, pool.size());
    }

    @Test
    void poolAtCapacityRejectsWithFull() {
        DwellerPool pool = new DwellerPool();
        pool.enqueue(viewer("a", "Ann"), 1);
        assertEquals(EnqueueResult.FULL, pool.enqueue(viewer("b", "Bob"), 1));
        assertEquals(1, pool.size());
    }

    @Test
    void nextForSpawnOnEmptyQueueIsEmpty() {
        DwellerPool pool = new DwellerPool();
        assertTrue(pool.nextForSpawn().isEmpty());
    }

    @Test
    void twoViewersAcrossTwentyDwellersAlternateEvenly() {
        DwellerPool pool = new DwellerPool();
        pool.enqueue(viewer("a", "Ann"), 100);
        pool.enqueue(viewer("b", "Bob"), 100);
        List<String> got = assign(pool, 20);
        long ann = got.stream().filter("Ann"::equals).count();
        long bob = got.stream().filter("Bob"::equals).count();
        assertEquals(10, ann);
        assertEquals(10, bob);
        assertEquals(List.of("Ann", "Bob", "Ann", "Bob"), got.subList(0, 4));
    }

    @Test
    void midVaultJoinerGetsUnshownPriority() {
        DwellerPool pool = new DwellerPool();
        pool.enqueue(viewer("a", "Ann"), 100);
        pool.enqueue(viewer("b", "Bob"), 100);
        assign(pool, 2); // Ann, Bob now both shown
        pool.enqueue(viewer("c", "Cat"), 100);
        // Cat is unshown -> debuts before the rotation repeats Ann/Bob
        assertEquals("Cat", pool.nextForSpawn().orElseThrow().twitchName());
    }

    @Test
    void retainUnshownDropsShownAndKeepsUnshownForNextVault() {
        DwellerPool pool = new DwellerPool();
        pool.enqueue(viewer("a", "Ann"), 100);
        pool.enqueue(viewer("b", "Bob"), 100);
        pool.enqueue(viewer("c", "Cat"), 100);
        assign(pool, 2); // Ann, Bob shown; Cat still unshown

        List<PendingViewer> dropped = pool.retainUnshown();
        assertEquals(List.of("Ann", "Bob"),
            dropped.stream().map(PendingViewer::twitchName).toList());
        assertEquals(1, pool.size());
        assertEquals(List.of("Cat"),
            pool.snapshot().stream().map(PendingViewer::twitchName).toList());
        // a dropped (shown) viewer can redeem again; carried-over Cat still dedups
        assertEquals(EnqueueResult.ADDED, pool.enqueue(viewer("a", "Ann"), 100));
        assertEquals(EnqueueResult.DUPLICATE, pool.enqueue(viewer("c", "Cat2"), 100));
    }

    @Test
    void purgeReturnsAllAndClears() {
        DwellerPool pool = new DwellerPool();
        pool.enqueue(viewer("a", "Ann"), 100);
        pool.enqueue(viewer("b", "Bob"), 100);
        assign(pool, 1); // one shown, one unshown -- purge takes both
        List<PendingViewer> purged = pool.purge();
        assertEquals(2, purged.size());
        assertEquals(0, pool.size());
        assertEquals(EnqueueResult.ADDED, pool.enqueue(viewer("a", "Ann"), 100));
    }

    @Test
    void snapshotPreservesInsertionOrderForFreshQueue() {
        DwellerPool pool = new DwellerPool();
        pool.enqueue(viewer("a", "Ann"), 100);
        pool.enqueue(viewer("b", "Bob"), 100);
        assertEquals(List.of("Ann", "Bob"),
            pool.snapshot().stream().map(PendingViewer::twitchName).toList());
        assertEquals(2, pool.size());
    }
}
