# Vault-Exit Fix, Clear-on-Exit Toggle, Claim & Blacklist — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the queue-never-clears bug in VH3 Remastered, make per-vault clearing toggleable, add a manual stream-end claim, and add a redemption blacklist that refunds points and posts a custom Twitch-chat message.

**Architecture:** Pure logic stays in unit-tested classes (`VaultLifecycle`, `Blacklist`, `RedemptionHandler`, `HelixClient`); Forge/Minecraft-coupled plumbing (`Config`, handlers, screen, bootstrap, commands) follows the existing untested-but-compiled pattern and is verified by a green `./gradlew test` build. Spec: `docs/superpowers/specs/2026-06-07-clear-toggle-claim-blacklist-design.md`.

**Tech Stack:** Java 17, Minecraft Forge 1.18.2, JUnit 5, Gradle 7.5.1 (must build with JDK 17), Gson, ForgeConfigSpec.

**Build/test prelude (run before every gradle command):**
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-17"
cd "/c/Users/Matth/Dev/chat-dwellers"
```

---

### Task 1: Fix vault-exit detection (prefix match)

**Files:**
- Modify: `src/main/java/com/chatdwellers/render/VaultLifecycle.java`
- Test: `src/test/java/com/chatdwellers/render/VaultLifecycleTest.java`

- [ ] **Step 1: Add failing tests for UUID-suffixed vault dims**

Append these tests inside `VaultLifecycleTest` (before the closing brace):

```java
    @Test
    void enteringInstancedVaultDimReportsEntered() {
        VaultLifecycle lc = new VaultLifecycle();
        assertEquals(Transition.NONE, lc.update("minecraft:overworld"));
        assertEquals(Transition.ENTERED_VAULT,
            lc.update("the_vault:vault_cd144c84-266e-41fa-93c9-46e97bf88602"));
    }

    @Test
    void leavingInstancedVaultDimReportsLeft() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault_cd144c84-266e-41fa-93c9-46e97bf88602");
        assertEquals(Transition.LEFT_VAULT, lc.update("minecraft:overworld"));
    }

    @Test
    void arenaAndOtherSideAreNotVault() {
        VaultLifecycle lc = new VaultLifecycle();
        assertEquals(Transition.NONE, lc.update("the_vault:arena"));
        assertEquals(Transition.NONE, lc.update("the_vault:the_other_side"));
    }

    @Test
    void stayingInSameInstancedVaultReportsNone() {
        VaultLifecycle lc = new VaultLifecycle();
        lc.update("the_vault:vault_aaa");
        assertEquals(Transition.NONE, lc.update("the_vault:vault_aaa"));
    }
```

- [ ] **Step 2: Run the new tests; verify they fail**

Run: `./gradlew test --tests "com.chatdwellers.render.VaultLifecycleTest"`
Expected: FAIL — `enteringInstancedVaultDimReportsEntered` expects `ENTERED_VAULT` but exact-match returns `NONE`.

- [ ] **Step 3: Switch `VaultLifecycle` to a prefix predicate**

Replace the body of `update` and add a helper. The full updated class:

```java
package com.chatdwellers.render;

/**
 * Pure detector of the player crossing in/out of a Vault dimension. In VH3 Remastered each
 * run is an instanced dimension {@code the_vault:vault_<uuid>}, so we match by the prefix
 * {@code the_vault:vault} (covers the template id and every instance) rather than an exact id.
 * Sibling dims {@code the_vault:arena} / {@code the_vault:the_other_side} are NOT vaults.
 * Fed the current dimension id (or null when there is no level) each client tick; reports the
 * edge. Correctness depends only on the LEFT_VAULT edge.
 */
public final class VaultLifecycle {

    /** Any dimension whose id starts with this is treated as "in a vault". */
    public static final String VAULT_PREFIX = "the_vault:vault";

    public enum Transition { NONE, ENTERED_VAULT, LEFT_VAULT }

    private String last = null;

    public synchronized Transition update(String currentDim) {
        boolean wasVault = inVault(last);
        boolean nowVault = inVault(currentDim);
        last = currentDim;
        if (nowVault && !wasVault) return Transition.ENTERED_VAULT;
        if (!nowVault && wasVault) return Transition.LEFT_VAULT;
        return Transition.NONE;
    }

    private static boolean inVault(String dim) {
        return dim != null && dim.startsWith(VAULT_PREFIX);
    }
}
```

- [ ] **Step 4: Run the full `VaultLifecycleTest`; verify all pass**

Run: `./gradlew test --tests "com.chatdwellers.render.VaultLifecycleTest"`
Expected: PASS (old exact-match tests still pass — `the_vault:vault` starts with the prefix).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chatdwellers/render/VaultLifecycle.java src/test/java/com/chatdwellers/render/VaultLifecycleTest.java
git commit -m "fix: detect instanced the_vault:vault_<uuid> dims by prefix"
```

---

### Task 2: `Blacklist` pure class

**Files:**
- Create: `src/main/java/com/chatdwellers/config/Blacklist.java`
- Test: `src/test/java/com/chatdwellers/config/BlacklistTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/chatdwellers/config/BlacklistTest.java`:

```java
package com.chatdwellers.config;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BlacklistTest {
    private static final String GENERIC = "{name} can't be a Vault Dweller.";

    @Test
    void customMessageIsReturnedForBlacklistedName() {
        Blacklist bl = Blacklist.of(
            List.of("technoblade=Technoblade can't possibly be a dweller since he never dies."),
            GENERIC);
        assertEquals("Technoblade can't possibly be a dweller since he never dies.",
            bl.messageFor("technoblade").orElseThrow());
    }

    @Test
    void matchIsCaseInsensitive() {
        Blacklist bl = Blacklist.of(List.of("Technoblade=nope"), GENERIC);
        assertEquals("nope", bl.messageFor("TECHNOBLADE").orElseThrow());
        assertEquals("nope", bl.messageFor("technoBlade").orElseThrow());
    }

    @Test
    void bareEntryUsesGenericTemplateWithNameSubstituted() {
        Blacklist bl = Blacklist.of(List.of("griefer123"), GENERIC);
        assertEquals("griefer123 can't be a Vault Dweller.",
            bl.messageFor("griefer123").orElseThrow());
    }

    @Test
    void nonMemberAndNullReturnEmpty() {
        Blacklist bl = Blacklist.of(List.of("technoblade=x"), GENERIC);
        assertTrue(bl.messageFor("someoneelse").isEmpty());
        assertTrue(bl.messageFor(null).isEmpty());
    }

    @Test
    void blankAndNamelessEntriesAreIgnored() {
        Blacklist bl = Blacklist.of(Arrays.asList("", "  ", "=onlymsg", null, "ok=yes"), GENERIC);
        assertTrue(bl.messageFor("").isEmpty());
        assertEquals("yes", bl.messageFor("ok").orElseThrow());
    }
}
```

- [ ] **Step 2: Run; verify it fails to compile (class missing)**

Run: `./gradlew test --tests "com.chatdwellers.config.BlacklistTest"`
Expected: FAIL — `Blacklist` does not exist.

- [ ] **Step 3: Implement `Blacklist`**

Create `src/main/java/com/chatdwellers/config/Blacklist.java`:

```java
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
```

- [ ] **Step 4: Run; verify pass**

Run: `./gradlew test --tests "com.chatdwellers.config.BlacklistTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chatdwellers/config/Blacklist.java src/test/java/com/chatdwellers/config/BlacklistTest.java
git commit -m "feat: add case-insensitive redemption Blacklist with per-entry messages"
```

---

### Task 3: `HelixClient.sendChatMessage`

**Files:**
- Modify: `src/main/java/com/chatdwellers/twitch/HelixClient.java`
- Test: `src/test/java/com/chatdwellers/twitch/HelixClientTest.java`

- [ ] **Step 1: Add failing test**

Append inside `HelixClientTest` (before the closing brace):

```java
    @Test
    void sendChatMessagePostsToChatMessagesEndpoint() throws IOException {
        RecordingHttp http = new RecordingHttp(200,
            "{\"data\":[{\"message_id\":\"m1\",\"is_sent\":true}]}");
        HelixClient h = build(http);
        h.sendChatMessage("bid-1", "bid-1", "hello chat");

        HttpRequest req = http.requests.get(0);
        assertEquals("POST", req.method());
        assertEquals(URI.create("https://api.twitch.tv/helix/chat/messages"), req.uri());
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""));
        assertEquals("Bearer TOKEN", req.headers().firstValue("Authorization").orElse(""));
    }
```

- [ ] **Step 2: Run; verify it fails**

Run: `./gradlew test --tests "com.chatdwellers.twitch.HelixClientTest"`
Expected: FAIL — `sendChatMessage` not defined.

- [ ] **Step 3: Implement `sendChatMessage`**

In `HelixClient.java`, add this method right after `updateRewardCost` (around line 146):

```java
    /**
     * Sends a chat message to {@code broadcasterId}'s channel as {@code senderId}. For our use
     * the broadcaster posts as themselves ({@code senderId == broadcasterId}). Requires the user
     * token to carry the {@code user:write:chat} scope.
     */
    public void sendChatMessage(String broadcasterId, String senderId, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("broadcaster_id", broadcasterId);
        body.addProperty("sender_id", senderId);
        body.addProperty("message", message);
        HttpRequest req = builder(BASE + "/chat/messages")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        JsonHttp.Response r = sendWithRefresh(req);
        if (r.status() < 200 || r.status() >= 300) {
            throw new IOException("sendChatMessage HTTP " + r.status() + ": " + r.body());
        }
    }
```

- [ ] **Step 4: Run; verify pass**

Run: `./gradlew test --tests "com.chatdwellers.twitch.HelixClientTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chatdwellers/twitch/HelixClient.java src/test/java/com/chatdwellers/twitch/HelixClientTest.java
git commit -m "feat: HelixClient.sendChatMessage (POST /helix/chat/messages)"
```

---

### Task 4: `RedemptionHandler` blacklist + Twitch-chat sink

**Files:**
- Modify: `src/main/java/com/chatdwellers/twitch/RedemptionHandler.java`
- Test: `src/test/java/com/chatdwellers/twitch/RedemptionHandlerTest.java`

- [ ] **Step 1: Update the test harness and add failing tests**

Edit `RedemptionHandlerTest.java`. Add imports at the top (after existing imports):

```java
import com.chatdwellers.config.Blacklist;
import java.util.function.Supplier;
```

Add two fields and update `setup()`/`newHandler()`. Replace the existing fields block + `setup()` + `newHandler()` with:

```java
    private DwellerPool pool;
    private FakeHelix helix;
    private FakeMojang mojang;
    private List<String> chatNotes;
    private List<String> twitchNotes;
    private Blacklist blacklist;

    @BeforeEach
    void setup() {
        pool = new DwellerPool();
        helix = new FakeHelix();
        mojang = new FakeMojang();
        chatNotes = new ArrayList<>();
        twitchNotes = new ArrayList<>();
        blacklist = Blacklist.of(List.of(), "{name} can't be a Vault Dweller.");
    }

    private RedemptionHandler newHandler() {
        return new RedemptionHandler(pool, helix, mojang, chatNotes::add, twitchNotes::add,
            () -> blacklist, 100);
    }
```

In `poolAtCapacityRefuses`, replace the inline handler construction line with:

```java
        RedemptionHandler h = new RedemptionHandler(pool, helix, mojang, chatNotes::add,
            twitchNotes::add, () -> blacklist, 1);
```

Add two new tests before the closing brace:

```java
    @Test
    void blacklistedNameIsRefundedMessagedAndNotEnqueued() throws IOException {
        blacklist = Blacklist.of(
            List.of("technoblade=Technoblade can't possibly be a dweller since he never dies."),
            "{name} can't be a Vault Dweller.");
        newHandler().handle(red("r1", "u1", "Alice", "Technoblade"));

        assertEquals(0, pool.size());
        assertEquals(1, helix.updates.size());
        assertEquals("r1", helix.updates.get(0)[0]);
        assertEquals("CANCELED", helix.updates.get(0)[1]);
        assertEquals(1, twitchNotes.size());
        assertTrue(twitchNotes.get(0).contains("never dies"), twitchNotes.get(0));
        assertTrue(chatNotes.isEmpty(), "blacklist path uses Twitch chat, not the local note");
    }

    @Test
    void blacklistShortCircuitsBeforeMojang() throws IOException {
        blacklist = Blacklist.of(List.of("technoblade=nope"), "{name} can't be a Vault Dweller.");
        // "technoblade" is not a FakeMojang-valid name, but the blacklist check runs first.
        newHandler().handle(red("r1", "u1", "Alice", "technoblade"));

        assertEquals(1, helix.updates.size());
        assertEquals("CANCELED", helix.updates.get(0)[1]);
        assertEquals(1, twitchNotes.size());
        assertTrue(chatNotes.isEmpty(), "Mojang invalid-name path must not run");
    }
```

- [ ] **Step 2: Run; verify it fails to compile**

Run: `./gradlew test --tests "com.chatdwellers.twitch.RedemptionHandlerTest"`
Expected: FAIL — constructor signature mismatch (`RedemptionHandler` has no 7-arg constructor).

- [ ] **Step 3: Implement the new constructor + blacklist check**

Replace the full body of `RedemptionHandler.java` with:

```java
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
```

- [ ] **Step 4: Run; verify pass**

Run: `./gradlew test --tests "com.chatdwellers.twitch.RedemptionHandlerTest"`
Expected: PASS (all old + new cases).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/chatdwellers/twitch/RedemptionHandler.java src/test/java/com/chatdwellers/twitch/RedemptionHandlerTest.java
git commit -m "feat: blacklist check in RedemptionHandler (refund + Twitch-chat message)"
```

---

### Task 5: `Config` — toggle, blacklist list, mutators

**Files:**
- Modify: `src/main/java/com/chatdwellers/config/Config.java`

(Plumbing — `ForgeConfigSpec` needs the Forge runtime, so verification is a clean compile/build.)

- [ ] **Step 1: Add the toggle to the `dwellers` section**

In `Config.java`, add a field with the other private static finals (after `MAX_POOL_SIZE`):

```java
    private static final ForgeConfigSpec.BooleanValue CLEAR_QUEUE_ON_VAULT_EXIT;
    private static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> BLACKLIST;
    private static final ForgeConfigSpec.ConfigValue<String> BLACKLIST_GENERIC;
```

Inside the `static {}` block, in the `dwellers` push (after the `MAX_POOL_SIZE` define, before `b.pop();`):

```java
        CLEAR_QUEUE_ON_VAULT_EXIT = b.comment(
            "When true, leaving a vault fulfills + drops the viewers who appeared this vault,",
            "keeping those who never appeared. When false the queue persists across vaults until",
            "you run /cd claim or /cd purge. Toggle live with /cd autoclear on|off.")
            .define("clearQueueOnVaultExit", true);
```

After the `dwellers` `b.pop();`, add a new section before `SPEC = b.build();`:

```java
        b.comment("Redemption blacklist").push("blacklist");
        BLACKLIST = b.comment(
            "Minecraft names that can't become dwellers. Format: 'mcname=message posted to Twitch chat'.",
            "An entry with no '=' uses blacklistGenericMessage. Matching is case-insensitive.")
            .defineList("entries",
                java.util.List.of("technoblade=Technoblade can't possibly be a dweller since he never dies."),
                o -> o instanceof String);
        BLACKLIST_GENERIC = b.comment(
            "Message for blacklist entries that have no message of their own; {name} is replaced.")
            .define("genericMessage", "{name} can't be a Vault Dweller.");
        b.pop();
```

- [ ] **Step 2: Add accessors + mutators**

Add these methods to `Config` (after `maxPoolSize()`):

```java
    public static boolean clearQueueOnVaultExit() { return CLEAR_QUEUE_ON_VAULT_EXIT.get(); }
    public static void setClearQueueOnVaultExit(boolean v) {
        CLEAR_QUEUE_ON_VAULT_EXIT.set(v); CLEAR_QUEUE_ON_VAULT_EXIT.save();
    }

    public static String blacklistGenericMessage() { return BLACKLIST_GENERIC.get(); }

    @SuppressWarnings("unchecked")
    public static java.util.List<String> blacklistEntries() {
        return new java.util.ArrayList<>((java.util.List<String>) (java.util.List<?>) BLACKLIST.get());
    }

    public static Blacklist blacklist() {
        return Blacklist.of(blacklistEntries(), blacklistGenericMessage());
    }

    private static String entryName(String entry) {
        String n = entry.contains("=") ? entry.substring(0, entry.indexOf('=')) : entry;
        return n.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static void addBlacklist(String name, String message) {
        java.util.List<String> entries = blacklistEntries();
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        entries.removeIf(e -> entryName(e).equals(key));
        entries.add(message == null || message.isBlank() ? name : name + "=" + message);
        BLACKLIST.set(entries); BLACKLIST.save();
    }

    public static boolean removeBlacklist(String name) {
        java.util.List<String> entries = blacklistEntries();
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        boolean removed = entries.removeIf(e -> entryName(e).equals(key));
        if (removed) { BLACKLIST.set(entries); BLACKLIST.save(); }
        return removed;
    }
```

(`Blacklist` is in the same package, so no import needed.)

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/chatdwellers/config/Config.java
git commit -m "feat: config for clearQueueOnVaultExit toggle + blacklist entries"
```

---

### Task 6: `TokenStore` + `TwitchAuthManager` scope upgrade

**Files:**
- Modify: `src/main/java/com/chatdwellers/twitch/TokenStore.java`
- Modify: `src/main/java/com/chatdwellers/twitch/TwitchAuthManager.java`

(Plumbing — verified by compile.)

- [ ] **Step 1: Add `scopes` to `TokenStore`**

In `TokenStore.java`, add a getter with the others (after `rewardId()`):

```java
    public String scopes()                        { return getString("scopes"); }
```

and a setter with the others (after `setRewardId`):

```java
    public void setScopes(String v)                    { config.set("scopes", v); persist(); }
```

- [ ] **Step 2: Add `user:write:chat` scope, persist granted scopes, expose checks**

In `TwitchAuthManager.java`:

Change the SCOPES constant:

```java
    private static final String SCOPES = "channel:manage:redemptions user:write:chat";
```

In `persistTokens(JsonObject body)`, add at the end of the method (after setting expiry):

```java
        store.setScopes(SCOPES);
```

Add two public methods (after `hasValidToken()`):

```java
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
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/chatdwellers/twitch/TokenStore.java src/main/java/com/chatdwellers/twitch/TwitchAuthManager.java
git commit -m "feat: request user:write:chat scope and track granted scopes for re-auth"
```

---

### Task 7: `ChatDwellersActions` — claim, autoclear, blacklist actions + status

**Files:**
- Modify: `src/main/java/com/chatdwellers/action/ChatDwellersActions.java`

(Plumbing — verified by compile.)

- [ ] **Step 1: Add the shared `claimShown` + setters + status line update**

In `ChatDwellersActions.java`, add these methods (after `purge()`):

```java
    /** Fulfills (on Twitch) and drops every viewer shown so far, keeping the un-shown ones
     *  queued. Shared by the manual /cd claim and by the vault-exit auto-clear path. */
    public static String claimShown() {
        DwellerPool pool = ChatDwellersClient.pool;
        List<PendingViewer> shown = pool.retainUnshown();
        int kept = pool.size();
        int fulfilled = 0;
        if (ChatDwellersClient.helixClient != null
            && ChatDwellersClient.tokenStore != null
            && !ChatDwellersClient.tokenStore.rewardId().isEmpty()) {
            String rewardId = ChatDwellersClient.tokenStore.rewardId();
            String broadcasterId = ChatDwellersClient.tokenStore.broadcasterId();
            for (PendingViewer v : shown) {
                if (v.redemptionId().startsWith("sim:")) continue;
                fulfilled++;
                final String redemptionId = v.redemptionId();
                CompletableFuture.runAsync(() -> {
                    try {
                        ChatDwellersClient.helixClient.updateRedemption(
                            broadcasterId, rewardId, redemptionId, "FULFILLED");
                    } catch (Exception e) {
                        ChatDwellers.LOGGER.warn("[ChatDwellers] failed to fulfill {} on claim: {}",
                            redemptionId, e.toString());
                    }
                });
            }
        }
        return "Claimed " + shown.size() + " shown viewer(s)"
            + (fulfilled > 0 ? " (fulfilling " + fulfilled + " on Twitch)" : "")
            + "; " + kept + " kept queued.";
    }

    public static String setAutoClear(boolean on) {
        Config.setClearQueueOnVaultExit(on);
        return "Auto-clear on vault exit: " + (on ? "ON" : "OFF") + ".";
    }

    public static String addBlacklist(String name, String message) {
        Config.addBlacklist(name, message);
        return "Blacklisted '" + name + "'.";
    }

    public static String removeBlacklist(String name) {
        return Config.removeBlacklist(name)
            ? "Removed '" + name + "' from blacklist."
            : "'" + name + "' was not blacklisted.";
    }

    public static String listBlacklist() {
        List<String> entries = Config.blacklistEntries();
        return entries.isEmpty() ? "Blacklist is empty." : "Blacklist: " + String.join(", ", entries);
    }
```

In `statusLine()`, change the `return` to include auto-clear:

```java
        return enabledState + " | Twitch: " + twitchState
            + " | auto-clear: " + (Config.clearQueueOnVaultExit() ? "ON" : "OFF")
            + " | reward: '" + Config.rewardName() + "' (" + Config.rewardCost() + " pts)"
            + " | queue (" + pool.size() + "): " + names;
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/chatdwellers/action/ChatDwellersActions.java
git commit -m "feat: claimShown + autoclear/blacklist actions, status shows auto-clear"
```

---

### Task 8: `VaultLifecycleHandler` — gate exit clear on the toggle

**Files:**
- Modify: `src/main/java/com/chatdwellers/render/VaultLifecycleHandler.java`

(Plumbing — verified by compile.)

- [ ] **Step 1: Replace the file to call the shared action and honor the toggle**

Replace the full body of `VaultLifecycleHandler.java` with:

```java
package com.chatdwellers.render;

import com.chatdwellers.ChatDwellersClient;
import com.chatdwellers.action.ChatDwellersActions;
import com.chatdwellers.config.Config;
import com.chatdwellers.render.VaultLifecycle.Transition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Watches the player's dimension each client tick. On leaving the Vault, clears the queue per
 * the {@code clearQueueOnVaultExit} toggle: when ON, viewers who appeared this vault are
 * fulfilled on Twitch and dropped (un-shown carry over); when OFF, the queue is left intact so
 * the same viewers keep appearing until /cd claim or /cd purge.
 *
 * <p>Registered explicitly from {@code ChatDwellers#clientSetup} (not via @Mod.EventBusSubscriber).
 */
public final class VaultLifecycleHandler {

    private static final VaultLifecycle LIFECYCLE = new VaultLifecycle();

    private VaultLifecycleHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        String dim = (level == null) ? null : level.dimension().location().toString();

        if (LIFECYCLE.update(dim) == Transition.LEFT_VAULT) {
            onVaultExit();
        }
    }

    private static void onVaultExit() {
        DwellerSkins.clearAll();
        if (!Config.clearQueueOnVaultExit()) return;
        if (Config.enabled()) {
            ChatDwellersActions.claimShown();
        } else {
            // Mod off: drop the shown viewers locally but don't touch Twitch.
            ChatDwellersClient.pool.retainUnshown();
        }
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/chatdwellers/render/VaultLifecycleHandler.java
git commit -m "feat: gate vault-exit queue clear on clearQueueOnVaultExit toggle"
```

---

### Task 9: Commands — `/cd claim`, `/cd autoclear`, `/cd blacklist`

**Files:**
- Modify: `src/main/java/com/chatdwellers/command/ChatDwellersCommand.java`

(Plumbing — verified by compile.)

- [ ] **Step 1: Register the new subcommands**

In `ChatDwellersCommand.onRegister`, extend the `tree` builder. Add these `.then(...)` clauses to the chain (e.g. right after the `reconnect` literal, before `cost`):

```java
            .then(Commands.literal("claim").executes(ChatDwellersCommand::claim))
            .then(Commands.literal("autoclear")
                .then(Commands.literal("on").executes(ctx -> setAutoClear(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> setAutoClear(ctx, false))))
            .then(Commands.literal("blacklist")
                .then(Commands.literal("list").executes(ChatDwellersCommand::blacklistList))
                .then(Commands.literal("remove")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ChatDwellersCommand::blacklistRemove)))
                .then(Commands.literal("add")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> blacklistAdd(ctx, ""))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> blacklistAdd(ctx,
                                StringArgumentType.getString(ctx, "message")))))))
```

- [ ] **Step 2: Add the handler methods**

Add these methods to `ChatDwellersCommand` (after `simulate(...)`):

```java
    private static int claim(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.claimShown());
        return 1;
    }

    private static int setAutoClear(CommandContext<CommandSourceStack> ctx, boolean on) {
        Notify.toast(ChatDwellersActions.setAutoClear(on));
        return 1;
    }

    private static int blacklistList(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.listBlacklist());
        return 1;
    }

    private static int blacklistRemove(CommandContext<CommandSourceStack> ctx) {
        Notify.toast(ChatDwellersActions.removeBlacklist(StringArgumentType.getString(ctx, "name")));
        return 1;
    }

    private static int blacklistAdd(CommandContext<CommandSourceStack> ctx, String message) {
        Notify.toast(ChatDwellersActions.addBlacklist(
            StringArgumentType.getString(ctx, "name"), message));
        return 1;
    }
```

(`StringArgumentType`, `CommandContext`, `CommandSourceStack`, `Commands`, `Notify`, `ChatDwellersActions` are all already imported.)

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/chatdwellers/command/ChatDwellersCommand.java
git commit -m "feat: /cd claim, /cd autoclear on|off, /cd blacklist add|remove|list"
```

---

### Task 10: Panel — Auto-clear toggle + Claim buttons

**Files:**
- Modify: `src/main/java/com/chatdwellers/client/ChatDwellersScreen.java`

(Plumbing — verified by compile.)

- [ ] **Step 1: Add the field + label helper**

In `ChatDwellersScreen.java`, add a field with the others (after `private Button toggleButton;`):

```java
    private Button autoClearButton;
```

Add a label helper next to `toggleLabel()`:

```java
    private TextComponent autoClearLabel() {
        return new TextComponent(Config.clearQueueOnVaultExit() ? "Auto-clear: ON" : "Auto-clear: OFF");
    }
```

- [ ] **Step 2: Add the bottom-row buttons**

In `init()`, replace the single Done button line:

```java
        this.addRenderableWidget(new Button(cx - 50, this.height - 32, 100, 20,
            new TextComponent("Done"), b -> onClose()));
```

with a three-button bottom row (Auto-clear / Done / Claim):

```java
        autoClearButton = this.addRenderableWidget(new Button(cx - 154, this.height - 32, 100, 20,
            autoClearLabel(), b -> {
                Notify.toast(ChatDwellersActions.setAutoClear(!Config.clearQueueOnVaultExit()));
                autoClearButton.setMessage(autoClearLabel());
            }));
        this.addRenderableWidget(new Button(cx - 50, this.height - 32, 100, 20,
            new TextComponent("Done"), b -> onClose()));
        this.addRenderableWidget(new Button(cx + 54, this.height - 32, 100, 20,
            new TextComponent("Claim shown"), b -> Notify.toast(ChatDwellersActions.claimShown())));
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/chatdwellers/client/ChatDwellersScreen.java
git commit -m "feat: panel Auto-clear toggle + Claim shown buttons"
```

---

### Task 11: `TwitchBootstrap` — wire Twitch-chat sink, blacklist, re-auth gate

**Files:**
- Modify: `src/main/java/com/chatdwellers/twitch/TwitchBootstrap.java`

(Plumbing — verified by compile + full test build.)

- [ ] **Step 1: Provide a Twitch-chat sink and pass blacklist into the handler**

In `TwitchBootstrap` constructor, replace the `this.redemptionHandler = new RedemptionHandler(...)` line with a sink + the new 7-arg constructor:

```java
        java.util.function.Consumer<String> twitchChat = msg -> {
            String bid = store.broadcasterId();
            if (bid.isEmpty()) {
                ChatDwellers.LOGGER.warn("[ChatDwellers] cannot send Twitch chat (no broadcaster id): {}", msg);
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    helix.sendChatMessage(bid, bid, msg);
                } catch (Exception e) {
                    ChatDwellers.LOGGER.warn("[ChatDwellers] failed to send Twitch chat: {}", e.toString());
                }
            });
        };
        this.redemptionHandler = new RedemptionHandler(pool, updater, nameResolver,
            TwitchBootstrap::sendChat, twitchChat, Config::blacklist, Config.maxPoolSize());
```

- [ ] **Step 2: Re-auth when the stored token lacks the new scope**

In `start()`, replace:

```java
            if (!auth.hasValidToken()) {
                try { auth.startDeviceCode().get(); } catch (Exception e) { return; }
            }
```

with:

```java
            if (!auth.hasValidToken()
                || !auth.hasGrantedScopes(TwitchAuthManager.requiredScopes())) {
                try { auth.startDeviceCode().get(); } catch (Exception e) { return; }
            }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/chatdwellers/twitch/TwitchBootstrap.java
git commit -m "feat: wire Twitch-chat sink + blacklist into handler; re-auth on scope upgrade"
```

---

### Task 12: Full test build + version bump

**Files:**
- Modify: `build.gradle:7`

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — all suites green (`VaultLifecycleTest`, `BlacklistTest`, `HelixClientTest`, `RedemptionHandlerTest`, `DwellerPoolTest`, and the rest).

- [ ] **Step 2: Bump the mod version**

In `build.gradle`, change:

```groovy
version = '1.0.10'
```

to:

```groovy
version = '1.1.0'
```

- [ ] **Step 3: Build the jar**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; jar at `build/libs/chatdwellers-1.1.0.jar`.

- [ ] **Step 4: Commit**

```bash
git add build.gradle
git commit -m "chore: bump version to 1.1.0"
```

---

## Self-Review

**Spec coverage:**
- Feature 1 (vault-exit fix) → Task 1. ✓
- Feature 2 (clear-on-exit toggle: config, handler gate, runtime control) → Tasks 5, 8, 9 (command), 10 (panel), 7 (status). ✓
- Feature 3 (stream-end claim, shared logic) → Task 7 (`claimShown`), 8 (reused at exit), 9 (command), 10 (button). ✓
- Feature 4 (blacklist + Twitch chat: Blacklist, config, handler, sendChatMessage, scope+reauth, runtime cmds) → Tasks 2, 3, 4, 5, 6, 9, 11. ✓
- Tests called out in the spec → Tasks 1, 2, 3, 4 (pure logic) + Task 12 full run. ✓
- Release note (re-auth for new scope) → handled at runtime by Task 11; user-facing note carried in the spec's Release section.

**Placeholder scan:** none — every code step is concrete.

**Type/name consistency check:**
- `RedemptionHandler` 7-arg constructor `(pool, helix, mojang, chatNote, twitchChat, Supplier<Blacklist>, maxPoolSize)` is defined in Task 4 and used identically in Task 4 tests and Task 11 (`Config::blacklist` is a `Supplier<Blacklist>`). ✓
- `Blacklist.of(List<? extends String>, String)` / `messageFor(String): Optional<String>` consistent across Tasks 2, 4, 5. ✓
- `Config.blacklist()`, `blacklistEntries()`, `addBlacklist(name,message)`, `removeBlacklist(name)`, `clearQueueOnVaultExit()`, `setClearQueueOnVaultExit(boolean)` defined in Task 5, used in Tasks 7, 8, 9, 11. ✓
- `ChatDwellersActions.claimShown()/setAutoClear(boolean)/addBlacklist/removeBlacklist/listBlacklist` defined in Task 7, used in Tasks 8, 9, 10. ✓
- `HelixClient.sendChatMessage(broadcasterId, senderId, message)` defined in Task 3, used in Task 11. ✓
- `TwitchAuthManager.requiredScopes()/hasGrantedScopes(String)` defined in Task 6, used in Task 11. `TokenStore.scopes()/setScopes` defined in Task 6, used in Task 6 (`persistTokens`). ✓
- `VaultLifecycle.VAULT_PREFIX` defined in Task 1 (replaces `VAULT_DIM`); no other file references the old constant (handler uses only `update`/`Transition`). ✓
