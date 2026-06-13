# ChatDwellers — Vault-Exit Fix, Clear-on-Exit Toggle, Stream-End Claim & Blacklist

**Date:** 2026-06-07
**Status:** Approved (design)

## Summary

Four changes, the first of which is a real bug fix:

1. **Fix queue-not-clearing bug** — the vault-exit detection never fires in VH3
   Remastered, so the queue never clears. Match the vault dimension by prefix.
2. **Clear-on-exit toggle** — make the per-vault queue clearing switchable
   (`/cd autoclear on|off`), default ON. OFF = "show all stream": nobody is dropped
   until you say so.
3. **Stream-end claim** — `/cd claim` fulfills + drops the viewers who actually
   appeared and keeps the un-shown ones queued (the manual version of the on-exit
   clear, useful when auto-clear is OFF).
4. **Blacklist + Twitch-chat rejection** — block specific typed Minecraft names from
   becoming dwellers, refund the points, and post a custom message to **Twitch chat**
   (e.g. Technoblade → "Technoblade can't possibly be a dweller since he never dies.").

Part 2 (a difficulty / "harder dwellers" mode) is **out of scope** here — designed
separately after this ships.

## Background — the bug (root cause, evidence-backed)

The queue is supposed to clear at vault exit: `VaultLifecycleHandler.onVaultExit()`
calls `DwellerPool.retainUnshown()`, which drops every viewer who was *shown* this vault
(fulfilling them on Twitch) and keeps the un-shown ones. That logic is correct and
unit-tested. **The problem is `onVaultExit()` never runs.**

`VaultLifecycle` decides "am I in the vault" by exact-matching the dimension id against
the constant `VAULT_DIM = "the_vault:vault"`. But VH3 **Remastered** generates each vault
as an instanced in-memory `VirtualWorld` with a **per-run dimension id carrying a UUID
suffix**. Confirmed from the user's own play logs:

```
[01:25:05] Minimap updated server level id: ... for world
   ResourceKey[minecraft:dimension / the_vault:vault_cd144c84-266e-41fa-93c9-46e97bf88602]
```

So the live dimension is `the_vault:vault_<uuid>`, which never equals `"the_vault:vault"`.
Result: `ENTERED_VAULT`/`LEFT_VAULT` never fire → `onVaultExit()` never runs → the queue
never clears. **Skinning still works** because `DwellerSpawnTracker` keys off the entity
type (`the_vault:vault_fighter`), which is dimension-independent — exactly why the mod
"works" but the queue does not clear.

Supporting evidence: the `the_vault:vault` save folder has only a `data/` dir and **no
`region/`** (in-memory virtual world); across dozens of sessions the client only ever
logged `OVERWORLD`/`NETHER` render pipelines. The static `the_vault:vault` is the
template/registry dimension; the player never actually stands in it.

## Feature 1 — Fix vault-exit detection

`VaultLifecycle` treats a dimension as "in vault" when its id **starts with**
`the_vault:vault`. This matches both the template (`the_vault:vault`) and every runtime
instance (`the_vault:vault_<uuid>`), and correctly **excludes** the sibling vault
dimensions `the_vault:arena` and `the_vault:the_other_side` (dwellers don't run there).

- Replace the exact-equality checks in `VaultLifecycle.update` with a prefix predicate
  `isVault(dim) = dim != null && dim.startsWith("the_vault:vault")`. The `Transition`
  state machine (last-dim memory, enter/leave/none edges, null-level → LEFT) is unchanged.
- Keep `VAULT_DIM` as the prefix constant (rename intent documented in a comment).

**Edge case (accepted):** going directly vault→vault without an intervening non-vault
dimension would read as staying-in-vault (no LEFT edge). In practice the player always
returns to the overworld between vaults, so this does not occur; noted for future work.

## Feature 2 — Clear-on-exit toggle

### Config
Add `clearQueueOnVaultExit` boolean to the `dwellers` section, default `true`, with
`clearQueueOnVaultExit()` getter and a saving setter `setClearQueueOnVaultExit(boolean)`
(mirrors the existing `enabled` pattern).

### Behavior
`VaultLifecycleHandler.onVaultExit()`:
- **Always** `DwellerSkins.clearAll()` — the vault's dweller entities are gone regardless
  of the toggle, so clearing the entity→skin map is pure cleanup.
- If `Config.clearQueueOnVaultExit()` is **ON** → run the claim (Feature 3 shared logic):
  fulfill + drop shown viewers, keep un-shown.
- If **OFF** → stop. The queue (shown + un-shown) and the Twitch redemptions are left
  untouched, so the same viewers keep reappearing as dwellers in the next vault until a
  manual `/cd claim` or `/cd purge`.

### Runtime control (set per-stream, flip per-vault)
- Command: `/cd autoclear on|off` (also reflected in `/cd status`, e.g. `auto-clear: ON`).
- Panel: a toggle button in `ChatDwellersScreen`.
- Logic: `ChatDwellersActions.setAutoClear(boolean)` / `autoClearStatus()`.

A single persisted setting flip-able at runtime satisfies both "set it for the whole
stream" and "change it between vaults."

## Feature 3 — Stream-end claim (`/cd claim`)

Extract the fulfill-and-drop-shown logic into a shared action so both the on-exit path
and the manual command use one implementation.

- `ChatDwellersActions.claimShown()`:
  - `List<PendingViewer> shown = pool.retainUnshown()` (drops shown, keeps un-shown).
  - For each shown viewer with a real (non-`sim:`) redemption → mark **FULFILLED** on
    Twitch (async, per-viewer error handling identical to the current `onVaultExit`).
  - Returns a short result string, e.g. `Claimed N shown viewer(s); M kept queued.`
- `VaultLifecycleHandler.onVaultExit()` (when auto-clear ON) calls `claimShown()` instead
  of duplicating the Twitch loop. (The handler keeps using `ChatDwellersClient` statics;
  `claimShown()` is the shared body.)
- Surfaced as `/cd claim` (toasts the result) + a **Claim shown** panel button.

This is the "show all stream, then claim once at the end" workflow: leave auto-clear OFF
during the stream so a stable set of viewers persists as dwellers, then `/cd claim` at the
end to fulfill everyone who appeared while keeping un-shown redeemers for next time.

## Feature 4 — Blacklist + Twitch-chat rejection

### Matching & decisions (confirmed with user)
- Match against the **typed Minecraft name** (`PendingRedemption.userInput()`),
  case-insensitive.
- On match: **refund** the points (`CANCELED`, consistent with the invalid-name path) and
  **do not enqueue**.
- The rejection message is posted to **Twitch chat** so viewers see it.

### `Blacklist` (new pure class, unit-tested)
`com.chatdwellers.config.Blacklist` built from the config list.
- `Optional<String> messageFor(String mcName)` — case-insensitive lookup; returns the
  entry's custom message, or the generic template (with `{name}` substituted) if the entry
  has no custom text, or empty if the name isn't blacklisted.
- Parses entries of the form `mcname=custom message`. An entry with no `=` uses the generic
  template. Names compared via `toLowerCase(Locale.ROOT)`.

### Config
- `blacklist` string list in a new `blacklist` section. Default ships one entry:
  `technoblade=Technoblade can't possibly be a dweller since he never dies.`
- `blacklistGenericMessage` string, default `{name} can't be a Vault Dweller.` — used for
  entries without a custom message.
- Saving mutators so the runtime commands persist: `addBlacklist(name, message?)`,
  `removeBlacklist(name)`, `blacklistEntries()`.

### Redemption flow
`RedemptionHandler` gains a `Blacklist` and a `twitchChat` sink (separate from the local
`chatNote` toast sink). At the **top** of `handle(PendingRedemption r)`, before enqueue /
Mojang lookup:
```
Optional<String> msg = blacklist.messageFor(r.userInput());
if (msg.isPresent()) {
    helix.updateRedemption(r.id(), "CANCELED");   // refund
    twitchChat.accept(msg.get());                 // post to Twitch chat
    return;                                        // never enqueued
}
```

### Twitch chat sending (new capability)
- **Scope:** add `user:write:chat` to `TwitchAuthManager.SCOPES` (now
  `"channel:manage:redemptions user:write:chat"`, space-separated; URL-encoded as today).
- **Endpoint:** `HelixClient.sendChatMessage(broadcasterId, senderId, message)` →
  `POST https://api.twitch.tv/helix/chat/messages` with body
  `{"broadcaster_id":…, "sender_id":…, "message":…}`. `sender_id == broadcasterId` (the
  streamer posts as themselves). Non-2xx → `IOException` (logged, not fatal).
- **Wiring:** `TwitchBootstrap` provides the `twitchChat` sink:
  `msg -> CompletableFuture.runAsync(() -> helix.sendChatMessage(bid, bid, msg))` with a
  warn-on-failure catch. Distinct from `sendChat` (the local toast/log notice sink).
- **Re-auth handling (self-healing scope upgrade):**
  - `TokenStore` stores the granted `scopes` string (new field in the secret toml).
  - `persistTokens` writes the current `SCOPES` on every successful grant/refresh-grant.
  - `TwitchAuthManager.hasGrantedScopes(required)` compares stored vs required.
  - `TwitchBootstrap.start()` triggers the device-code activation popup when
    `!auth.hasValidToken() || !auth.hasGrantedScopes(SCOPES)`. So upgrading to this build
    re-prompts the link **once**; after granting, the new token carries both scopes and the
    stored scopes match thereafter. Any future scope change is handled the same way.

### Runtime blacklist commands (confirmed: include now)
- `/cd blacklist add <name> [message...]` → `Config.addBlacklist`, toast confirmation.
- `/cd blacklist remove <name>` → `Config.removeBlacklist`, toast.
- `/cd blacklist list` → toast the current entries.
- Brigadier: `name` is `StringArgumentType.word()`, optional `message` is
  `StringArgumentType.greedyString()`.
- (Panel UI for the blacklist is out of scope this round; commands + config file cover it.)

## Error handling

| Situation | Handling |
|---|---|
| Vault-exit missed (crash mid-vault) | Queue persists; next vault enter is harmless; `/cd purge` is the manual reset. |
| Auto-clear OFF, queue grows unbounded | Bounded by `maxPoolSize`; `/cd claim` / `/cd purge` drain it. |
| Per-viewer fulfill fails during claim | Logged per-viewer; other viewers unaffected; viewer dropped locally regardless. |
| `sendChatMessage` fails (no scope / network) | Logged warn; redemption is still refunded. Missing scope is prevented by the re-auth gate. |
| Token lacks `user:write:chat` after upgrade | `start()` re-prompts the device-code popup once before connecting. |
| Blacklisted name also invalid on Mojang | Blacklist check is first, so it wins (refund + message); Mojang never consulted. |

## Testing

- **`VaultLifecycleTest`** — add cases: enter/leave with `the_vault:vault_<uuid>`; staying
  across two different UUID vaults via overworld in between; `the_vault:arena` and
  `the_vault:the_other_side` are **not** treated as vault.
- **`BlacklistTest`** (new) — custom-message hit; generic-fallback hit (`{name}` substituted);
  case-insensitive match; non-member → empty; entry parsing (`name=msg` vs bare `name`).
- **`RedemptionHandlerTest`** — blacklisted typed name → `CANCELED` + message sent + not
  enqueued; blacklist wins over Mojang; existing cases updated for the new constructor params.
- **`HelixClientTest`** — `sendChatMessage` request shape (URL, body fields, 401-refresh
  path consistent with the other methods).
- **`DwellerPoolTest`** — already covers retain/keep; no change needed for claim (it reuses
  `retainUnshown`).
- Config defaults: a small test that the default blacklist contains the Technoblade entry
  and that `Blacklist` built from defaults returns its custom message.

## Release

After green tests: bump `version` in `build.gradle`, build with JDK 17, cut a GitHub
release via the existing workflow. **Note for the user:** this build adds the
`user:write:chat` Twitch scope, so on first launch it will re-show the activation link —
re-authorize once so the blacklist messages can post to chat.
