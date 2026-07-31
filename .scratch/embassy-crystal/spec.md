# Spec: Embassies and the Teleportation Crystal

Status: ready-for-agent

Port of the two remaining MCTravelerNucleus features — the Embassies world and the
Teleportation Crystal — from the Nucleus-era Paper plugin
(`https://github.com/Blazzike/MCTravelerNucleus`, reference clone at
`/Users/jam/Development/MCTravelerNucleus`) into the Fabric mod. Companion docs: the
fabric-port spec (`../fabric-port/spec.md`) for house conventions, `CONTEXT.md` for
vocabulary, ADR 0003 for the out-of-trio decision. Source files of record:
`src/main/kotlin/dev/jamespowell/mcTravelerNucleus/embassy.kt` and
`teleportation-crystal.kt` in the reference clone.

## Problem Statement

The fabric port reproduced the Portal era. Two features from the earlier Nucleus era
never crossed into the Portal and so never reached the Fabric server: the Embassies
world (20 player embassies still sit in `/root/MCTraveler-Old-Data/Server/embassies`
and in the Nucleus `regions.json` on the dedi) and the Teleportation Crystal (craftable
teleport item; 25 players still carry energy state in Nucleus-era playerdata). The
fabric codebase already carries their scars: an `EMBASSY` region flag that cannot be
toggled, a `/rg delete` refusal that points at a `/embassy delete` command that does
not exist. Players want their embassies and crystals back, identical to how they were.

## Solution

Add an out-of-trio `mctraveler:embassies` dimension (datapack JSON, flat void, custom
no-spawn plains biome) with the Nucleus plot system, `/embassy` command family, origin
tracking, and anchor teleporters; add the Teleportation Crystal as a re-skinned Echo
Shard (Notepad idiom) with per-player energy, viewer-relative damage display, datapack
recipes with a component-aware crafting guard, and the chest-GUI destination menu.
Extend the one-time importer to carry the Nucleus-era embassies world, embassy regions
(with their saved destinations), and per-player crystal energy into the live save
before the new build's first boot.

## User Stories

### The Embassies dimension

1. As a player, I want an embassies dimension that is a flat void of plains biome with
   no natural mobs, no weather, and frozen daylight, so embassies feel like a museum
   space, not survival terrain.
2. As a player, I want to take no damage of any kind while in the embassies dimension.
3. As a player, I want the region sidebar hidden in the void between plots (the whole
   dimension is the synthetic "Embassies World" region, flag `NO_SCOREBOARD`, no
   members), but shown normally when I stand on an embassy plot (title, residents).
4. As a player, I want the void and other players' plots protected from modification
   exactly like any region I am not a member of.
5. As a player, I want falling off a plot into the void to return me to where I was
   before I entered the embassies dimension (no fall damage, fall distance reset),
   rather than dying or drifting forever.
6. As a player, I want logging out inside the embassies dimension to put me back at my
   pre-entry location, so I log back in where I really was. The server does the same
   for everyone still inside when it stops.
7. As a player, I want my pre-entry location remembered whenever I enter the embassies
   dimension from outside by any teleport (crystal menu, `/embassy create`, admin tp).

### Embassy plots

8. As an admin, I want `/embassy` with no arguments to print the two lines
   `/embassy create` and `/embassy delete` (plain text, no prefix).
9. As an admin, I want `/embassy create` (ops only), refused with ERROR
   "You must not be in the embassies world" when issued from inside the embassies
   dimension, to: allocate the next free plot on the outward chunk spiral from (0,0)
   (a plot chunk is free when the region lookup at its centre still resolves to the
   synthetic world region); build the plot; create the region; teleport me to the
   plot centre (chunkX*16+8.5, y=1.0, chunkZ*16+8.5); and reply SUCCESS
   "Created embassy".
10. As an admin, I want the plot built exactly as Nucleus built it, per chunk (cx, cz):
    bedrock across the chunk at y=-64; dirt filling y=-63..-1; smooth quartz slabs
    (bottom half) across the chunk at y=0; an 11×11 grass-block square at local
    (3..13, y=0, 3..13); blackstone stairs framing the grass: local x 2..14 at z=2
    facing so their backs surround the platform (Nucleus rotations: north edge
    CLOCKWISE_180, west edge CLOCKWISE_90, south edge default, east edge
    COUNTERCLOCKWISE_90, from a base stair facing north); and a respawn anchor with
    4 charges at local (8, 0, 8).
11. As an admin, I want the created region titled "Unnamed Embassy", spanning block
    x plot*16+3 .. plot*16+13 and z likewise, full height (startY 320, endY -64),
    members = just me, flags = {EMBASSY}, and metadata
    `embassy-destination` = my position at the moment of creation
    (x, y, z, yaw, pitch, world) — world recorded as the legacy world name
    ("world", "last", "last_nether", …).
12. As an embassy owner, I want to rename my embassy with the ordinary
    `/rg rename`, and manage members with the ordinary region commands.
13. As a player standing in an embassy plot, I want stepping onto the block above the
    respawn anchor (block below my feet is the anchor) to teleport me to that
    embassy's saved destination, with SUCCESS "Teleported from embassy" — unless I am
    sneaking, in which case I stay and get INFO "Sneaking, teleportation ignored".
14. As an admin teleported by an embassy anchor, I want the additional INFO line
    "You can click here to go back to your previous location." where the message is
    clickable and runs a command returning me to the anchor-side position.
15. As a player, I want right-clicks on an embassy plot's respawn anchor guarded:
    when the anchor has charges and I am not recharging it with glowstone (or it is
    already full), the interaction is cancelled — the anchor never explodes and never
    loses charges; recharging an under-charged anchor with glowstone stays possible.
16. As an embassy member, I want `/embassy delete` (ops only) to refuse with ERROR
    "You must be in the embassies world" outside the dimension, ERROR
    "You must be in an embassy" outside a plot, and ERROR
    "You are not a member of this embassy" when I am not a member.
17. As an embassy member, I want `/embassy delete` without the exact title to warn:
    WARNING "Are you sure you want to delete this embassy? The embassy build will
    also be deleted. Click here to confirm. This cannot be undone." — "here" gold and
    clickable, running `/embassy delete <title>`.
18. As an embassy member, I want `/embassy delete <exact title>` to clear the chunk I
    stand in to air (full height), refresh the region state of everyone inside, remove
    the region, persist, and reply SUCCESS "Embassy deleted".
19. As a player, I keep the existing guards: the EMBASSY flag cannot be toggled,
    regions cannot be created inside an embassy, and `/rg delete` on an embassy says
    to use `/embassy delete` — now with `/embassy delete` red and clickable.

### The Teleportation Crystal item

20. As a player, I want to craft a tier-1 crystal from one Eye of Ender (shapeless);
    a tier-2 by surrounding a tier-1 crystal with 4 amethyst shards (plus pattern);
    a tier-3 by surrounding a tier-2 crystal with 4 echo shards (plus pattern). A
    plain echo shard in the centre must not craft a higher tier.
21. As a player, I want crystals unusable as ingredients in any other recipe (e.g. a
    tier-1 crystal must never craft into a recovery compass).
22. As a player, I want the crystal to be an Echo Shard named "Teleportation Crystal",
    max stack 1, enchant-glinted, with lore: "The power of teleportation in your
    hands" / "Recharges one use every 15 minutes" / "" / "Charge capacity <tier>"
    (last line gold).
23. As a player, I want every crystal I see to show my own energy as its damage bar:
    max damage = tier, damage = 3 − my energy, rewritten on outgoing container
    packets so the stored item never carries damage.
24. As a player, I want 0–3 energy (default 3) persisted across sessions, shared by
    all my crystals.
25. As a player, I want one energy back per 15 minutes of play time (ticks played,
    not wall clock), starting from when I first drop below 3, with INFO
    "Your energy crystal has recharged one energy" on each point.

### The crystal menu

26. As a player, I want right-clicking with a crystal (air or block, anywhere,
    including other players' regions) to open a 27-slot chest GUI titled "Where would
    you like to go?" — top and bottom rows black stained glass panes; middle row blue
    stained glass panes except slots 11–15: Bed (blue bed, "Go back to your place of
    rest"), Spawn (spawner, "Head to spawn town"), Player (player head, "Request to
    teleport to a player" / "costs even if they don't accept"), Embassy (spyglass,
    "Teleport to the embassy world"), Wilderness (grass block, "Coming soon"). Items
    show name + lore only (extra tooltip hidden); clicks never move items.
27. As a player, I want the menu refused with ERROR "You are already in a
    teleportation crystal." when one is open, and ERROR "You have no energy, please
    wait for a recharge" when my energy ≤ 3 − tier (a tier-1 crystal needs full
    energy; tier-3 works down to 1).
28. As a player, I want choosing a destination to close the menu, teleport me, cost
    one energy, and reply INFO "You used one energy going to <name>" with the
    lowercase destination name in aqua.
29. As a player, I want Bed to send me to my current respawn point (bed/anchor as
    vanilla resolves it in my current World), or ERROR "You have no bed to go to"
    (no energy spent).
30. As a player, I want Spawn to send me to spawn town: Primary overworld
    (16.5, 71.0, −15.5), yaw 180, pitch 0.
31. As a player, I want Embassy to send me to the embassies dimension at
    (0.5, 1.0, 0.5).
32. As a player, I want Wilderness to reply ERROR "Sorry, this feature is not
    available yet" (menu closes, no energy spent).
33. As a player, I want Player to refuse with ERROR "No-one else is online" when I am
    alone; otherwise open a "Select a player" GUI (rows = ⌈others/9⌉) of the other
    online players' heads (their skin, name, lore "Click to teleport to this player").
34. As a player, I want clicking a head to cost one energy, reply SUCCESS "One energy
    used to send request to <name>" (name green), and send the target INFO
    "<me> wants to teleport to you - click here to accept" (my name and "here" aqua,
    the message clickable, running `/teleportation-crystal-accept <me>`). If the head's
    player just left: ERROR "<name> is not online" (name red) and no energy spent.
35. As a request target, I want `/teleportation-crystal-accept <name>` (not shown in
    tab completion) to validate: unknown player → ERROR "<name> is not online";
    no matching request for me → ERROR "No request found"; request older than 5
    minutes → ERROR "Request timed out" (request consumed). On success the requester
    teleports to me, they get INFO "<me> has accepted your request" (my name aqua),
    I get SUCCESS "Request accepted".
36. As a player, my menu/request state is cleared when I close the GUI or disconnect;
    open crystal GUIs are closed when the server stops.

### Admin

37. As an admin, I want `/set-teleportation-crystal-energy <energy> [player]` (ops
    only): no args → USAGE "/set-teleportation-crystal-energy <energy> [player]";
    out of range → ERROR "Energy must be between 0 and 3"; otherwise set the target's
    energy (target defaults to me; unknown player → ERROR "<name> is not online") and
    reply SUCCESS "<target> now has <n> energy" (target name and count green).

### Migration

38. As a returning player, I want the 20 Nucleus-era embassies back: their plot
    builds (world chunks), their regions with titles, members, EMBASSY flag, and
    their saved `embassy-destination` positions — before the new build first boots.
39. As a returning player, I want my Nucleus-era crystal energy and recharge progress
    carried over where a newer value doesn't exist.

## Implementation Decisions

- **Dimension**: `data/mctraveler/dimension/embassies.json` — flat generator, single
  air layer, no lakes/features/structures, biome `mctraveler:embassies_plains`; a
  custom `dimension_type` cloning overworld but with `fixed_time` (noon); a custom
  biome cloning plains visuals with empty spawners and `has_precipitation: false`.
  Datapack-first, like the secondary trio.
- **Not a World**: embassies is an out-of-trio dimension (ADR 0003). No `/switch`, no
  Per-World Bucket, no Position Memory. `Worlds.worldOf` stays null for it; origin
  tracking (stories 5–7) is the only way in and out for players.
- **Synthetic world region**: a guard seam on region lookup returns the in-memory
  region "Embassies World" (flags `{NO_SCOREBOARD}`, no members, never persisted) for
  any embassies position not inside a real region — mirroring Nucleus's
  `getRegionAtGuards`. Protection, sidebar, and plot allocation all flow from it.
- **Region metadata**: `Region` gains a `metadata: MutableMap<String, JsonElement>`
  (or equivalent) and `RegionStore` an optional `"metadata"` object per region,
  written only when non-empty — legacy entries stay byte-identical.
- **Origin tracking**: in-memory `UUID → (dimension, x, y, z, yaw, pitch)` recorded on
  any change-level into embassies from outside; consumed by void-fall (y < −64),
  disconnect, and SERVER_STOPPING. Never persisted (Nucleus used a WeakHashMap).
- **Crystal item**: Notepad idiom — `ItemStack(Items.ECHO_SHARD)` + `CUSTOM_DATA`
  marker (`is-teleportation-crystal`, `tier`), `ITEM_NAME`, `LORE`, `MAX_STACK_SIZE`
  1, `MAX_DAMAGE` tier, `ENCHANTMENT_GLINT_OVERRIDE`. No registry writes (server-only
  mod). Damage display is per-viewer: rewrite crystal stacks in outgoing
  `ClientboundContainerSetSlotPacket` / `ClientboundContainerSetContentPacket`
  (mixin), never on the stored stack.
- **Energy**: two new typed pairs on `PlayerStore` (energy, next-regen-at). Regen loop
  on END_SERVER_TICK every 20 ticks over online players, thresholds in play-time
  ticks (`Stats.PLAY_TIME`), +15·60·20 ticks per point.
- **Recipes**: datapack JSONs (component results) for the three tiers, plus a
  server-side crafting guard for what datapack ingredients cannot express: the centre
  of tier-2/3 must be a crystal of the right tier, and a crystal anywhere in a grid
  whose result is not a crystal kills the result.
- **GUI**: first `MenuProvider`/`ChestMenu` use. Mod-owned menus are exempt from
  region container protection (`RegionContainerClickMixin` path) and from the
  container-region session tracking. All clicks cancelled server-side.
- **Commands**: `/embassy` + subcommands and `/set-teleportation-crystal-energy` as
  ordinary Brigadier trees, admin-gated in-body via `RegionsFeature.isAdmin` (USAGE
  before gate, house rule). `/teleportation-crystal-accept` handled in the chat/command
  packet path so it never appears in the client command tree (Nucleus parity).
- **Text**: `Paint.info` (aqua INFO) and `Paint.warning` (gold WARNING) join the
  vocabulary; first `ClickEvent` uses (stories 14, 17, 19, 34, 35).
- **Importer**: a separate post-cutover command (gradle task) run against a stopped
  server: copies `embassies/{region,entities,poi}` →
  `world/dimensions/mctraveler/embassies/`, converts the 20 embassy regions from
  Nucleus `regions.json` (schema: x/z/y bounds, EMBASSY flag, metadata) into the live
  `regions.json`, imports crystal energy/next-regen from playerdata
  `PublicBukkitValues` (`mctravelernucleus:tc-teleportation-energy`,
  `tc-next-regen-at`) into the player store. Idempotent; refuses to double-import
  embassy regions. Nucleus-era UUIDs are Mojang UUIDs (online-mode server) — no
  identity re-keying.

## Testing Decisions

Gametests are the primary tier, driven through the existing harnesses
(`MessageCapturingPlayer` for commands/messages, `TestPlayer`/`PacketCapture` for
packet-level assertions like the damage-bar rewrite and hidden accept command,
`NotepadTestPlayer`-style held-item driving for crystal use). Plot geometry, spiral
allocation, and the crafting guard get unit tests where they are pure. The importer
follows the ticket-18 pattern: unit tests against fixture directories. `SmokeHook`
gains the embassies dimension.

## Out of Scope

- Nucleus's `/give` integration (crystal item names in an admin give command) — the
  fabric port never adopted Nucleus admin utils; vanilla `/give` with components
  serves admins.
- A real Wilderness destination (the stub error is the feature, story 32).
- Embassy plot resale/reassignment, or any behavior Nucleus did not have.
- Making embassies a World (/switch, buckets) — ADR 0003.

## Further Notes

### Deviation register

Entries keep their numbers permanently; tickets cite them as "deviation N".

1. **Gamerules**: Bukkit set ~40 per-world gamerules on the embassies world. Fabric
   gamerules are per-server. Only the user-visible effects are reproduced, by
   dimension-scoped means: all player damage cancelled (subsumes fall/fire/freeze/
   drowning rules), `fixed_time` for the frozen daylight, biome
   `has_precipitation: false` for the missing weather, empty biome spawners for the
   missing mobs. Keep-inventory and immediate-respawn are moot (players cannot die
   there); the remaining rules governed systems that do not exist on the plots.
2. **Frozen time of day**: Nucleus froze time at whatever it was at world creation;
   we fix it at noon.
3. **Admin back-link**: Nucleus's clickable back-link ran Bukkit
   `/tp <x> <y> <z> <world>`; ours runs an `/execute in <dimension> run tp`
   equivalent. Message text unchanged.
4. **Request timeout**: Nucleus used wall-clock `System.currentTimeMillis()`
   (300 000 ms); we use 6 000 server ticks, per the house all-timing-is-ticks rule.
5. **`/set-teleportation-crystal-energy` feedback**: Nucleus sent the error and
   success lines to the *target*, leaving the sender silent when setting another
   player's energy — plainly a bug (the message names the target in third person).
   Intent Parity: feedback goes to the sender.
6. **regions.json schema**: gains an optional `"metadata"` object per region entry.
   Legacy entries are unaffected byte-for-byte; only embassy regions carry it.
7. **Recipes**: Nucleus registered Bukkit recipes whose ingredients matched the
   crystal's NBT. Datapack ingredients are component-blind, so the tier-2/3 centre
   slot and the never-an-ingredient rule are enforced by a server-side crafting
   guard; observable behavior is identical (wrong centre → no result shown).
8. **Accept command**: `/teleportation-crystal-accept` was an unregistered
   preprocess-intercepted command in Nucleus (no tab completion, no unknown-command
   error). We intercept the command packet server-side for the same effect.
9. **ClickEvent**: first use in this codebase (previously all command suggestions
   were plain text).
10. **Paint vocabulary**: gains INFO (aqua, bold prefix) and WARNING (gold, bold
    prefix), matching Nucleus's component styles exactly.
11. **Origin-clearing dead code**: Nucleus's teleport handler had an unreachable
    branch meant to clear the origin when leaving embassies by teleport; origins were
    in practice consumed only by void-fall and quit. We reproduce the practice, and
    also clear the origin on any teleport that leaves the dimension (the plain
    intent); the anchor and menu paths behave identically either way.
12. **Damage-bar rewrite**: Nucleus rewrote SET_SLOT/WINDOW_ITEMS via ProtocolLib;
    we mixin the corresponding clientbound container packets. Same observable: every
    crystal a player sees wears that player's energy.
13. **Crystal in foreign regions**: usable (Nucleus's listener ignored region
    protection); our item-use protection exempts the crystal so the menu opens
    anywhere.
14. **Import freshness**: crystal energy is read from the newest playerdata that
    carries the tags (post-Nucleus eras never wrote them, so in practice the
    Nucleus-era files); embassies chunks are 1.21-era and are upgraded by vanilla
    DataFixerUpper on first load.
15. **Biome**: Nucleus served plains from a code BiomeProvider; we ship
    `mctraveler:embassies_plains`, a plains clone with no spawns and no
    precipitation (registry-synced to vanilla clients).
16. **Menu protection exemption**: region container protection (click cancellation
    and container-session tracking) skips mod-owned menus; Nucleus menus were
    plugin-owned inventories that its region listeners never touched.
17. **Anchor guard scope**: the right-click guard applies inside embassy-flagged
    regions (as in Nucleus); anchors elsewhere in the dimension cannot exist through
    normal play.
