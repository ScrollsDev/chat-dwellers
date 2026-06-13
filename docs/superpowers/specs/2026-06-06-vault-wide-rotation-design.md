# ChatDwellers — Vault-Wide Rotation, Auth Popup & Live Cost

**Date:** 2026-06-06
**Status:** Approved (design)

## Summary

Three user-requested changes to ChatDwellers, plus the cleanup they enable:

1. **Auth popup screen** — replace the unclickable activation link in chat with an
   in-game GUI offering Copy / Yes-open / No buttons.
2. **Whole-vault viewer rotation** — replace the single-group skinning model with a
   rotation that skins *every* dweller in the vault by cycling through the queued
   viewers, with carry-over of un-shown viewers between vaults and fulfillment only
   for viewers who actually appeared.
3. **`/cd cost <n>`** — change the channel-point cost live without a reconnect.

Horde-mob skinning is explicitly **out of scope** this round (see Future Work).

## Background — current behavior

- `TwitchAuthManager.startDeviceCode()` posts a chat line
  `"[ChatDwellers] Authorize Twitch: visit <uri> and enter code <code>"`. The link is
  not clickable and spams chat.
- `DwellerPool` is a consume-on-dequeue `ArrayDeque<PendingViewer>`. `GroupWindow`
  decides when a *new group* of dwellers opens; on a new group one viewer is dequeued
  into `ChatDwellersClient.current` and every dweller in that time window wears that one
  viewer. The redemption is marked `FULFILLED` on Twitch when its group spawns.
- `DwellerSpawnTracker` only skins dwellers within `radiusBlocks` of the player.
- `rewardCost` exists in config but there is no way to change the live Twitch reward's
  price; editing the toml does not update an already-created reward.

## Feature 1 — Twitch auth popup `Screen`

### Behavior
`TwitchAuthManager` stops writing the activation link to chat. Instead it surfaces the
device-code data — `(verificationUri, verificationUriComplete?, userCode)` — to a new
client-side `TwitchActivateScreen` opened on the Minecraft render thread.

Screen layout (top to bottom):
- Title / instruction: *"Activate ChatDwellers on Twitch"* and *"Code: `XXXXXX`"*.
- A **Copy** button beside the instruction → copies the **activation URL** to the
  clipboard (the `verification_uri_complete`, which embeds the code, when Twitch returns
  it; otherwise the base `verification_uri`). For viewers who prefer to paste it into a
  browser themselves.
- A **Yes, open it** button → opens that same URL in the default browser via
  `Util.getPlatform().openUri(...)`. With the pre-filled URL the user only has to click
  Authorize on Twitch.
- A **No** button → closes the screen (covers an accidental popup). Polling continues in
  the background; the screen can be re-shown with `/cd reconnect`.

### URL selection
A small pure helper `activationUrl(uri, uriComplete)` returns `uriComplete` when present
and non-blank, else `uri`. Unit-tested in isolation. The device-code request will ask for
and parse `verification_uri_complete`; Twitch may omit it, in which case Yes/Copy fall back
to the base URL and the on-screen code is the manual path.

### Robustness
- If opening the browser fails, a chat fallback line with the URL + code is logged.
- A chat backstop line is still emitted (log level) so headless/no-screen contexts and
  the existing tests/log expectations keep working.

## Feature 2 — Whole-vault viewer rotation (core)

### Invariants
- **Fill the vault:** while the queue is non-empty, *every* dweller that spawns wears a
  queued viewer's skin + nametag. With fewer viewers than dwellers, viewers repeat.
- **Default when empty:** a dweller that spawns while the queue is empty gets the default
  skin, permanently.
- **Bound at spawn:** viewer assignment happens once, at the dweller's spawn, and is
  **never** applied retroactively. An already-spawned dweller never changes skin because
  someone later joined the queue.
- **Unshown priority:** a viewer who has not yet appeared this vault is picked before the
  rotation loops back to repeat an already-shown viewer. A mid-vault redeemer lands at the
  back of the *unshown* group (ahead of repeats, behind other unshown viewers).
- **No radius:** every dweller in the vault is eligible regardless of distance.

### Rotation data model (`DwellerPool` rework)
Replace the consume-on-dequeue deque with a rotation that tracks a `shown` flag per
viewer. Conceptually two ordered segments — *unshown* then *shown* — both FIFO by
redemption order.

- `enqueue(viewer, max)` — append to the unshown segment (dedup by `twitchId`, respect
  `maxPoolSize`). Returns `ADDED | DUPLICATE | FULL` as today.
- `nextForSpawn()` — returns the viewer to assign to a spawning dweller, or empty if the
  queue is empty:
  - if any unshown viewer exists, take the front unshown one, mark it `shown`, and move it
    to the back of the shown segment;
  - else rotate the shown segment: take the front, move it to the back.
- `purge()` — drain everything (used by `/cd purge`), refunding on Twitch as today.
- `snapshot()` / `size()` — for `/cd status`.
- `partitionShown()` — returns `(shown, unshown)` lists for the vault-exit step.
- `retainUnshown()` — drop all shown viewers, keep unshown ones in order, used at vault
  exit to build the carry-over set. (No flags to reset: shown viewers are removed, and the
  survivors are by definition unshown.)

Concrete example (2 viewers, 20 dwellers): assignments alternate
`v1, v2, v1, v2, …` → 10 each, every dweller skinned. A third viewer redeeming mid-vault
debuts on the next spawn (unshown priority), then folds into the rotation.

### `GroupWindow` and `current`
`GroupWindow` is **deleted**. `ChatDwellersClient.current` (single shared viewer) is
removed; assignment is now per-dweller. `ChatDwellersClient.init()` no longer builds a
`GroupWindow`.

### `DwellerSpawnTracker`
- On dweller `EntityJoinWorldEvent` in the client world (no radius check): call
  `pool.nextForSpawn()`. If empty → leave default skin. Else set name + skin via
  `DwellerSkins` / `VaultSkinSupport` exactly as today, and record the entity→viewer
  binding so the per-tick maintenance loop (unchanged) keeps re-asserting it.
- **Fulfillment moves out of the spawn handler.** Spawning a dweller no longer marks the
  redemption `FULFILLED`. Instead, mark the viewer `shown` in the rotation. Fulfillment
  happens at vault exit (below).

### Vault lifecycle (`VaultLifecycle` helper)
A client-tick watcher remembers the player's last dimension and fires only on a
**transition** in/out of `the_vault:vault` (confirmed dimension id). Correctness depends
only on the *exit* edge; the enter edge is tracked solely so we can recognize the exit.

- **Enter vault:** nothing to do. Carried-over unshown viewers from the previous vault are
  already queued (flags false); the next dweller spawn picks them up.
- **Leave vault** (transition `the_vault:vault` → anything else): partition the rotation
  into `shown` and `unshown`:
  - For each `shown` viewer with a real (non-`sim:`) redemption → mark **FULFILLED** on
    Twitch (async, per-viewer error handling mirroring the current fulfill pattern), then
    drop them from the rotation.
  - `unshown` viewers remain queued for the next vault (still pending on Twitch), retaining
    redemption order.
  - Clear all entity→viewer bindings and tagged skins (`DwellerSkins.clearAll()`); the
    vault and its dwellers are gone.

### Why fulfill at exit, not on show
The user wants points consumed only for viewers who actually appeared, and only once the
vault is over — so a viewer queued but never reached (vault ended first) keeps their points
and priority into the next vault. Fulfilling on first appearance would consume points for a
viewer even if the streamer immediately left; exit-time fulfillment matches the stated
intent.

## Feature 3 — `/cd cost <n>`

New subcommand `Commands.literal("cost").then(argument("amount", integer(1, 1_000_000)))`:
1. Validate range (Brigadier integer bounds).
2. Write `rewardCost` to config and save.
3. PATCH the live Twitch reward via a new `HelixClient.updateRewardCost(broadcasterId,
   rewardId, cost)` → `PATCH https://api.twitch.tv/helix/channel_points/custom_rewards
   ?broadcaster_id=…&id=…` with body `{"cost": n}`.
4. Report success/failure to chat. On API failure the config value is still saved and will
   be re-applied on the next `/cd reconnect` / reward sync.

`/cd status` already prints the cost, so no change there. `/cd help` gains a line.

## Feature 4 — No-chat interface (toasts + control panel)

The mod no longer writes to chat at all. Two mechanisms replace it:

### Toast notifications
A client helper `Notify.toast(message)` (in a new `com.chatdwellers.client` package) shows a
Minecraft **toast popup** (top-right card, auto-fading) via a small custom
`ChatDwellersToast`. Every place that currently writes to chat is rerouted here:
- `TwitchBootstrap.sendChat` → renamed `notify`, body shows a toast (+ `LOGGER.info`
  backstop for headless). All its callers (auth, rewards, EventSub errors, redemption
  handler) flow through it unchanged.
- Each command handler's `ctx.getSource().sendSuccess(...)` → `Notify.toast(...)`.
- The `[ChatDwellers] ` prefix is stripped (the toast title already reads "ChatDwellers").

### Control panel `Screen`
A new `ChatDwellersScreen` (in `com.chatdwellers.client`) is the primary UI. It shows live
state — enabled on/off, Twitch connection state, reward name + cost, and the current queue —
and offers buttons: **On/Off**, **Reconnect**, **Purge**, a **cost** `EditBox` + **Set**, and
a small **simulate** pair (`twitch`, `mc`) + **Add** for testing. Opened by typing `/cd`
with no arguments (and `/cd help`); `mc.execute(...)` defers `setScreen` to the render thread.

### Shared actions
To avoid duplicating logic between the typed commands and the panel buttons, the action
bodies move into a new `ChatDwellersActions` class (static methods: `toggle()`,
`reconnect()`, `purge()`, `setCost(int)`, `simulate(twitch, mc)`), each returning a short
result string. Both the commands (which toast the result) and the panel (which toasts and
refreshes) call these. The typed subcommands are **kept as a fallback** per user preference;
their feedback is a toast, never chat.

## Config changes

- **Remove:** `radiusBlocks` (no radius), `groupWindowSeconds` (no group window).
- **Keep:** `enabled`, `twitchClientId`, `rewardName`, `rewardCost`, `rewardPrompt`,
  `nametagFormat`, `nametagYOffset`, `maxPoolSize` (now the rotation cap).
- Removing keys is backward-compatible: stale keys left in a user's existing toml are
  ignored by ForgeConfigSpec.

## Error handling

| Situation | Handling |
|---|---|
| Browser open fails (Feature 1) | Chat fallback with URL + code; screen stays open. |
| `verification_uri_complete` absent | Fall back to base URL; on-screen code is the manual path. |
| Twitch reward PATCH fails (Feature 3) | Chat error; config saved; re-applied on reconnect. |
| Per-viewer fulfill fails at vault exit | Logged per-viewer; does not block other viewers; viewer dropped locally regardless (they were shown). |
| Vault-exit detection missed (e.g. crash) | Rotation persists; next vault enter is harmless; `/cd purge` remains the manual reset. |

## Testing

- **`DwellerPoolTest`** (rewritten for rotation): round-robin distribution across N
  dwellers; unshown-priority ordering; mid-rotation insertion of a new viewer; dedup/full
  results; `partitionShown` / carry-over semantics across a simulated vault exit;
  fulfill-only-if-shown partition.
- **`VaultLifecycleTest`** (new): enter/exit transition detection from a sequence of
  dimension ids; the fulfill-vs-keep partition on exit; no-op on non-vault dimensions.
- **`HelixClientTest`**: add `updateRewardCost` (request shape, query params, body, 401
  refresh path consistent with existing methods).
- **Auth URL helper**: unit test `activationUrl(uri, uriComplete)` selection logic.
- Screen rendering itself stays thin (UI wiring only); logic lives in tested helpers.

## Future work (explicitly out of scope)

- **Horde-mob skins/nametags.** Vault horde mobs (`OvergrownZombieEntity`,
  `WinterwalkerEntity`, `DeepDarkZombieEntity`, `CaveSkeletonEntity`, `VaultSpiderEntity`,
  guardians, …) have no `SkinProfile` and do not render as player models — only
  `FighterEntity` (the dweller) and `AncientCopperGolemEntity` do. Showing a player skin on
  them would require intercepting their rendering and drawing a player model in their place
  (cancel vanilla render in a Forge render event), with imperfect animation fidelity. A
  configurable entity-type list would drive which mobs opt in. Deferred to a separate
  project.

## Release

After implementation + green tests: bump the mod version, build the jar, and cut a GitHub
release via the existing release workflow so the user can drop it into the VH3 Remastered
`mods/` folder and test.
