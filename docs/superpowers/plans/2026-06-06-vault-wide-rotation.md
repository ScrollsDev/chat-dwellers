# Vault-Wide Rotation, Auth Popup & Live Cost — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ChatDwellers' single-group dweller skinning with a whole-vault viewer rotation, swap the chat activation link for an in-game popup, and add a live channel-point cost command.

**Architecture:** Pure, unit-tested logic lives in plain classes (`DwellerPool` rotation, `VaultLifecycle` transition detector, `ActivationLink` URL chooser, `HelixClient.updateRewardCost`). Minecraft/Forge glue (spawn tracker, lifecycle tick handler, GUI screen, command) is thin wiring around those tested cores, verified by build + manual in-game test.

**Tech Stack:** Java 17, Minecraft Forge 1.18.2 (`net.minecraftforge:forge:1.18.2-40.2.0`), Gson, JUnit 5. Build/test with `./gradlew`.

**Spec:** `docs/superpowers/specs/2026-06-06-vault-wide-rotation-design.md`

---

## File Structure

**Create:**
- `src/main/java/com/chatdwellers/render/VaultLifecycle.java` — pure dimension-transition detector (enter/leave `the_vault:vault`).
- `src/main/java/com/chatdwellers/render/VaultLifecycleHandler.java` — Forge client-tick subscriber; runs vault-exit fulfillment.
- `src/main/java/com/chatdwellers/twitch/ActivationLink.java` — pure helper choosing the best activation URL.
- `src/main/java/com/chatdwellers/twitch/TwitchActivateScreen.java` — the popup `Screen` (Copy / Yes / No).
- `src/test/java/com/chatdwellers/render/VaultLifecycleTest.java`
- `src/test/java/com/chatdwellers/twitch/ActivationLinkTest.java`

**Modify:**
- `src/main/java/com/chatdwellers/pool/DwellerPool.java` — consume-on-dequeue → rotation with shown/unshown segments.
- `src/test/java/com/chatdwellers/pool/DwellerPoolTest.java` — rewritten for rotation.
- `src/main/java/com/chatdwellers/ChatDwellersClient.java` — drop `groupWindow`/`current`.
- `src/main/java/com/chatdwellers/render/DwellerSpawnTracker.java` — no radius, assign-per-spawn, no inline fulfill.
- `src/main/java/com/chatdwellers/config/Config.java` — remove `radiusBlocks`, `groupWindowSeconds`.
- `src/main/java/com/chatdwellers/twitch/HelixClient.java` — add `updateRewardCost`.
- `src/test/java/com/chatdwellers/twitch/HelixClientTest.java` — add `updateRewardCost` test.
- `src/main/java/com/chatdwellers/twitch/TwitchAuthManager.java` — emit activation data to a prompt callback, parse `verification_uri_complete`.
- `src/main/java/com/chatdwellers/twitch/TwitchBootstrap.java` — wire the prompt to open the screen.
- `src/main/java/com/chatdwellers/command/ChatDwellersCommand.java` — add `/cd cost <n>`; help line.
- `build.gradle` — version bump (release task).

**Delete:**
- `src/main/java/com/chatdwellers/group/GroupWindow.java`
- `src/test/java/com/chatdwellers/group/GroupWindowTest.java`

---

## Task 1: `HelixClient.updateRewardCost`

**Files:**
- Modify: `src/main/java/com/chatdwellers/twitch/HelixClient.java`
- Test: `src/test/java/com/chatdwellers/twitch/HelixClientTest.java`

- [ ] **Step 1: Write the failing test**

Add to `HelixClientTest`:

```java
    @Test
    void updateRewardCostPatchesCostWithIds() throws IOException {
        RecordingHttp http = new RecordingHttp(200, "{\"data\":[{\"id\":\"rew-1\",\"cost\":750}]}");
        HelixClient h = build(http);
        h.updateRewardCost("bid-1", "rew-1", 750);

        HttpRequest req = http.requests.get(0);
        assertEquals("PATCH", req.method());
        String uri = req.uri().toString();
        assertTrue(uri.contains("broadcaster_id=bid-1"), uri);
        assertTrue(uri.contains("id=rew-1"), uri);
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.chatdwellers.twitch.HelixClientTest`
Expected: FAIL — `cannot find symbol method updateRewardCost`.

- [ ] **Step 3: Implement**

Add to `HelixClient` (after `updateRedemption`):

```java
    /** Updates the live channel-point reward's cost. */
    public void updateRewardCost(String broadcasterId, String rewardId, int cost) throws IOException {
        String url = BASE + "/channel_points/custom_rewards"
            + "?broadcaster_id=" + broadcasterId
            + "&id=" + rewardId;
        JsonObject body = new JsonObject();
        body.addProperty("cost", cost);
        HttpRequest req = builder(url)
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        JsonHttp.Response r = sendWithRefresh(req);
        if (r.status() < 200 || r.status() >= 300) {
            throw new IOException("updateRewardCost HTTP " + r.status() + ": " + r.body());
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.chatdwellers.twitch.HelixClientTest`
Expected: PASS (all HelixClientTest cases green).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chatdwellers/twitch/HelixClient.java src/test/java/com/chatdwellers/twitch/HelixClientTest.java
git commit -m "feat: HelixClient.updateRewardCost to PATCH live reward price"
```

---

## Task 2: `/cd cost <n>` command

**Files:**
- Modify: `src/main/java/com/chatdwellers/command/ChatDwellersCommand.java`

No unit test (Brigadier command glue, consistent with the other untested subcommands); verified by build + manual test.

- [ ] **Step 1: Add the import**

At the top of `ChatDwellersCommand.java`, add to the imports:

```java
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.concurrent.CompletableFuture;
```

(`com.chatdwellers.config.Config` is already imported.)

- [ ] **Step 2: Register the subcommand**

In `onRegister`, add this `.then(...)` to the `tree` builder (place it after the `simulate` block, before the assignment to `root`):

```java
            .then(Commands.literal("cost")
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
                    .executes(ChatDwellersCommand::cost)));
```

- [ ] **Step 3: Implement the handler**

Add this method to the class:

```java
    private static int cost(CommandContext<CommandSourceStack> ctx) {
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        Config.setRewardCost(amount);

        boolean canPush = ChatDwellersClient.helixClient != null
            && ChatDwellersClient.tokenStore != null
            && !ChatDwellersClient.tokenStore.rewardId().isEmpty();
        if (canPush) {
            String rewardId = ChatDwellersClient.tokenStore.rewardId();
            String broadcasterId = ChatDwellersClient.tokenStore.broadcasterId();
            CompletableFuture.runAsync(() -> {
                try {
                    ChatDwellersClient.helixClient.updateRewardCost(broadcasterId, rewardId, amount);
                } catch (Exception e) {
                    com.chatdwellers.ChatDwellers.LOGGER.warn(
                        "[ChatDwellers] failed to update reward cost: {}", e.toString());
                }
            });
        }
        ctx.getSource().sendSuccess(new TextComponent(
            "Reward cost set to " + amount + " points"
            + (canPush ? " (updating on Twitch)." : " (saved; will apply on /cd reconnect).")), false);
        return 1;
    }
```

- [ ] **Step 4: Update the help text**

In `help(...)`, add this line inside the `TextComponent` string (after the `simulate` line):

```java
            + "  /cd cost <amount>                set channel-point cost (live)\n"
```

- [ ] **Step 5: Add `Config.setRewardCost`**

This is implemented in Task 8 (Config). For now, add it to `Config.java` so this task compiles. In `src/main/java/com/chatdwellers/config/Config.java`, add after the `rewardCost()` getter:

```java
    public static void setRewardCost(int v) { REWARD_COST.set(v); REWARD_COST.save(); }
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/chatdwellers/command/ChatDwellersCommand.java src/main/java/com/chatdwellers/config/Config.java
git commit -m "feat: /cd cost <n> command to change reward cost live"
```

---

## Task 3: `DwellerPool` rotation + spawn rework (cohesive change)

This task is grouped because the pool's API change, the spawn tracker, `GroupWindow` deletion, and the `radiusBlocks`/`groupWindowSeconds` config removals are tightly coupled — the project must compile at the commit boundary. TDD drives the pool; the glue updates ride along.

**Files:**
- Modify/Test: `src/main/java/com/chatdwellers/pool/DwellerPool.java`, `src/test/java/com/chatdwellers/pool/DwellerPoolTest.java`
- Modify: `src/main/java/com/chatdwellers/render/DwellerSpawnTracker.java`
- Modify: `src/main/java/com/chatdwellers/ChatDwellersClient.java`
- Modify: `src/main/java/com/chatdwellers/config/Config.java`
- Delete: `src/main/java/com/chatdwellers/group/GroupWindow.java`, `src/test/java/com/chatdwellers/group/GroupWindowTest.java`

- [ ] **Step 1: Replace `DwellerPoolTest` with rotation tests**

Overwrite `src/test/java/com/chatdwellers/pool/DwellerPoolTest.java`:

```java
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
        // Cat is unshown → debuts before the rotation repeats Ann/Bob
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
        assign(pool, 1); // one shown, one unshown — purge takes both
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.chatdwellers.pool.DwellerPoolTest`
Expected: FAIL/compile error — `nextForSpawn` / `retainUnshown` don't exist.

- [ ] **Step 3: Rewrite `DwellerPool` as a rotation**

Overwrite `src/main/java/com/chatdwellers/pool/DwellerPool.java`:

```java
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
```

- [ ] **Step 4: Run pool tests to verify they pass**

Run: `./gradlew test --tests com.chatdwellers.pool.DwellerPoolTest`
Expected: PASS. (Other modules won't compile yet — that's fixed in the next steps. If `test` fails to compile the whole source set, proceed to Steps 5-8 then re-run.)

- [ ] **Step 5: Delete `GroupWindow` and its test**

```bash
git rm src/main/java/com/chatdwellers/group/GroupWindow.java src/test/java/com/chatdwellers/group/GroupWindowTest.java
```

- [ ] **Step 6: Update `ChatDwellersClient`**

Overwrite `src/main/java/com/chatdwellers/ChatDwellersClient.java`:

```java
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
```

Then remove the now-stale `current` reference in the purge command. In
`src/main/java/com/chatdwellers/command/ChatDwellersCommand.java`, find inside `purge(...)`:

```java
        java.util.List<PendingViewer> drained = pool.purge();
        ChatDwellersClient.current = null;
        DwellerSkins.clearAll();
```

and delete the middle line so it reads:

```java
        java.util.List<PendingViewer> drained = pool.purge();
        DwellerSkins.clearAll();
```

(`pool.purge()` already drains both rotation segments, so nothing else in the command changes.)

- [ ] **Step 7: Rewrite `DwellerSpawnTracker` (no radius, per-spawn assignment, no inline fulfill)**

Overwrite `src/main/java/com/chatdwellers/render/DwellerSpawnTracker.java`:

```java
package com.chatdwellers.render;

import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.config.Config;
import com.chatdwellers.pool.PendingViewer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.EntityLeaveWorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.Map;

@Mod.EventBusSubscriber(modid = com.chatdwellers.ChatDwellers.MODID, value = Dist.CLIENT)
public final class DwellerSpawnTracker {

    private DwellerSpawnTracker() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinWorldEvent event) {
        if (!event.getWorld().isClientSide()) return;
        if (!Config.enabled()) return;

        Entity entity = event.getEntity();
        ResourceLocation type = EntityType.getKey(entity.getType());
        if (!type.getNamespace().equals("the_vault")) return;
        if (!type.getPath().startsWith("vault_fighter")) return;

        // Assignment is bound here, at spawn, and never applied retroactively. Queue empty →
        // this dweller keeps the default skin for its lifetime (never revisited).
        PendingViewer viewer = ChatDwellersClient.pool.nextForSpawn().orElse(null);
        if (viewer == null) return;

        int id = entity.getId();
        DwellerSkins.setName(id, new TextComponent(Config.formatNametag(viewer.twitchName(), viewer.mcName())));
        DwellerSkins.setSkin(id, viewer.mcName());
        VaultSkinSupport.maintain(entity, viewer.mcName());
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveWorldEvent event) {
        if (!event.getWorld().isClientSide()) return;
        DwellerSkins.clear(event.getEntity().getId());
    }

    /**
     * Re-asserts each tagged dweller's skin at the start of every client tick — before Vault's
     * {@code FighterEntity.tick()} runs — so Vault can't re-derive the skin from the custom name
     * and overwrite the viewer's. {@link VaultSkinSupport#maintain} is idempotent.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!Config.enabled()) return;
        if (DwellerSkins.skins().isEmpty()) return;

        var mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (Map.Entry<Integer, String> tag : DwellerSkins.skins().entrySet()) {
            Entity entity = mc.level.getEntity(tag.getKey());
            if (entity != null) {
                VaultSkinSupport.maintain(entity, tag.getValue());
            }
        }
    }
}
```

- [ ] **Step 8: Remove `radiusBlocks` and `groupWindowSeconds` from `Config`**

In `src/main/java/com/chatdwellers/config/Config.java`:

Delete these field declarations:
```java
    private static final ForgeConfigSpec.IntValue RADIUS_BLOCKS;
    private static final ForgeConfigSpec.IntValue GROUP_WINDOW_SECONDS;
```

Delete these builder blocks inside `static { ... }` (the whole `RADIUS_BLOCKS = ...` and `GROUP_WINDOW_SECONDS = ...` statements with their comments):
```java
        RADIUS_BLOCKS = b.comment("Skin dwellers spawning within this many blocks of you")
            .defineInRange("radiusBlocks", 15, 1, 256);
        GROUP_WINDOW_SECONDS = b.comment("Dwellers spawning within this many seconds share one viewer")
            .defineInRange("groupWindowSeconds", 2, 1, 60);
```

Delete these getters:
```java
    public static int radiusBlocks() { return RADIUS_BLOCKS.get(); }
    public static int groupWindowSeconds() { return GROUP_WINDOW_SECONDS.get(); }
```

(The `MAX_POOL_SIZE` block and all other config stay. `setRewardCost` added in Task 2 stays.)

- [ ] **Step 9: Run the full test suite + compile**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — all tests pass, whole source set compiles, no references to `GroupWindow`, `radiusBlocks`, `groupWindowSeconds`, or `ChatDwellersClient.current`/`groupWindow`.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: whole-vault viewer rotation; remove group window and radius"
```

---

## Task 4: `VaultLifecycle` transition detector

**Files:**
- Create: `src/main/java/com/chatdwellers/render/VaultLifecycle.java`
- Test: `src/test/java/com/chatdwellers/render/VaultLifecycleTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/chatdwellers/render/VaultLifecycleTest.java`:

```java
package com.chatdwellers.render;

import com.chatdwellers.render.VaultLifecycle.Transition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VaultLifecycleTest {

    @Test
    void enteringVaultFromOverworldReportsEntered() {
        VaultLifecycle lc = new VaultLifecycle();
        assertEquals(Transition.NONE, lc.update("minecraft:overworld"));
        assertEquals(Transition.ENTERED_VAULT, lc.update("the_vault:vault"));
    }

    @Test
    void leavingVaultReportsLeft() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault");
        assertEquals(Transition.LEFT_VAULT, lc.update("minecraft:overworld"));
    }

    @Test
    void stayingInVaultReportsNone() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault");
        assertEquals(Transition.NONE, lc.update("the_vault:vault"));
    }

    @Test
    void losingLevelWhileInVaultCountsAsLeft() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault");
        assertEquals(Transition.LEFT_VAULT, lc.update(null));
    }

    @Test
    void nullWhileNotInVaultIsNone() {
        VaultLifecycle lc = new VaultLifecycle();
        assertEquals(Transition.NONE, lc.update(null));
        assertEquals(Transition.NONE, lc.update("minecraft:overworld"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.chatdwellers.render.VaultLifecycleTest`
Expected: FAIL — class `VaultLifecycle` does not exist.

- [ ] **Step 3: Implement**

Create `src/main/java/com/chatdwellers/render/VaultLifecycle.java`:

```java
package com.chatdwellers.render;

/**
 * Pure detector of the player crossing in/out of the Vault dimension ({@code the_vault:vault}).
 * Fed the current dimension id (or null when there is no level) each client tick; reports the
 * edge. Correctness depends only on the LEFT_VAULT edge — that's when the rotation is settled.
 */
public final class VaultLifecycle {

    public static final String VAULT_DIM = "the_vault:vault";

    public enum Transition { NONE, ENTERED_VAULT, LEFT_VAULT }

    private String last = null;

    public synchronized Transition update(String currentDim) {
        boolean wasVault = VAULT_DIM.equals(last);
        boolean isVault = VAULT_DIM.equals(currentDim);
        last = currentDim;
        if (isVault && !wasVault) return Transition.ENTERED_VAULT;
        if (!isVault && wasVault) return Transition.LEFT_VAULT;
        return Transition.NONE;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.chatdwellers.render.VaultLifecycleTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chatdwellers/render/VaultLifecycle.java src/test/java/com/chatdwellers/render/VaultLifecycleTest.java
git commit -m "feat: VaultLifecycle dimension-transition detector"
```

---

## Task 5: `VaultLifecycleHandler` (tick watcher + vault-exit fulfillment)

**Files:**
- Create: `src/main/java/com/chatdwellers/render/VaultLifecycleHandler.java`

Forge glue (no unit test); verified by build + manual test. Depends on `DwellerPool.retainUnshown` (Task 3) and `VaultLifecycle` (Task 4).

- [ ] **Step 1: Implement**

Create `src/main/java/com/chatdwellers/render/VaultLifecycleHandler.java`:

```java
package com.chatdwellers.render;

import com.chatdwellers.ChatDwellers;
import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.config.Config;
import com.chatdwellers.pool.PendingViewer;
import com.chatdwellers.render.VaultLifecycle.Transition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Watches the player's dimension each client tick. On leaving the Vault, fulfills (on Twitch)
 * every viewer that was actually shown this vault and drops them, keeping un-shown viewers
 * queued for the next vault. See the design doc for the rationale (fulfill-at-exit-if-shown).
 */
@Mod.EventBusSubscriber(modid = ChatDwellers.MODID, value = Dist.CLIENT)
public final class VaultLifecycleHandler {

    private static final VaultLifecycle LIFECYCLE = new VaultLifecycle();

    private VaultLifecycleHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        String dim = (level == null) ? null : level.dimension().location().toString();

        Transition t = LIFECYCLE.update(dim);
        if (t == Transition.LEFT_VAULT) {
            onVaultExit();
        }
    }

    private static void onVaultExit() {
        DwellerSkins.clearAll();
        List<PendingViewer> shown = ChatDwellersClient.pool.retainUnshown();
        if (!Config.enabled()) return;
        if (ChatDwellersClient.helixClient == null
            || ChatDwellersClient.tokenStore == null
            || ChatDwellersClient.tokenStore.rewardId().isEmpty()) {
            return;
        }
        final String rewardId = ChatDwellersClient.tokenStore.rewardId();
        final String broadcasterId = ChatDwellersClient.tokenStore.broadcasterId();
        for (PendingViewer v : shown) {
            if (v.redemptionId().startsWith("sim:")) continue;
            final String redemptionId = v.redemptionId();
            CompletableFuture.runAsync(() -> {
                try {
                    ChatDwellersClient.helixClient.updateRedemption(
                        broadcasterId, rewardId, redemptionId, "FULFILLED");
                } catch (Exception e) {
                    ChatDwellers.LOGGER.warn(
                        "[ChatDwellers] failed to fulfill {} at vault exit: {}",
                        redemptionId, e.toString());
                }
            });
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/chatdwellers/render/VaultLifecycleHandler.java
git commit -m "feat: fulfill shown viewers and reset rotation on vault exit"
```

---

## Task 6: `ActivationLink` URL chooser

**Files:**
- Create: `src/main/java/com/chatdwellers/twitch/ActivationLink.java`
- Test: `src/test/java/com/chatdwellers/twitch/ActivationLinkTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/chatdwellers/twitch/ActivationLinkTest.java`:

```java
package com.chatdwellers.twitch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivationLinkTest {

    @Test
    void prefersCompleteUrlWhenPresent() {
        assertEquals("https://twitch.tv/activate?device-code=ABC123",
            ActivationLink.best("https://twitch.tv/activate",
                "https://twitch.tv/activate?device-code=ABC123"));
    }

    @Test
    void fallsBackToBaseWhenCompleteMissing() {
        assertEquals("https://twitch.tv/activate",
            ActivationLink.best("https://twitch.tv/activate", null));
        assertEquals("https://twitch.tv/activate",
            ActivationLink.best("https://twitch.tv/activate", ""));
        assertEquals("https://twitch.tv/activate",
            ActivationLink.best("https://twitch.tv/activate", "   "));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.chatdwellers.twitch.ActivationLinkTest`
Expected: FAIL — class `ActivationLink` does not exist.

- [ ] **Step 3: Implement**

Create `src/main/java/com/chatdwellers/twitch/ActivationLink.java`:

```java
package com.chatdwellers.twitch;

/** Chooses the activation URL to open/copy: the code-prefilled URL when Twitch returns one. */
public final class ActivationLink {

    private ActivationLink() {}

    public static String best(String verificationUri, String verificationUriComplete) {
        if (verificationUriComplete != null && !verificationUriComplete.isBlank()) {
            return verificationUriComplete;
        }
        return verificationUri;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.chatdwellers.twitch.ActivationLinkTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chatdwellers/twitch/ActivationLink.java src/test/java/com/chatdwellers/twitch/ActivationLinkTest.java
git commit -m "feat: ActivationLink chooses prefilled Twitch activation URL"
```

---

## Task 7: `TwitchActivateScreen` popup

**Files:**
- Create: `src/main/java/com/chatdwellers/twitch/TwitchActivateScreen.java`

Minecraft GUI glue (no unit test); verified by build + manual test.

- [ ] **Step 1: Implement**

Create `src/main/java/com/chatdwellers/twitch/TwitchActivateScreen.java`:

```java
package com.chatdwellers.twitch;

import com.chatdwellers.ChatDwellers;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

/**
 * Popup shown when Twitch device-code authorization starts. Offers Copy (link to clipboard),
 * Yes (open the link in the browser), and No (dismiss — covers an accidental popup). The
 * background polling in {@link TwitchAuthManager} keeps running regardless; re-open with
 * {@code /cd reconnect}.
 */
public final class TwitchActivateScreen extends Screen {

    private final String url;
    private final String userCode;

    public TwitchActivateScreen(String verificationUri, String verificationUriComplete, String userCode) {
        super(new TextComponent("Activate ChatDwellers on Twitch"));
        this.url = ActivationLink.best(verificationUri, verificationUriComplete);
        this.userCode = userCode;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int row = this.height / 2;

        // Copy button next to the instruction (for people who want to paste it themselves).
        this.addRenderableWidget(new Button(cx + 60, row - 24, 60, 20,
            new TextComponent("Copy"), b -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(url);
            }));

        // Yes — open the (code-prefilled) link in the default browser.
        this.addRenderableWidget(new Button(cx - 105, row + 20, 100, 20,
            new TextComponent("Yes, open it"), b -> {
                try {
                    Util.getPlatform().openUri(url);
                } catch (Exception e) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] failed to open browser: {}", e.toString());
                }
                onClose();
            }));

        // No — dismiss.
        this.addRenderableWidget(new Button(cx + 5, row + 20, 100, 20,
            new TextComponent("No"), b -> onClose()));
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(pose);
        drawCenteredString(pose, this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        drawCenteredString(pose, this.font,
            new TextComponent("Authorize, then continue on Twitch."),
            this.width / 2, this.height / 2 - 44, 0xA0A0A0);
        Component code = new TextComponent("Code: " + userCode);
        drawString(pose, this.font, code, this.width / 2 - 100, this.height / 2 - 19, 0xFFFF55);
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

> Note for the implementer: `Button(int,int,int,int,Component,OnPress)`, `drawCenteredString`/`drawString(PoseStack,Font,...)`, `Util.getPlatform().openUri(String)`, and `Minecraft.keyboardHandler.setClipboard(String)` are all the 1.18.2 signatures. If a signature mismatch appears, fix to the 1.18.2 API rather than changing behavior.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/chatdwellers/twitch/TwitchActivateScreen.java
git commit -m "feat: TwitchActivateScreen popup with Copy/Yes/No"
```

---

## Task 8: Wire the popup into the auth flow

**Files:**
- Modify: `src/main/java/com/chatdwellers/twitch/TwitchAuthManager.java`
- Modify: `src/main/java/com/chatdwellers/twitch/TwitchBootstrap.java`

Glue (no unit test — the `ActivationLink` logic is already tested). The only caller of the `TwitchAuthManager` constructor is `TwitchBootstrap`.

- [ ] **Step 1: Add the prompt callback to `TwitchAuthManager`**

In `src/main/java/com/chatdwellers/twitch/TwitchAuthManager.java`:

Add this functional interface inside the class (near the top, after the field declarations):

```java
    /** Surfaces the device-code data so the client can show an activation popup. */
    public interface ActivationPrompt {
        void show(String verificationUri, String verificationUriComplete, String userCode);
    }
```

Add a field and constructor parameter. Change the field block + constructor to:

```java
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
```

- [ ] **Step 2: Parse `verification_uri_complete` and call the prompt instead of chat**

In `startDeviceCode()`, replace the URI parsing + chat line. Find:

```java
                String uri        = dev.has("verification_uri")
                    ? dev.get("verification_uri").getAsString()
                    : "https://twitch.tv/activate";
                int interval      = dev.has("interval") ? dev.get("interval").getAsInt() : 5;
                int expiresIn     = dev.has("expires_in") ? dev.get("expires_in").getAsInt() : 1800;

                chatNote.accept("[ChatDwellers] Authorize Twitch: visit " + uri + " and enter code " + userCode);
```

Replace with:

```java
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
```

- [ ] **Step 3: Wire the prompt in `TwitchBootstrap`**

In `src/main/java/com/chatdwellers/twitch/TwitchBootstrap.java`, find:

```java
        this.auth = new TwitchAuthManager(http, store, clientId, TwitchBootstrap::sendChat);
```

Replace with:

```java
        this.auth = new TwitchAuthManager(http, store, clientId, TwitchBootstrap::sendChat,
            TwitchBootstrap::showActivation);
```

Add this method to `TwitchBootstrap` (next to `sendChat`):

```java
    private static void showActivation(String uri, String uriComplete, String userCode) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(
            new com.chatdwellers.twitch.TwitchActivateScreen(uri, uriComplete, userCode)));
    }
```

(`net.minecraft.client.Minecraft` is already imported in `TwitchBootstrap`.)

- [ ] **Step 4: Build + test the whole project**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — compiles and all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chatdwellers/twitch/TwitchAuthManager.java src/main/java/com/chatdwellers/twitch/TwitchBootstrap.java
git commit -m "feat: show activation popup instead of chat link"
```

---

## Task 9: Toast notification helper (`Notify` + `ChatDwellersToast`)

**Files:**
- Create: `src/main/java/com/chatdwellers/client/Notify.java`

Minecraft glue (no unit test); verified by build + manual test. Uses Minecraft's built-in
`SystemToast` (top-right auto-fading card) so we don't hand-render a texture. Note: SystemToast
dedupes by id, so two notifications fired in the same instant show only the latest — fine for
our low-frequency messages.

- [ ] **Step 1: Implement**

Create `src/main/java/com/chatdwellers/client/Notify.java`:

```java
package com.chatdwellers.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.TextComponent;

/** Shows ChatDwellers notifications as toast popups (top-right), replacing all chat output. */
public final class Notify {

    private static final SystemToast.SystemToastIds ID = SystemToast.SystemToastIds.PERIODIC_NOTIFICATION;
    private static final String PREFIX = "[ChatDwellers] ";

    private Notify() {}

    public static void toast(String message) {
        String body = message.startsWith(PREFIX) ? message.substring(PREFIX.length()) : message;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.add(mc.getToasts(), ID,
            new TextComponent("ChatDwellers"), new TextComponent(body)));
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (If `SystemToastIds.PERIODIC_NOTIFICATION` is absent in 1.18.2,
use another constant such as `NARRATOR_TOGGLE` — any id works.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/chatdwellers/client/Notify.java
git commit -m "feat: toast notification helper (Notify)"
```

---

## Task 10: Shared `ChatDwellersActions`

**Files:**
- Create: `src/main/java/com/chatdwellers/action/ChatDwellersActions.java`

Extracts the side-effecting logic that the typed commands and the panel buttons will share.
Added now (unused until Tasks 11-12 call it), so it compiles standalone. No unit test (it's
thin glue over already-tested `DwellerPool`/`HelixClient`); verified by build.

- [ ] **Step 1: Implement**

Create `src/main/java/com/chatdwellers/action/ChatDwellersActions.java`:

```java
package com.chatdwellers.action;

import com.chatdwellers.ChatDwellers;
import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.config.Config;
import com.chatdwellers.pool.DwellerPool;
import com.chatdwellers.pool.PendingViewer;
import com.chatdwellers.render.DwellerSkins;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** Side-effecting actions shared by the typed commands and the control panel. */
public final class ChatDwellersActions {

    private ChatDwellersActions() {}

    /** Flips enabled state, restarting/stopping the Twitch bootstrap. Returns a result message. */
    public static String toggle() {
        boolean now = !Config.enabled();
        Config.setEnabled(now);
        if (ChatDwellersClient.bootstrap != null) {
            if (now) ChatDwellersClient.bootstrap.restart();
            else ChatDwellersClient.bootstrap.stop();
        }
        return now ? "Enabled."
            : "Disabled. Pending redemptions stay until re-enabled or purged.";
    }

    public static String reconnect() {
        if (ChatDwellersClient.bootstrap == null) return "Twitch bootstrap not initialized.";
        ChatDwellersClient.bootstrap.restart();
        return "Reconnecting to Twitch...";
    }

    public static String purge() {
        DwellerPool pool = ChatDwellersClient.pool;
        List<PendingViewer> drained = pool.purge();
        DwellerSkins.clearAll();
        int refunded = 0;
        if (ChatDwellersClient.helixClient != null
            && ChatDwellersClient.tokenStore != null
            && !ChatDwellersClient.tokenStore.rewardId().isEmpty()) {
            String rewardId = ChatDwellersClient.tokenStore.rewardId();
            String broadcasterId = ChatDwellersClient.tokenStore.broadcasterId();
            for (PendingViewer v : drained) {
                if (v.redemptionId().startsWith("sim:")) continue;
                refunded++;
                final String redemptionId = v.redemptionId();
                CompletableFuture.runAsync(() -> {
                    try {
                        ChatDwellersClient.helixClient.updateRedemption(
                            broadcasterId, rewardId, redemptionId, "CANCELED");
                    } catch (Exception e) {
                        ChatDwellers.LOGGER.warn("[ChatDwellers] failed to refund {}: {}",
                            redemptionId, e.toString());
                    }
                });
            }
        }
        return "Purged " + drained.size() + " viewer(s)"
            + (refunded > 0 ? " (refunding " + refunded + " on Twitch)" : "") + ".";
    }

    public static String setCost(int amount) {
        Config.setRewardCost(amount);
        boolean canPush = ChatDwellersClient.helixClient != null
            && ChatDwellersClient.tokenStore != null
            && !ChatDwellersClient.tokenStore.rewardId().isEmpty();
        if (canPush) {
            String rewardId = ChatDwellersClient.tokenStore.rewardId();
            String broadcasterId = ChatDwellersClient.tokenStore.broadcasterId();
            CompletableFuture.runAsync(() -> {
                try {
                    ChatDwellersClient.helixClient.updateRewardCost(broadcasterId, rewardId, amount);
                } catch (Exception e) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] failed to update reward cost: {}",
                        e.toString());
                }
            });
        }
        return "Reward cost set to " + amount + " points"
            + (canPush ? " (updating on Twitch)." : " (saved; applies on reconnect).");
    }

    public static String simulate(String twitch, String mc) {
        PendingViewer viewer = new PendingViewer(
            twitch.toLowerCase(Locale.ROOT), twitch, mc, "sim:" + UUID.randomUUID());
        DwellerPool.EnqueueResult result =
            ChatDwellersClient.pool.enqueue(viewer, Config.maxPoolSize());
        return "simulate " + twitch + " / " + mc + " -> " + result;
    }

    /** One-line live status, used by /cd status and drawn in the panel header. */
    public static String statusLine() {
        DwellerPool pool = ChatDwellersClient.pool;
        String names = pool.snapshot().stream()
            .map(PendingViewer::twitchName).collect(Collectors.joining(", "));
        String enabledState = Config.enabled() ? "ON" : "OFF";
        String twitchState = "DISABLED";
        if (ChatDwellersClient.tokenStore != null) {
            if (ChatDwellersClient.tokenStore.accessToken().isEmpty()) twitchState = "UNAUTHORIZED";
            else if (ChatDwellersClient.tokenStore.rewardId().isEmpty()) twitchState = "REWARD-MISSING";
            else twitchState = "CONNECTED";
        }
        return enabledState + " | Twitch: " + twitchState
            + " | reward: '" + Config.rewardName() + "' (" + Config.rewardCost() + " pts)"
            + " | queue (" + pool.size() + "): " + names;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/chatdwellers/action/ChatDwellersActions.java
git commit -m "refactor: extract ChatDwellersActions shared by commands and panel"
```

---

## Task 11: `ChatDwellersScreen` control panel

**Files:**
- Create: `src/main/java/com/chatdwellers/client/ChatDwellersScreen.java`

Minecraft GUI glue (no unit test); verified by build + manual test.

- [ ] **Step 1: Implement**

Create `src/main/java/com/chatdwellers/client/ChatDwellersScreen.java`:

```java
package com.chatdwellers.client;

import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.action.ChatDwellersActions;
import com.chatdwellers.config.Config;
import com.chatdwellers.pool.PendingViewer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import java.util.List;

/** Primary ChatDwellers UI: live status + buttons. Opened by typing {@code /cd}. */
public final class ChatDwellersScreen extends Screen {

    private EditBox costField;
    private EditBox simTwitch;
    private EditBox simMc;
    private Button toggleButton;

    public ChatDwellersScreen() {
        super(new TextComponent("ChatDwellers"));
    }

    private TextComponent toggleLabel() {
        return new TextComponent(Config.enabled() ? "Turn OFF" : "Turn ON");
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = 56;

        toggleButton = this.addRenderableWidget(new Button(cx - 154, top, 100, 20,
            toggleLabel(), b -> {
                Notify.toast(ChatDwellersActions.toggle());
                toggleButton.setMessage(toggleLabel());
            }));
        this.addRenderableWidget(new Button(cx - 50, top, 100, 20,
            new TextComponent("Reconnect"), b -> Notify.toast(ChatDwellersActions.reconnect())));
        this.addRenderableWidget(new Button(cx + 54, top, 100, 20,
            new TextComponent("Purge"), b -> Notify.toast(ChatDwellersActions.purge())));

        // Cost row
        costField = new EditBox(this.font, cx - 154, top + 44, 100, 20, new TextComponent("cost"));
        costField.setValue(Integer.toString(Config.rewardCost()));
        this.addRenderableWidget(costField);
        this.addRenderableWidget(new Button(cx - 50, top + 44, 100, 20,
            new TextComponent("Set cost"), b -> {
                try {
                    int v = Math.max(1, Integer.parseInt(costField.getValue().trim()));
                    Notify.toast(ChatDwellersActions.setCost(v));
                } catch (NumberFormatException e) {
                    Notify.toast("Cost must be a whole number.");
                }
            }));

        // Simulate (testing) row
        simTwitch = new EditBox(this.font, cx - 154, top + 88, 100, 20, new TextComponent("twitch"));
        simMc = new EditBox(this.font, cx - 50, top + 88, 100, 20, new TextComponent("mc"));
        this.addRenderableWidget(simTwitch);
        this.addRenderableWidget(simMc);
        this.addRenderableWidget(new Button(cx + 54, top + 88, 100, 20,
            new TextComponent("Add (test)"), b -> {
                if (!simTwitch.getValue().isBlank() && !simMc.getValue().isBlank()) {
                    Notify.toast(ChatDwellersActions.simulate(
                        simTwitch.getValue().trim(), simMc.getValue().trim()));
                }
            }));

        this.addRenderableWidget(new Button(cx - 50, this.height - 32, 100, 20,
            new TextComponent("Done"), b -> onClose()));
    }

    @Override
    public void tick() {
        costField.tick();
        simTwitch.tick();
        simMc.tick();
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(pose);
        int cx = this.width / 2;
        drawCenteredString(pose, this.font, this.title, cx, 16, 0xFFFFFF);
        drawCenteredString(pose, this.font,
            new TextComponent(ChatDwellersActions.statusLine()), cx, 34, 0xA0A0A0);
        drawString(pose, this.font, new TextComponent("Cost:"), cx - 154, 56 + 34, 0xFFFFFF);
        drawString(pose, this.font, new TextComponent("Test viewer (twitch / mc):"),
            cx - 154, 56 + 78, 0xFFFFFF);

        List<PendingViewer> q = ChatDwellersClient.pool.snapshot();
        int y = 56 + 120;
        drawString(pose, this.font, new TextComponent("Queue (" + q.size() + "):"),
            cx - 154, y, 0xFFFFFF);
        int shown = Math.min(q.size(), 8);
        for (int i = 0; i < shown; i++) {
            drawString(pose, this.font, new TextComponent("- " + q.get(i).twitchName()),
                cx - 150, y + 12 + i * 10, 0xC0C0C0);
        }
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

> Implementer note: `EditBox(Font,int,int,int,int,Component)`, `EditBox#tick/getValue/setValue`,
> `addRenderableWidget` returning the widget, and the static `drawString`/`drawCenteredString`
> are all 1.18.2 APIs. Fix any signature drift to the 1.18.2 API without changing behavior.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/chatdwellers/client/ChatDwellersScreen.java
git commit -m "feat: ChatDwellersScreen control panel"
```

---

## Task 12: Make it chat-free — rewire commands & bootstrap to toasts + open panel

**Files:**
- Modify: `src/main/java/com/chatdwellers/command/ChatDwellersCommand.java`
- Modify: `src/main/java/com/chatdwellers/twitch/TwitchBootstrap.java`

Glue (no unit test); verified by build + manual test + a grep audit that no chat call remains.

- [ ] **Step 1: Rewrite `ChatDwellersCommand`**

Overwrite `src/main/java/com/chatdwellers/command/ChatDwellersCommand.java`:

```java
package com.chatdwellers.command;

import com.chatdwellers.action.ChatDwellersActions;
import com.chatdwellers.client.ChatDwellersScreen;
import com.chatdwellers.client.Notify;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = com.chatdwellers.ChatDwellers.MODID, value = Dist.CLIENT)
public final class ChatDwellersCommand {

    private ChatDwellersCommand() {}

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> tree = Commands.literal("chatdwellers")
            .executes(ChatDwellersCommand::openPanel)
            .then(Commands.literal("help").executes(ChatDwellersCommand::openPanel))
            .then(Commands.literal("status").executes(ChatDwellersCommand::status))
            .then(Commands.literal("purge").executes(ChatDwellersCommand::purge))
            .then(Commands.literal("reconnect").executes(ChatDwellersCommand::reconnect))
            .then(Commands.literal("on").executes(ChatDwellersCommand::turnOn))
            .then(Commands.literal("off").executes(ChatDwellersCommand::turnOff))
            .then(Commands.literal("cost")
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
                    .executes(ChatDwellersCommand::cost)))
            .then(Commands.literal("simulate")
                .then(Commands.argument("twitch", StringArgumentType.word())
                    .then(Commands.argument("mc", StringArgumentType.word())
                        .executes(ChatDwellersCommand::simulate))));

        LiteralCommandNode<CommandSourceStack> root = event.getDispatcher().register(tree);
        event.getDispatcher().register(Commands.literal("cd").redirect(root));
    }

    private static int openPanel(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new ChatDwellersScreen()));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.statusLine());
        return 1;
    }

    private static int purge(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.purge());
        return 1;
    }

    private static int turnOn(CommandContext<CommandSourceStack> ctx) {
        if (!com.chatdwellers.config.Config.enabled()) {
            Notify.toast(ChatDwellersActions.toggle());
        } else {
            Notify.toast("Already enabled.");
        }
        return 1;
    }

    private static int turnOff(CommandContext<CommandSourceStack> ctx) {
        if (com.chatdwellers.config.Config.enabled()) {
            Notify.toast(ChatDwellersActions.toggle());
        } else {
            Notify.toast("Already disabled.");
        }
        return 1;
    }

    private static int reconnect(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.reconnect());
        return 1;
    }

    private static int cost(CommandContext<CommandSourceStack> ctx) {
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        Notify.toast(ChatDwellersActions.setCost(amount));
        return 1;
    }

    private static int simulate(CommandContext<CommandSourceStack> ctx) {
        String twitch = StringArgumentType.getString(ctx, "twitch");
        String mc = StringArgumentType.getString(ctx, "mc");
        Notify.toast(ChatDwellersActions.simulate(twitch, mc));
        return 1;
    }
}
```

- [ ] **Step 2: Convert `TwitchBootstrap.sendChat` to a toast**

In `src/main/java/com/chatdwellers/twitch/TwitchBootstrap.java`, find:

```java
    private static void sendChat(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(new TextComponent(message), mc.player.getUUID());
        } else {
            ChatDwellers.LOGGER.info(message);
        }
    }
```

Replace with:

```java
    private static void sendChat(String message) {
        ChatDwellers.LOGGER.info(message);
        com.chatdwellers.client.Notify.toast(message);
    }
```

(The method keeps its name so its existing `TwitchBootstrap::sendChat` references and direct
calls are unchanged; it now toasts instead of writing to chat. The now-unused `TextComponent`
import may be removed if the compiler flags it.)

- [ ] **Step 3: Audit — no chat output remains**

Run a search for chat sinks across main sources:

```bash
grep -rn "sendMessage\|sendSuccess\|sendFailure" src/main/java
```

Expected: **no matches** (the activation popup, toasts, and the panel are the only UI). If any
remain, convert them to `Notify.toast(...)`.

- [ ] **Step 4: Build + test the whole project**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — compiles and all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chatdwellers/command/ChatDwellersCommand.java src/main/java/com/chatdwellers/twitch/TwitchBootstrap.java
git commit -m "feat: chat-free UI — /cd opens panel, commands and notices use toasts"
```

---

## Task 13: Full build + manual in-game verification

**Files:** none (verification only).

- [ ] **Step 1: Full build (produces the obfuscated jar)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; jar at `build/libs/chatdwellers-1.0.5.jar` (version bumps in Task 10).

- [ ] **Step 2: Manual test checklist (in VH3 Remastered)**

Drop the jar into the instance's `mods/` folder, launch, and verify:
- [ ] **No mod output appears in chat at all.** Notifications appear as toast popups (top-right).
- [ ] Typing `/cd` (no args) opens the **control panel**: it shows live status + queue, and the On/Off, Reconnect, Purge, cost field + Set, and test-viewer Add buttons all work (each shows a toast).
- [ ] On first launch with no token, the **activation popup** appears (not a chat link). **Copy** puts the URL on the clipboard; **Yes** opens the browser (code prefilled if Twitch supplied it); **No** dismisses and `/cd reconnect` re-shows it.
- [ ] Setting the cost in the panel (or `/cd cost 750`) changes the reward price in the Twitch Channel Points panel.
- [ ] In a vault with 1–2 queued viewers (panel "Add (test)" or `/cd simulate <twitch> <mc>`), **every** dweller is skinned, names cycle, and viewers repeat across many dwellers.
- [ ] A dweller that spawned while the queue was empty stays default-skinned even after a viewer is queued; only newly-spawned dwellers pick up skins.
- [ ] On leaving the vault, the panel queue shows shown viewers removed (and real redemptions marked fulfilled on Twitch); a queued-but-never-shown viewer remains for the next vault.

- [ ] **Step 3: Commit (only if manual testing required code fixes)**

```bash
git add -A
git commit -m "fix: address manual-test findings"
```

---

## Task 14: Version bump & release

**Files:**
- Modify: `build.gradle`

The release workflow (`.github/workflows/release.yml`) builds and publishes a GitHub Release when a `v*` tag is pushed. The jar artifact name is driven by `version` in `build.gradle`.

- [ ] **Step 1: Bump the version**

In `build.gradle`, change:

```groovy
version = '1.0.5'
```

to:

```groovy
version = '1.0.6'
```

- [ ] **Step 2: Commit**

```bash
git add build.gradle
git commit -m "chore: bump version to 1.0.6"
```

- [ ] **Step 3: Decide the release path (CONFIRM WITH USER before pushing)**

Pushing a tag triggers a public GitHub Release — an outward-facing action. Confirm with the user which path:
- **(a)** Merge `feat/vault-wide-rotation` into `main`, then tag `v1.0.6` on `main` and push.
- **(b)** Tag `v1.0.6` directly on the branch commit and push (release builds from the branch; `main` untouched for now).

- [ ] **Step 4: Push branch, tag, and trigger the release** (after confirmation)

For path (a):
```bash
git checkout main && git merge --no-ff feat/vault-wide-rotation -m "Merge vault-wide rotation, auth popup, live cost"
git push origin main
git tag v1.0.6 && git push origin v1.0.6
```

For path (b):
```bash
git push origin feat/vault-wide-rotation
git tag v1.0.6 && git push origin v1.0.6
```

- [ ] **Step 5: Verify the release**

Run: `gh run list --workflow release.yml --limit 1`
Then confirm the release exists with the attached jar:
Run: `gh release view v1.0.6`
Expected: release `v1.0.6` with `chatdwellers-1.0.6.jar` attached.

---

## Notes for the implementer

- Run a single test class with `./gradlew test --tests <fully.qualified.ClassName>`; the whole suite with `./gradlew test`.
- The first `./gradlew` invocation downloads Forge/MCP mappings and may take several minutes.
- On Windows the wrapper is `gradlew.bat`; from the repo root `./gradlew` works under the Bash tool, or use `cmd /c "gradlew.bat test"` from PowerShell.
- Do **not** add a `Co-Authored-By: Claude` trailer to commits (user preference).
