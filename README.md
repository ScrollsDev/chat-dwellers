# ChatDwellers

A client-side Minecraft Forge mod for **Vault Hunters Third Edition - Remastered** that skins Vault Dwellers as your Twitch viewers when they redeem a channel-point reward and type their Minecraft username. The next wave of dwellers that spawns near you wears their skin (full overlay layers + correct slim/classic arms) with their Twitch name floating above.

![status](https://img.shields.io/badge/Minecraft-1.18.2-blue) ![status](https://img.shields.io/badge/Forge-40.2.0%2B-orange) ![status](https://img.shields.io/badge/side-CLIENT-green)

## What you need

- Minecraft **1.18.2**
- Forge **40.2.0+** (the version VH3 Remastered ships with)
- The **Vault Hunters Third Edition - Remastered** modpack (anything below 1.0 untested)
- A Twitch channel with **Affiliate** or **Partner** status (channel points are gated to those tiers)

That's it. You do **not** need to register a Twitch developer app — the mod ships with one baked in.

## Install

1. Download `chatdwellers-1.0.0.jar` from the [latest release](https://github.com/ScrollsDev/chat-dwellers/releases/latest).
2. Drop it into the `mods/` folder of your VH3 Remastered instance.
   - CurseForge default: `~/Documents/curseforge/minecraft/Instances/Vault Hunters Third Edition - Remastered/mods/` (macOS) or `%USERPROFILE%\Documents\curseforge\minecraft\Instances\Vault Hunters Third Edition - Remastered\mods\` (Windows).
3. Launch the modpack once so the config files get generated, then quit Minecraft.

## One-time Twitch setup

1. Launch Minecraft and load any single-player world.
2. Within ~5 seconds the in-game chat will show:
   ```
   [ChatDwellers] Authorize Twitch: visit https://twitch.tv/activate and enter code XXXXXX
   ```
3. Open the URL, sign in as your **streamer account** (the one whose channel-point reward you want to manage), paste the 6-character code, click Authorize.
4. Within another ~5 seconds you'll see:
   ```
   [ChatDwellers] Twitch authorization successful.
   [ChatDwellers] Reward 'Become a Vault Dweller' created.
   ```
5. The reward is now visible in your channel-points panel.

That's it — viewers can redeem.

### Advanced: using your own Twitch app

The mod ships with a default Client ID for an app owned by the maintainer. If you'd rather use your own — for example to keep your own rate-limit budget separate, or because you're publishing your own fork — register a Twitch app at <https://dev.twitch.tv/console/apps> (Public client type, redirect URL `http://localhost`), copy its Client ID into `twitchClientId` in `chatdwellers-local.toml`, and run `/cd reconnect`. The default app is published as a **Public** OAuth client, which means its Client ID is intentionally not a secret — your authorization is per-broadcaster regardless of which app's ID is used.

## Commands

All commands work as `/chatdwellers <sub>` or the shorter alias `/cd <sub>`.

| Command | What it does |
|---|---|
| `/cd help` | List all subcommands. |
| `/cd status` | Show on/off, Twitch connection state, reward, and the current queue. |
| `/cd on` / `/cd off` | Master enable/disable (persists to config). Turn off when joining a server that has its own similar plugin. |
| `/cd purge` | Refund all pending Twitch redemptions and clear the local queue. |
| `/cd reconnect` | Re-run authorization and reopen the EventSub WebSocket. Useful after editing config. |
| `/cd simulate <twitch> <mc>` | Inject a fake viewer locally without touching Twitch — handy for testing. |

## Config

`config/chatdwellers-local.toml` (user-edited):

```toml
[chatdwellers]
enabled = true            # master toggle; false skips the entire Twitch+skin pipeline

[twitch]
twitchClientId = "<default ChatDwellers app id>"   # override only if you registered your own Twitch app
rewardName = "Become a Vault Dweller"
rewardCost = 500
rewardPrompt = "Type your Minecraft username"

[dwellers]
radiusBlocks = 15         # skin only dwellers spawning within this many blocks of you
groupWindowSeconds = 2    # dwellers spawning within this many seconds share one viewer
nametagFormat = "{twitch}"  # tokens: {twitch}, {mc}
nametagYOffset = 0.3      # blocks above default; raise to clear health-bar overlays
maxPoolSize = 100         # pending viewers beyond this are refused (refunded)
```

`config/chatdwellers-secret.toml` is auto-managed (OAuth tokens, broadcaster ID, reward ID). Do not edit by hand. It's already covered by gitignore so it won't accidentally get committed.

## How it works (short version)

1. Viewer redeems the reward and types a Minecraft username.
2. Twitch's EventSub WebSocket delivers the redemption to the mod within ~1 second.
3. The mod validates the MC username with Mojang. Bad names are auto-refunded with a chat note. Duplicates and over-capacity are also auto-refunded.
4. Valid redemptions go into a FIFO queue (kept UNFULFILLED on Twitch — Twitch is the durable source of truth).
5. When a Vault Dweller spawns within `radiusBlocks` of you and a queued viewer is available, the dweller's `skin` field is populated with the viewer's `SkinProfile`, Vault's renderer naturally picks the right `PlayerModel` (slim/classic), and the Twitch nametag floats above.
6. The redemption gets marked FULFILLED on Twitch only at the moment the dweller is tagged — points are spent exactly when the viewer "appears" in your world.

## Server play

ChatDwellers is **client-side only**. On a multiplayer server, the mod still tries to set the dweller's skin locally on your client — but the server doesn't know, so other players won't see what you see. If the server already runs a similar plugin, turn ChatDwellers off with `/cd off` (or set `enabled = false`) before joining; turn it back on when you return to your own world.

## Known limitations (v1)

- Single-player only on the rendering side; multiplayer servers need their own implementation if they want skins visible to everyone.
- One reward per mod install. Renaming the reward in the Twitch dashboard after creation will desync the mod from the reward and you'll need to delete the reward and run `/cd reconnect`.
- The mod won't adopt a pre-existing reward of the same title — delete that one first and let the mod create its own.
- A redemption that was tagged in a previous Minecraft session but failed to be marked FULFILLED on Twitch (e.g. network blip) will be re-fetched on next launch and tagged again. Viewer gets one extra dweller for the same points. Rare edge case.

## License

All rights reserved.

## Acknowledgements

Built on the visible-by-design Twitch developer ecosystem: Helix REST, EventSub WebSocket, Device Code OAuth. Skin rendering relies on the `iskallia.vault.util.SkinProfile` API that the Vault Hunters team built into their `FighterRenderer` — reused via reflection rather than reimplemented.
