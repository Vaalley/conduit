# MCTraveler Portal — Feature-Parity Inventory

Source: `mctraveler-portal` (TypeScript/Bun custom Minecraft proxy, protocol 773 / MC 1.21.10).
Clone inspected at `/private/tmp/claude-502/-Users-jam-Development-mctraveler-fabric/40ed3b14-3ba9-411c-8cbf-830e6c818c3c/scratchpad/mctraveler-portal`.
All file references below are relative to that repo root.

This document defines what "100% feature parity" means for the Fabric port (single server,
multiple overworlds replacing the two-backend topology). Sections: 1 Topology, 2 Per-feature
inventory, 3 Command framework, 4 Persistence, 5 Test inventory, 6 Proxy-only concerns,
7 Oddities/dead code found during inventory.

---

## 1. Topology summary

### Process layout

- **Proxy** listens on `PORT` (default 25565) — `main.ts`, `network/proxy.ts`.
- **Primary backend**: vanilla server, port 25566, working dir `minecraft-server/primary`, level-name `world`.
- **Secondary backend**: vanilla server, port 25567, working dir `minecraft-server/secondary`, level-name `last`.
- Backends are stock Mojang jars run in **offline mode**, `network-compression-threshold=-1`,
  `enforce-secure-profile=false` (`minecraft-server.ts:190-199`). The proxy does Mojang auth,
  compression, and encryption itself.
- A blessed TUI launcher (`launcher.ts`) supervises all three processes, plus a GitHub
  push-to-deploy webhook on port 9000 in production.

Config (`config.ts`): `kPort`, `kPrimaryPort`, `kSecondaryPort`, `kProtocolVersion` (773),
`kProtocolVersionString` ('1.21.10'), `kIsOnlineMode` (`ONLINE_MODE !== 'false'`),
`kIsProduction`, `kMcMemoryMax/Min` (8G prod / 512M-256M dev).

### Identity model (important)

- Client authenticates against Mojang at the proxy (online mode). Backends are offline-mode, so
  the backend computes **offline UUIDs** (`md5("OfflinePlayer:"+name)`, UUIDv3 — implemented in
  three places: `modules/PersistenceModule.ts:86`, `modules/OnlinePlayersModule.ts:49`,
  `network/player-tracking.ts:254`).
- The proxy therefore continuously **rewrites offline UUID → online UUID** in server→client
  packets (tab list, spawn entity, player remove) and injects the Mojang skin/properties, since
  the offline backend has none. This whole remapping layer is moot on Fabric (single online-mode
  server) — see §6.
- `TravelPatchFeature` remaps two usernames to different backend identities at login (§2.10).

### Player movement between worlds

- `/switch` (SwitchFeature) toggles the player between primary and secondary
  (`features/SwitchFeature.ts`). Flow in `network/connection-handler.ts:784-865`
  (`connectToBackend(port, isSwitch=true)`):
  1. Remove player from global tab list; clear protection state (hooks at :786-787).
  2. Close backend socket, wait `SERVER_SWITCH_DISCONNECT_DELAY` = 2000 ms
     (`network/packet-ids.ts:33`) so the backend saves playerdata.
  3. `SyncModule.api.syncPlayerData(offlineUuid, fromPort, toPort)` — copies selected NBT tags
     between the two servers' `playerdata/<offlineUuid>.dat` files (§2.14).
  4. Reconnect to the target backend, replay Handshake + Login Start (with the player's tracked
     UUID), silently re-run Login → Configuration (replaying cached Client Settings and Known
     Packs packets, answering config keep-alives) while `isSwitching` suppresses normal
     forwarding (`handleSwitchPacket`, :587-659).
  5. **Dimension-switch trick** (`handleSwitchJoinGame`, :661-780): the new Join Game packet is
     rewritten to claim an *alternate* dimension id, then a Respawn packet with the real
     dimension follows — forcing the client to drop all chunks and reload cleanly.
  6. Re-send self tab-list entry, global tab list, header/footer after 100 ms
     (`TAB_LIST_SEND_DELAY`).
- The last server a player was on is persisted (`lastServer: 'primary'|'secondary'` in
  `players/<uuid>.json`) and the player is **reconnected to that server on next login**
  (`connection-handler.ts:183,265`).

### State that is implicitly per-world today

Because the backends are separate servers with separate `playerdata`:

- **Synced across servers on `/switch`** (SyncModule `SYNC_TAGS`, `modules/SyncModule.ts:22-37`):
  `Inventory`, `EnderItems`, `equipment`, `XpLevel`, `XpP`, `XpTotal`, `foodLevel`,
  `foodExhaustionLevel`, `foodSaturationLevel`, `foodTickTimer`, `Health`, `Score`,
  `AbsorptionAmount`, `Attributes`.
- **Deliberately per-world** (never synced; deleted when seeding a fresh target file):
  `Pos`, `Rotation`, `Dimension`, `WorldUUID` (`SyncModule.ts:65-69`) — i.e. **position is
  per-world**; switching returns you to where you last stood in that world.
- Everything else in playerdata (advancements, statistics, recipe book, potion effects, vehicle,
  spawn point/respawn anchor, etc.) is also per-world simply because it is not in `SYNC_TAGS` —
  it silently stays behind on the source server. On Fabric, per-dimension position must be
  reproduced explicitly; shared inventory/XP/health come free from a single server, and the
  "everything else is per-world" behaviours (per-world spawn point, per-world advancements…)
  become **decisions to make** (they are current behaviour, arguably accidental).
- Note the sync failure mode: only one snapshot direction, best-effort, and if the backend
  hasn't flushed within 2 s, stale data is copied. A single-server port eliminates this class of
  bug entirely.

### Global (cross-world) state maintained by the proxy

- Online player registry, chat, private messages, join/leave messages, death message rebroadcast,
  away status, tab list (all players on both servers appear, with ping), MOTD/player-count,
  commands, regions (regions have a `world` field), admin flags, notepads.

---

## 2. Per-feature inventory

Feature registration order (`features/registry.ts`): Core, Motd, Away, Chat, Switch, TabList,
Notepad, Region, TravelPatch, Admin.

### 2.1 CoreFeature (`features/CoreFeature.ts`)

No behaviour of its own; enables the infrastructure modules: OnlinePlayers, Persistence, Message,
TabList, CommandsInjection, HeldItem, PlayerInfoBitflags, ProtectionHooks, Tps, XpOrbMerge.
(SyncModule is *not* enabled but is used directly by the connection handler; its `onEnable` is
empty so this is inconsequential.)

### 2.2 MotdFeature (`features/MotdFeature.ts`)

- Registers `MotdRequest` hook returning two Paint lines:
  - line 1 (green): `                  play.MCTraveler.eu` with **MCTraveler** bold
  - line 2 (gray): `       Celebrating 13 years of vanilla survival`
- Consumed by `main.ts:12-33` status response: joined as legacy-formatted strings; also reports
  `max: 20`, `online: <count>`, sample of first 12 players (name + uuid), a favicon placeholder
  string (`'data:image/png;base64,<data>'` — literally a placeholder, `main.ts:30`),
  `enforcesSecureChat: true`, version name `MCTraveler Proxy`, protocol 773
  (`network/handle-proxy-query.ts`).

### 2.3 AwayFeature (`features/AwayFeature.ts`)

Constants: away timeout 5 min, `/away` cooldown 3 s, checker interval 5 s (:11-13).

- Any of these counts as "interaction" and clears away status: join, chat, command, block
  break, block place, item use, movement (:58-64). On leave, state is cleaned up.
- Auto-away: every 5 s, players with no interaction for >5 min are marked away (:34-53).
- Broadcast messages (to **all** players, both worlds, via `MessageModule.api.broadcast`):
  - `p.gray` `<green username> is now away`
  - `p.gray` `<green username> is no longer away`
- `/away` command: marks the sender away immediately. Cooldown handling (:70-86) is quirky:
  the cooldown timestamp is set when a player *returns* from away; within 3 s of returning,
  `/away` either silently does nothing (when remaining rounds to exactly 3.0 s) or replies
  `ERROR You cannot use /away again for another <red seconds> seconds yet` (seconds rounded to
  0.1). Reproduce as-is or simplify deliberately.
- Away state is in-memory only; nothing persisted; no visual marker other than broadcasts.

### 2.4 ChatFeature (`features/ChatFeature.ts`)

- **Chat formatting**: `PlayerChat` hook returns `p` `<green name> <message>` (:30). Chat packets
  from clients are **never forwarded to the backend**; the proxy broadcasts the formatted
  message to every online player on both servers as a system-chat packet
  (`network/packet-routing.ts:63-77`). Consequence: chat is global, unsigned (no chat report
  path), and the backend server log never sees chat.
- **Join/leave messages** (:31-32):
  - join: `p.gray` `[<green +>] <green username> joined` (brackets dark gray)
  - leave: `p.gray` `[<red ->] <red username> left.` (note trailing period)
  - Vanilla backend join/leave (`multiplayer.player.joined/left` translates) are suppressed in
    two places (`connection-handler.ts:350-361`, `network/proxy.ts:23-44`).
  - The join message is queued at login and only broadcast once the player actually reaches Play
    (100 ms after Join Game), via `broadcastJoinMessage(player, delayUntilPlay=true)` +
    `flushPendingJoinMessages` (`network/player-tracking.ts:204-230`,
    `connection-handler.ts:445,468,533`).
- **Death message rebroadcast** (:14-28): `SystemChat` hook — if the NBT translate key starts
  with `death.`, block the original per-connection packet, dedupe identical messages for 1 s
  (JSON-stringified NBT as key), and broadcast the decoded component to **all** players (both
  worlds). Non-death system chat passes through.
- **Commands**:
  - `/shrug` → sends `¯\_(ツ)_/¯` as the player's chat; `/tableflip` → `(╯°□°）╯︵ ┻━┻` (:33-34).
    **Currently broken**: `OnlinePlayer.chat` is a no-op stub
    (`modules/OnlinePlayersModule.ts:94-96,216`), so these commands do nothing visible. Parity
    decision: implement the obvious intent (send as the player's chat line).
  - `/msg <target:onlinePlayer> <message:string...>` (:36-48): errors with
    `ERROR You can't send a message to yourself` for self-target; otherwise stores reply
    partners in both directions and sends
    `p` `<green sender> <gray →> <green target>: <message>` to the target, and the same text is
    returned to the sender (both see the identical line).
  - `/reply|/r <message:string...>` (:50-64): errors `ERROR You have no-one to reply to` and
    `ERROR The player you were messaging is no longer online`; otherwise same private-message
    format as `/msg` (note: reply does **not** update the reply map — only `/msg` does).
  - Vanilla `/tell` and `/w` are removed from the client-side command tree (aliases of the
    custom `msg`, `modules/CommandsInjectionModule.ts:298-308`) but still reach the backend if
    typed, since unmatched commands are forwarded (§3). On Fabric decide: alias them to `/msg`.

### 2.5 SwitchFeature (`features/SwitchFeature.ts`)

- `/switch` (no args): computes the other server (falls back to primary if
  `currentServerPort` unset), sends `p.gray` `Switching to <green Primary|Secondary>...`, then
  awaits `sender.switchServer(newPort)`; on throw sends
  `ERROR Failed to switch server: <error>`.
- All the heavy lifting is in the connection handler (§1). On Fabric this becomes a
  teleport between overworlds + scoreboard/protection state reset; the persisted `lastServer`
  choice ("spawn in the world you left from") must be kept.

### 2.6 TabListFeature (`features/TabListFeature.ts`)

- Header: `p` `             <green MCTraveler>             \n`
- Footer: `p` `\n<gray "          play.mctraveler.eu          ">\n<darkGray "TPS: "><yellow tps>`
  where tps = `TpsModule.api.getTps().toFixed(1)`.
- Note: TPS is the **proxy process's** event-loop tick estimate (§2.20), not backend MSPT. On
  Fabric, replace with real server TPS.
- Delivery mechanics (proxy-specific): TabListModule intercepts/overrides the backend
  header/footer packet and builds fresh ones on join/switch (§2.15).

### 2.7 NotepadFeature (`features/NotepadFeature.ts`)

A per-player, cross-world persistent notebook edited via a fake writable book.

- `/notepad`: if already editing → `p.gray` `You're already editing your notepad`. Otherwise
  loads saved pages (default single page:
  `This is your private note taking space. It's with you everywhere.`), then **injects a fake
  writable book** into the player's current held hotbar slot via a client-bound Set Slot packet
  (item id 1216 = writable_book in 1.21.10, components: `custom_name` = "Click to edit your
  notepad", `writable_book_content` with the pages; :18-70). The server never knows about the
  item.
- Player opens the book, edits, presses Done → client sends Edit Book; `EditBook` hook (:144-162)
  consumes it (never reaches the backend), parses pages, persists via
  `PersistenceModule.writeNotepadData(player.uuid, pages)`, replies `SUCCESS Notepad saved`
  (or `ERROR Failed to save notepad` on parse failure), and triggers an inventory resync.
- Inventory resync trick (:110-130): sends a synthetic Window Click (mode 2 hotbar-swap of the
  slot with itself, stateId 0) **to the server**, which makes the server reject and re-send the
  real slot contents, erasing the fake book client-side.
- Session cancellation: changing held item (`HeldItemChange` hook, which also tracks the held
  slot from the packet's int16) or clicking in the inventory (`InventoryClick`) cancels the
  session, resyncs, and sends `ERROR Your notepad editing session has been cancelled` (:132-174).
- Persistence: `notepad: string[]` in `players/<uuid>.json` (§4).
- Fabric note: the fake-item + resync mechanics disappear; open a real (server-side) book-edit
  UI instead, keeping the message texts and stored page format.

### 2.8 RegionFeature (`features/RegionFeature.ts`, 1072 lines) — largest feature

Player-owned protected cuboid regions with a live sidebar scoreboard, adventure-mode
enforcement, membership, flags, and sub-regions.

**Data model** (:24-66): `Region { title, start{x,z,y?}, end{x,z,y?}, world, members:Set<uuid>,
flags:Set<string>, subRegions:[], parentRegion? }`. Loaded from / saved to `regions.json`
(legacy format, §4). Default y-bounds on load: start-y 320, end-y −64 (:82-91); y values equal
to those defaults are omitted on save (:132-133). Worlds are strings: `world`, `world_nether`,
`world_the_end`, `last`, `last_nether`, `last_the_end` (`util/world.ts` — secondary server ⇒
`last*`; dimension from the player's current dimension name).

**Region lookup** (:154-180): axis-aligned containment (x/z/y inclusive, min/max normalised),
recursing into sub-regions — deepest match wins.

**Membership/permissions** (:410-419): can modify iff resident OR region has `PUBLIC` flag.
Admins (PersistenceModule `isAdmin`) bypass management-command checks but NOT protection itself.

**Move tracking** (:444-449): on `PlayerMove` (client position packets), computes region at
floored feet position and updates "current region".

**Scoreboard sidebar** (proxy-crafted packets, :186-332): objective named `region`, displayed in
sidebar; title = region title truncated to 20 chars, green bold; entries:
- `@residents` → bold "Residents", score = member count
- `@break` → 30-space dark-gray strikethrough separator, score = memberCount+1
- one entry per member uuid → username truncated to 20, white for self / gray for others,
  score 0..n, number format BLANK (scores hidden). Members whose username can't be resolved
  (online lookup, then uuid-cache) are skipped.
Entering a region shows/updates the board (unless flag `NO_SCOREBOARD`); leaving hides it;
switching regions removes only members not in the new region (:373-399). Objective is
re-created once per connection (remove+create) to survive server switches (:244-255).

**Adventure-mode protection** (:334-371): when a survival-mode player stands in a region they
cannot modify, the proxy sends a client-bound Game State Change (reason 3) to Adventure; on
exit (or membership gain) back to Survival. The player's *actual* gamemode is tracked from
Join/Respawn/Game State Change packets (`PlayerGameModeChange` hook —
`connection-handler.ts:539-580,744-750`); protection only ever applies to actual-survival
players. State cleared on server switch (`ClearPlayerProtection`). On Fabric: no fake gamemode
needed — enforce via event cancellation, but note the *visual* effect today (block-break
animation suppressed client-side).

**Protection hook handlers** (all send `ERROR This area is protected by <red regionName>`):
- Block dig (:460-467) & block place (:469-476) & sign edit (:478-485): blocked at the *target
  block's* region unless `canModifyRegion`.
- Container click (:487-499): the region captured at container-**open** time is used; blocked
  unless resident / `PUBLIC` / `ENABLE_PUBLIC_CONTAINERS`.
- Item use (:501-508): blocked in the player's current region unless `canModifyRegion` (only
  fires when actually holding an item — `ProtectionHooksModule`).
- Entity interact (:510-523): in a non-modifiable region, unless `DISABLE_ANIMAL_PROTECTION`:
  `attack` always blocked; `interact`/`interact_at` blocked only while holding an item and
  without `ENABLE_PUBLIC_VILLAGER_TRADING` (empty-hand interaction, e.g. trading, allowed).

**Commands** (`/region` and `/rg` equivalent):
- `/rg` (no args) — help panel listing all subcommands (:1058-1067).
- `/rg rename <name:string...>` (:526-553): must stand in region; resident or admin; name regex
  `^[a-zA-Z0-9!_'?()#:,.+&@*\- ]{3,30}$` else `ERROR Invalid region name`; renames, saves,
  live-updates the scoreboard title of everyone in the region;
  `SUCCESS Renamed <green old> to <green new>`.
- `/rg add <target:onlinePlayer>` (:556-613): must stand in region; cap
  `ERROR Regions may only have 99 members`; sender must be resident/admin **or** resident of the
  parent region; duplicate →
  `ERROR <red name> is already a member of <red title>`; adds, saves, live-updates scoreboards
  and re-evaluates the added player's adventure-mode state;
  `SUCCESS <green name> has been added to <green title>`.
- `/rg remove <player:string-with-suggestions>` (:629-686): suggestions = current region's
  member usernames matching the typed prefix (:616-627); errors: not in region / not a member /
  `ERROR <red name> is not a member of <red title>` /
  `ERROR <red name> is the only member of <red title>` (can't empty a region); removes, saves,
  live-updates, re-evaluates removed player's protection;
  `SUCCESS <green name> has been removed from <green title>`.
- `/rg delete` (:689-724): must stand in region; resident or admin; `EMBASSY` regions refuse
  with `ERROR You must use <red /embassy delete> to delete an embassy` (note: **no `/embassy`
  command exists in the portal** — legacy); clears scoreboards of everyone inside; detaches from
  parent or root list; saves; `SUCCESS Deleted region <green title>`.
- `/rg start` (:727-740): records current position + world + server;
  `SUCCESS First point set!\n\nNow move over to the next point and do:\n<green /rg end>`.
  Errors if no tracked position yet: `ERROR Position not available yet, please move first`.
- `/rg end` (:743-858): validations, in order — must have started
  (`ERROR You must start first. Use /rg start`); position available; same world
  (`ERROR Regions may only be created in the same world.`); same server
  (`ERROR Regions may only be created on the same server. Use /rg start again.`); area
  = (|dx|+1)×(|dz|+1); `area <= 9` → `ERROR Region too small`; `area > 5000` and not admin →
  `ERROR Region too large (<n> blocks). Limit is 5000 blocks. Ask an admin to create it.`;
  overlap check against all regions' *corners* falling inside the new rect (recursing into
  sub-regions) → `ERROR Overlapping region <red title>!`; sub-region creation when both
  endpoints are in the same existing region (refused for `EMBASSY`
  `ERROR You cannot create a region inside an embassy`, for `ADMIN` flag
  `ERROR You cannot create a region inside a region with admin flag`, and when the sender isn't
  a resident of the parent `ERROR You are not a member of the parent region`); if only one
  endpoint is inside an existing region → overlap error. Creates region titled
  `<username>'s Place` with **y-bounds 255..15** (note: NOT the 320/−64 defaults used at load
  time — inconsistency to preserve or fix consciously), members={sender}, saves, clears the
  start marker, immediately sets it as current region (scoreboard appears);
  `SUCCESS Region <green title> created!\n\nYou can now rename the region:\n<green /rg rename <name>>`.
- `/rg flag <flag:string...>` (admin only) (:861-905): toggles a flag. Valid flags: `EMBASSY`,
  `NO_SCOREBOARD`, `ENABLE_EXPLOSIONS`, `ADMIN`, `ENABLE_PUBLIC_CONTAINERS`, `DISABLE_GATES`,
  `ENABLE_FIRE_DAMAGE`, `DISABLE_PLAYER_FALL_DAMAGE`, `ENABLE_PUBLIC_VILLAGER_TRADING`,
  `DISABLE_PUBLIC_REDSTONE_TRIGGERS`, `DISABLE_ANIMAL_PROTECTION`, `PUBLIC`. `EMBASSY` cannot
  be toggled (`ERROR You cannot toggle the embassy flag`). Invalid →
  `ERROR Invalid flag. Valid flags: <list>`. Replies `SUCCESS Flag <green FLAG> added/removed`.
  **Only** `NO_SCOREBOARD`, `PUBLIC`, `ENABLE_PUBLIC_CONTAINERS`,
  `ENABLE_PUBLIC_VILLAGER_TRADING`, `DISABLE_ANIMAL_PROTECTION`, `EMBASSY`, `ADMIN` are
  enforced anywhere in the portal; `ENABLE_EXPLOSIONS`, `DISABLE_GATES`, `ENABLE_FIRE_DAMAGE`,
  `DISABLE_PLAYER_FALL_DAMAGE`, `DISABLE_PUBLIC_REDSTONE_TRIGGERS` are accepted-but-inert
  (legacy of the pre-proxy plugin — a Fabric port could actually implement them; decide).
- `/rg flag` (admin only) (:908-937): lists all flags, enabled green then disabled red,
  comma-separated on a gray line.
- `/rg bounds <minY:int> <maxY:int>` (admin only) (:940-969): −64 ≤ y ≤ 320
  (`ERROR Y bounds must be between -64 and 320`), span ≥ 16
  (`ERROR Y bounds must be at least 16 blocks tall`); stores start.y=maxY end.y=minY;
  `SUCCESS Set Y bounds for <green title> to <white minY> - <white maxY>`.
- `/rg bounds` (admin only) (:972-986): shows `<green title> bounds: Y <white min> to <white max>`.
- `/rg locate <name:string...>` (admin only) (:989-1055): case-insensitive substring search over
  region titles AND member usernames (recursive). One result:
  `<yellow title> - <white centerX/~/centerZ>/<green server/dimension>`; multiple: header
  `Located regions (<yellow n>):` plus up to 10 lines
  ` - <yellow title> <gray centerX/centerZ/server/dimension>` and `<gray ...and N more>`.
  World mapping: `last*` ⇒ secondary else primary; contains `nether` ⇒ nether, `end` ⇒ end
  (:1024-1031).

**Lifecycle hooks**: PlayerLeave clears current-region cache (:440-442); ContainerOpen/Close
capture/release the open-container region (:451-458).

### 2.9 TravelPatchFeature (`features/TravelPatchFeature.ts`)

- Hardcoded profile remaps applied at online-mode login (`GetRemappedProfile` hook consumed at
  `connection-handler.ts:247-262`):
  - `DemonicNoodle` → username `travelcraft2012`, uuid `461789c5-4501-48a0-b47d-7574c9a7b9ec`
  - `AlsoJames` → username `iElmo`, uuid `be9482bb-6bcd-4df3-9cf4-9f1fb61c5e93`
- The player authenticates with Mojang under their real account, but the backend (and all portal
  state: tab list, persistence key, region membership, admin flag) sees the remapped
  username/UUID. Case-sensitive lookup. Log line: `Remapping <old> -> <new>`.
- On Fabric: equivalent is a login-time GameProfile swap.

### 2.10 AdminFeature (`features/AdminFeature.ts`)

- Admin flag lives in `players/<uuid>.json` (`isAdmin: true`) via PersistenceModule.
- `/op <target:onlinePlayer>` (:64-78): allowed if sender is admin **or** sender's username is
  literally `iElmo` (bootstrap backdoor, :65). Sets `isAdmin=true` for the target's uuid,
  caches uuid→username in `uuid-cache.json`, and writes the target into **both** backends'
  `ops.json` (level 4, `bypassesPlayerLimit:false`, keyed by the target's **offline** uuid)
  (:36-59). Messages: target gets `SUCCESS You are now an operator` (if not self); sender gets
  `SUCCESS Made <green name> an operator`. Not-admin → `ERROR You must be an admin to use this
  command`.
- `/deop <target:onlinePlayer>` (:80-93): admin-only (no iElmo backdoor). Sets `isAdmin=false`,
  removes from both ops.json files. Target (if not self) gets yellow
  `You are no longer an operator`; sender gets `SUCCESS Removed <yellow name> as operator`.
- Malformed ops.json is silently reset to `[]` (:16-26).
- Portal admin gating (`isPlayerAdmin`) controls: `/op`, `/deop`, region admin bypass,
  `/rg flag|bounds|locate`, >5000-block regions. Backend op level 4 grants vanilla commands on
  the backends (forwarded unmatched commands, §3).

### 2.11 OnlinePlayersModule (`modules/OnlinePlayersModule.ts`)

- Registry of online players keyed by (online) uuid, plus offlineUuid→uuid index.
  `OnlinePlayer` shape (:10-23): uuid, username, loginTime, id/name getters, `isOnline`,
  `offlineUuid`, `currentServerPort`, `currentDimension` (default `overworld`),
  `sendMessage(message)` (string|Paint|NBT-object → system chat packet, :31-47),
  `chat(message)` (**no-op stub**), `switchServer(port)` (delegates to registered switcher).
- Hooks registered (:186-246): `GetOnlinePlayers`, `TrackPlayerLogin` (creates + stores player;
  logs `+ player <name>[ (offline)]`), `TrackPlayerLogout` (logs `- player <name>`),
  `SetServerSwitcher`/`ClearServerSwitcher`.
- API: trackPlayerLogin/out, getOnlinePlayer, getPlayerByUsername (case-insensitive),
  getPlayerByOfflineUuid, getOnlinePlayers, getOnlineCount, isPlayerOnline, setPlayerDimension,
  generateOfflineUUID, socket accessors, switcher management.

### 2.12 PersistenceModule (`modules/PersistenceModule.ts`) — see §4 for storage detail

API: `cachePlayerUuid`, `getUsernameFromUuid`, `convertPlayerDataToOfflineUuid` (dead code —
renames Mojang-uuid playerdata to offline-uuid and force-sets Pos to hardcoded spawn
`16.5/71.0/-14.5`; never called, :112-144), `getPlayerLastServerName`/`setPlayerLastServerName`,
`trackPlayerLoginData`/`trackPlayerLogoutData` (dead code — timestamps/ip tracking, never
called), `readNotepadData`/`writeNotepadData`, `isPlayerAdmin`/`setPlayerAdmin`.

### 2.13 MessageModule (`modules/MessageModule.ts`)

- `broadcast(message, excludePlayer?)`: sendMessage to every online player (both worlds).
- `sendMessageToPlayer`: wraps player.sendMessage with error swallow.
- `sendSystemMessage(socket, nbt, isActionBar)`: **latent bug** — writes field `message` but the
  packet schema field is `content` (`defined-packets.gen.ts:137-143`); unused in production
  paths.

### 2.14 SyncModule (`modules/SyncModule.ts`)

Cross-server playerdata NBT sync described in §1. Reads/writes gzip NBT with prismarine-nbt;
target file seeded from source (minus Pos/Rotation/Dimension/WorldUUID) when absent; errors are
logged, not surfaced to the player. Keyed by **offline** uuid.

### 2.15 TabListModule (`modules/TabListModule.ts`)

Maintains the illusion of one server's tab list across two backends:

- `profilePropertiesMap`: uuid → Mojang properties (skin), fed from Login (`SetProfileProperties`
  hook, `connection-handler.ts:401`).
- `globalTabList`: uuid → {name, gamemode, latency, listed…} accumulated from backend
  player-info packets.
- Packet work: intercepts Player Remove to prune the global list (:56-83; also translating
  offline→online uuid); transforms Tab List Header/Footer packets, replacing content with the
  `TabListHeaderRequest`/`TabListFooterRequest` hook results (last registered hook wins,
  :315-328); builds synthetic Player Info Add packets (flags ADD|GAMEMODE|LISTED|LATENCY, with
  injected properties), Player Remove packets, and Header/Footer packets on demand (hooks
  `BuildPlayerInfoPacket`, `BuildPlayerRemovePacket`, `BuildTabListHeaderFooterPacket`,
  `RemovePlayerFromTabList`, `Set/GetProfileProperties`).
- Join/leave/switch tab-list choreography lives in `network/player-tracking.ts:133-202`
  (broadcastPlayerJoin/Leave, sendGlobalTabList, sendTabListHeaderFooter) and
  `connection-handler.ts:513-534` (100 ms after Join Game: send self entry, global list, header/
  footer, broadcast self to others, flush join message).
- **Behaviour to preserve on Fabric**: single unified player list; custom header/footer; players
  visible across worlds. Mechanism (packet rewriting) is moot.

### 2.16 CommandsInjectionModule (`modules/CommandsInjectionModule.ts`)

- Transforms the backend's Declare Commands packet: decodes the Brigadier node graph, removes
  vanilla nodes shadowed by portal commands (plus vanilla aliases `tell`/`w` when `msg` is
  registered), appends node trees generated from each registered command pattern (literals,
  integer, string single/greedy/quotable, player→string+`minecraft:ask_server` suggestions),
  prunes orphans, re-encodes (:274-440). Effect: clients see/complete portal commands natively.
- Intercepts client Tab Complete requests (packet 0x0e) for portal commands
  (`msg tell w rg region op deop` hardcoded list, :483): custom suggestion provider if the
  matched parser has one (e.g. region members), else online usernames matching the prefix;
  responds directly, never reaching the backend (:457-523).
- Fabric equivalent: register real Brigadier commands with suggestion providers — this whole
  module becomes declarative.

### 2.17 HeldItemModule (`modules/HeldItemModule.ts`)

- Tracks each player's held hotbar slot (client Held Item Change packet 0x34 → also fires
  `HeldItemChange` hook) and hotbar occupancy (parses server Set Slot & Window Items packets for
  window 0, slots 36-44) (:49-108). API: `isHoldingItem`, `getHeldSlot`, tracking cleared on
  leave.
- Exists only because a proxy can't see inventories; on Fabric read the live inventory.
  Behavioural consumers: protection empty-hand rules (§2.8), notepad slot placement.

### 2.18 PlayerInfoBitflagsModule (`modules/PlayerInfoBitflagsModule.ts`)

Server→client transforms:
- Player Info Update (0x44): rewrites offline→online uuids, injects Mojang properties (skins),
  strips `INITIALIZE_CHAT` when the payload is too small (offline backend has no chat session,
  :61-66), and, on latency updates, additionally sends a display-name update so the tab list
  shows `<green username> <darkGray [Nms]>` (:31-45, :185-188).
- Spawn Entity: rewrites the player uuid field offline→online (:226-242).
- Player Remove: same uuid rewrite (:244-274).
- Fabric parity: only the **ping-in-tab-list display name** (`name [123ms]`) is a user-visible
  feature to reproduce; the uuid/skin surgery is moot.

### 2.19 ProtectionHooksModule (`modules/ProtectionHooksModule.ts`)

Client→server interception translating packets into protection hooks (§2.8 consumes them):
- Block Dig (statuses 0=start, 2=finish only): on block, sends Acknowledge Block Change with the
  dig sequence id so the client doesn't ghost (:68-99).
- Block Place (position from packet), Sign Update, Window Click (only windowId ≠ 0 = containers),
  Use Item (only when holding an item), Use Entity (mouse 1=attack, 2=interact_at, else
  interact).
- Container open/close tracking: server Open Window / Close Window packets and client Close
  Window (0x12) → `ContainerOpen`/`ContainerClose` hooks.
- Fabric equivalent: block/entity/container events; the ack-packet dance disappears.

### 2.20 TpsModule (`modules/TpsModule.ts`)

Every 1 s computes `min(20, 20*1000/max(1000, elapsed))` and smooths
`tps = 0.9*tps + 0.1*sample` — i.e. an EWMA of the **proxy's** timer drift, always ≈20 unless
the Bun process stalls. `getTps()` feeds the tab footer. Replace with real server TPS/MSPT.

### 2.21 XpOrbMergeModule (`modules/XpOrbMergeModule.ts`)

- Intercepts server→client Spawn Entity for type 47 (experience orb). Buffers per client socket
  for 50 ms; merges consecutive orb spawns into a single orb whose `objectData` (xp value) is the
  sum; keeps the first orb's entity id/uuid/position; suppresses the individual spawns (:49-82).
- Pure client-side visual/perf tweak (server entities unaffected; later packets referencing the
  suppressed ids are simply ignored by the client). Fabric equivalent: actually merge orb
  entities server-side, or drop the feature deliberately.

### 2.22 Unused API-only modules

`ChatModule`, `CommandModule`, `PlayerPositionModule`, `PlayerInteractionModule`
(`modules/*.ts`) — parsing/callback registries that nothing registers with; not enabled by any
feature; equivalent live paths are in `network/packet-routing.ts`. **No behaviour to port.**

---

## 3. Command framework (`feature-api/command.ts`, `command-usage.ts`, `paint.ts`)

### 3.1 Syntax DSL & matching (`command.ts`)

- Patterns are template literals: `syntax`op ${syntax.onlinePlayer('target')}``. Parser types:
  - `syntax.string` — one word; `.rest('name')` — greedy remainder;
    `.withSuggestions(name, (partial, player) => string[])` — custom tab-complete.
  - `syntax.onlinePlayer(name)` — resolves via case-insensitive username lookup; failure
    produces the Paint error `p.gray` `Player <red name> not found or is offline` (:296-321).
  - `syntax.integer(name)` — parseInt; failure → `ERROR Expected a number, got '<red word>'`.
  - `syntax.oneOf(name, [...])` — case-insensitive literal alternatives (used for command
    aliases: `region|rg`, `reply|r`); rendered as `<name:a|b>` in pattern strings.
- Matching (:51-104): word-by-word; literals must match exactly; extra trailing words fail the
  match unless the last parser is a rest parser; parser errors abort with the error Paint.
- Execution (`executeCommand`, :220-251): commands tried **in registration order**; first match
  wins; handler return `undefined` ⇒ true (handled, no reply); a returned Paint is sent to the
  sender (`network/packet-routing.ts:50-55`); handler exceptions are logged and swallowed
  (command still counts as handled); if nothing matched but a parser produced an error, that
  error Paint is sent; otherwise returns false and **the command packet is forwarded to the
  backend** (vanilla/backend commands keep working). Every attempt logs
  `<name>: /<cmd> (<pattern>)` or `(Invalid command)`.
- Registry helpers: `getRegisteredCommands`, `getUniqueCommandNames` (splits `a|b` aliases),
  `getSuggestionsForCommand` (finds the parser at the cursor position and calls its suggestion
  provider — used by tab-complete interception), per-feature clearing for HMR.
- Full command list registered today: `op`, `deop`, `away`, `shrug`, `tableflip`, `msg`,
  `reply|r`, `switch`, `notepad`, `region|rg` (subcommands: rename/add/remove/delete/start/end/
  flag/bounds/locate/help).

### 3.2 Usage rendering (`command-usage.ts`)

`checkIncompleteCommand` builds `ERROR Usage: /<cmd> <arg:type> ...` messages for partially
typed known commands, and wraps parser errors in the ERROR prefix. **Currently dead code** —
nothing calls it (invalid commands simply fall through to the backend). Decide whether to
resurrect on Fabric (Brigadier gives usage errors for free).

### 3.3 Paint text styling (`paint.ts`)

- Colors: green, gray, white, yellow, red, blue, darkGray, reset; decorations: bold, italic,
  underline. Chainable tagged-template builder: `p.green.bold`text ${value}`` , nestable.
- Output formats: `toNbtObject()` (Minecraft text component: single part or `{text:'', extra:[…]}`
  with color/bold/italic/underlined), `toLegacyString()` (§-codes, emitting `§r` +
  re-applying the parent's formatting after a formatted child, :130-166), `toTerminal()` (ANSI),
  `toUnformatted()`.
- Semantic helpers: `p.error` → `<red+bold "ERROR"> <gray content>`; `p.success` →
  `<green+bold "SUCCESS"> <gray content>`; `p.usage` → literal `§b§lUSAGE §7<content>` (aqua,
  raw legacy string).
- Fabric mapping: Adventure/vanilla `Component` equivalents; keep exact color choices and the
  ERROR/SUCCESS prefixes — they are the server's entire message design language.

---

## 4. Persistence layer

All plain files in the proxy's working directory; no database. Writes are synchronous,
whole-file JSON rewrites; no locking.

| Store | Path | Format / schema | Written by |
|---|---|---|---|
| Player data | `players/<uuid>.json` (uuid = tracked player uuid: Mojang uuid in online mode — post-remap — or offline uuid in offline mode) | `{ timestamps?{login,logout,firstSeen}, ipAddress?, lastServer?: 'primary'\|'secondary', balance?, geoLocation?, balanceBeheadingLoss?, notepad?: string[], isAdmin?: boolean }` (`modules/PersistenceModule.ts:24-33`) | **Live fields**: `lastServer` (every login/switch, `connection-handler.ts:413,459,608`), `notepad` (NotepadFeature), `isAdmin` (AdminFeature). `timestamps`/`ipAddress` only via dead `trackPlayerLoginData`; `balance`, `geoLocation`, `balanceBeheadingLoss` are **legacy fields never read or written** by the portal (data from the predecessor server may exist on disk — do not destroy on migration). |
| UUID→name cache | `uuid-cache.json` | `Record<uuid, username>` | `cachePlayerUuid` — in practice only from `/op` (§7 oddity: region scoreboards depend on it for offline members' names). Loaded at startup. |
| Regions | `regions.json` (cwd) | `{ regions: { "<idx>": { title, "start-x", "start-z", "start-y"? (om. if 320), "end-x", "end-z", "end-y"? (om. if −64), world, members: uuid[], flags?: string[], "sub-regions"?: {…recursive} } } }` (`features/RegionFeature.ts:30-46,120-152`) | RegionFeature on every mutation. Legacy format shared with the pre-proxy plugin — keep readable/writable for migration. |
| Backend op lists | `minecraft-server/{primary,secondary}/ops.json` | Vanilla ops format, entries `{uuid: offlineUuid, name, level:4, bypassesPlayerLimit:false}` | AdminFeature `/op` `/deop`. |
| Backend playerdata | `minecraft-server/{primary/world,secondary/last}/playerdata/<offlineUuid>.dat` | gzip NBT | The backends themselves; SyncModule copies `SYNC_TAGS` between them on `/switch` (§1). |
| Launcher logs | `logs/{primary,secondary,proxy}.log` | ANSI-stripped process output | launcher.ts (proxy-only). |

**Migration note for Fabric**: the natural mapping is players/<uuid>.json → per-player mod data
(attachments or a flat dir), regions.json → mod config/world storage (keep the import path),
ops.json → real server ops, playerdata sync → obsolete (single server), `lastServer` →
"last overworld" dimension key.

---

## 5. Test inventory (`test/`, Bun test)

### Portable behaviour tests (specify behaviour the Fabric port must keep)

- `test/features/AdminFeature.test.ts` — /op & /deop gating (non-admin refused, admin allowed,
  iElmo backdoor), state changes.
- `test/features/AwayFeature.test.ts` — /away broadcast wording, cooldown, and that each
  interaction type (move/chat/command/item/place/break) clears away.
- `test/features/ChatFeature.test.ts` — command registration incl. `r` alias; /msg formatting;
  self-message error; /reply flows and errors.
- `test/features/CoreFeature.test.ts` — which modules Core enables.
- `test/features/MotdFeature.test.ts` — MOTD lines and formatting codes.
- `test/features/NotepadFeature.test.ts` — /notepad sends book; double-open guard message.
- `test/features/RegionFeature.test.ts` — command registration (region+rg), help text, all the
  "must stand in a region" errors, /rg start-before-end, admin gating of flag/bounds/locate.
- `test/features/SwitchFeature.test.ts` — /switch messaging and port toggling both directions.
- `test/features/TabListFeature.test.ts` — header/footer text and formatting.
- `test/features/TravelPatchFeature.test.ts` — both remaps, unknown name, case sensitivity.
- `test/feature-api/command.test.ts` — syntax DSL semantics (patterns, oneOf, rest, extra-arg
  rejection, registry, execution, arg passing).
- `test/feature-api/command-usage.test.ts` — usage-message generation (dead code today, but
  encodes intended UX).
- `test/feature-api/paint.test.ts` — legacy-string output for every color/decoration, nesting,
  error/usage helpers, terminal mapping.
- `test/feature-api/manager.test.ts`, `test/module-api/module.test.ts` — hook/module framework
  semantics (multiple hooks, idempotent enable) — port only if the port keeps a hook system.
- `test/modules/OnlinePlayersModule.test.ts` — registry behaviour + offline-UUID generation
  (consistent, v3 format) — offline-uuid part becomes migration-tooling knowledge.
- `test/modules/HeldItemModule.test.ts` — held-slot/hotbar tracking & isHoldingItem (semantics
  portable; source of data changes).
- `test/modules/XpOrbMergeModule.test.ts` — merge-within-50 ms semantics, per-socket
  independence (portable if the feature is kept).
- `test/modules/ChatModule.test.ts` — chat parsing incl. '/'-prefix exclusion, unicode (module
  unused; low value).

### Proxy-internals tests (verify mechanism, not product behaviour — do not port)

- `test/network/*`: packet-handlers (hook plumbing), packet-queue, socket-packet-slicer,
  packet-routing (chat/command packet interception — the *routing decisions* here, e.g. "chat is
  always intercepted", "unknown commands forwarded", are behaviourally relevant),
  commands-injection (Brigadier tree building), player-info-bitflags (uuid/props/INITIALIZE_CHAT
  rewriting), tab-list (global list bookkeeping, join/leave broadcast flow), region-protection
  (packet→hook translation), packet-parser-properties, defined-packet, login-properties,
  util.
- `test/encryption.test.ts`, `test/mojang-session.test.ts`, `test/login-packets.test.ts` —
  protocol crypto/login encoding.
- `test/encoding/data-buffer*.test.ts` — varint/NBT codec.
- `test/config.test.ts` — env-var config defaults.

---

## 6. Proxy-only concerns (replaced wholesale by the Fabric platform)

- **Protocol encoding stack**: `encoding/data-buffer.ts` (varint/string/NBT/etc. codecs),
  `defined-packets.json` + `scripts/generate-packets.ts` → `defined-packets.gen.ts` (packet
  schemas generated from minecraft-data), `manual-packets.ts`, `network/defined-packet.ts`,
  `network/packet-parser-properties.ts`.
- **Connection machinery**: `network/proxy.ts`, `connection-handler.ts` (state machine
  Login→Configuration→Play, backend dial-out, switching, dimension trick),
  `connection-state.ts`, `packet-queue.ts`, `socket-packet-slicer.ts`, `packet-handlers.ts`
  (handler/transform registries), `packet-routing.ts`, `util.ts`, `types.ts`, `packet-ids.ts`.
- **Auth/encryption/compression**: `network/encryption.ts` (RSA-1024 keypair, CFB8),
  `login-packets.ts`, `login.ts`, `mojang-session.ts` (hasJoined verification incl. two's-
  complement server-id hash, IP passed except for LAN clients `connection-handler.ts:230-232`),
  `compression.ts`. Fabric/vanilla handles all of it.
- **Status/ping handling**: `network/handle-proxy-query.ts` (keep the *content* — §2.2).
- **Offline/online UUID duality**: every offline-uuid rewrite (TabList, PlayerInfoBitflags,
  player-tracking) and `generateOfflineUUID` — moot, but needed once for **data migration**
  (playerdata and ops.json are keyed by offline uuid today).
- **Client-side illusions that become real**: fake adventure-mode gamemode change (RegionFeature),
  fake book item + window-click resync (Notepad), dig-ack packet on protection block, synthetic
  scoreboard packets, XP-orb packet merging, tab-list packet synthesis, Brigadier tree injection
  + tab-complete interception, chat-as-system-message broadcast, `CHAT_SESSION_UPDATE` blocking
  and secure-chat stripping.
- **Behavioural quirks that exist only as workarounds** (drop consciously): 2 s switch delay,
  100 ms tab-list delay, 100 ms entity-interact rate limit (`connection-handler.ts:330-335`),
  blanket drop of all `interact_at` packets (`packet-routing.ts:82-95` — verify no gameplay
  regressions, e.g. armor stands still work because plain `interact` passes), suppression of
  backend join/leave messages, death-message dedupe window.
- **Ops tooling**: `launcher.ts` (blessed TUI, per-pane logs, `logs/*.log`, crash-restart,
  GitHub webhook auto-deploy on port 9000), `minecraft-server.ts` (JDK 21 + server-jar
  auto-download, JVM flags, eula/server.properties provisioning, crash-retry ×3),
  `instrument.ts` + Sentry throughout, `logging.ts` (keep the idea: component-scoped levels,
  info/debug silenced in production).

---

## 7. Oddities / dead code / parity risks found

1. `/shrug` and `/tableflip` are **no-ops in production** — `OnlinePlayer.chat` is a stub
   (`modules/OnlinePlayersModule.ts:94-96`). Port the intent, not the bug.
2. `checkIncompleteCommand` (usage messages) and `ChatModule`/`CommandModule`/
   `PlayerPositionModule`/`PlayerInteractionModule` are dead code; `trackPlayerLoginData`/
   `trackPlayerLogoutData`/`convertPlayerDataToOfflineUuid` (incl. hardcoded spawn
   `16.5/71/-14.5`) are never called.
3. `MessageModule.sendSystemMessage` writes a wrong field name (`message` vs `content`) — unused,
   latent bug.
4. Region y-bounds inconsistency: loaded default 320/−64, but newly created regions get 255/15
   (`features/RegionFeature.ts:835-836`) — blocks above y255/below y15 in *new* regions are
   unprotected until an admin runs `/rg bounds`.
5. Region overlap check only tests whether *existing regions' corners* fall inside the new
   rectangle — a new region that fully contains no corner (e.g. a thin strip crossing another
   region) can overlap undetected (:785-813).
6. Five region flags are accepted but enforced nowhere (`ENABLE_EXPLOSIONS`, `DISABLE_GATES`,
   `ENABLE_FIRE_DAMAGE`, `DISABLE_PLAYER_FALL_DAMAGE`, `DISABLE_PUBLIC_REDSTONE_TRIGGERS`);
   `/embassy` is referenced in an error message but does not exist.
7. Explosion/fire/piston/fluid/mob-grief region protection does **not exist** in the proxy at
   all (packets can't express it) — currently regions are only protected against *player*
   actions. A Fabric port can silently fix this; decide whether to.
8. `uuid-cache.json` is only populated by `/op`, so region scoreboards/`locate` can't resolve
   most offline members' names (they're skipped) — consider a proper name cache on Fabric.
9. Admin identity is split: portal `isAdmin` keyed by online uuid vs backend ops.json keyed by
   offline uuid; the `iElmo` username backdoor in `/op` (and `iElmo` is itself the *target* of a
   TravelPatch remap from `AlsoJames`).
10. Chat is entirely unsigned system-chat (secure chat stripped, `enforcesSecureChat: true`
    advertised anyway); 1.21+ clients accept this today, but on Fabric you must decide between
    real signed chat vs. reproducing global unsigned chat.
11. `PlayerMove` fires only from client position packets (not server teleports) — region
    entry via teleport/portal is detected only after the client's next move packet.
12. Tab-list latency display rewrites player display names to `name [Nms]` on every latency
    update (`modules/PlayerInfoBitflagsModule.ts:185-188`) — easy to forget, very visible.
13. TPS in the tab footer is proxy event-loop TPS, effectively always ~20 — replace with real
    TPS and expect the number to become meaningful (and occasionally embarrassing).
14. `lastServer` login routing + per-world position (`Pos` never synced) is the core UX of the
    two-overworld system: on Fabric, per-dimension position storage and "log back into the world
    you left" must be built explicitly.
