# 02 — Embassy plots, /embassy command, anchor teleporter

**What to build:** Region `metadata` support in the model and `RegionStore` (optional
`"metadata"` object, written only when non-empty, legacy entries byte-identical); the
plot spiral allocator and Nucleus block palette (`populateChunk`); the `/embassy`
command family (`create`/`delete`, ops-gated, exact messages, clickable delete
confirmation); the stand-on-anchor teleport to the region's `embassy-destination`
(with sneak bypass and the admin clickable back-link); and the respawn-anchor
right-click guard inside EMBASSY regions. Upgrade the existing `/rg delete` embassy
refusal so `/embassy delete` is red and clickable.

**Blocked by:** 01 (Embassies dimension).

**Status:** done

See ../spec.md (User Stories 8–19; Implementation Decisions "Region metadata",
"Commands", "Text"; deviations 3, 6, 9, 17) and the Nucleus source `embassy.kt`
(`getNextAvailablePlotCoords`, `populateChunk`, `EmbassyCommand`,
`EmbassyListener.onPlayerMove` anchor branch, `onInteract`) plus `util.kt`
(`teleportWithAdminBack`, `sendAdminBack`) in the reference clone at
/Users/jam/Development/MCTravelerNucleus. Destination worlds are stored as legacy
names — map through `RegionWorlds`.

- [x] `Region` carries metadata; `RegionStore` round-trips it; a store file without
      metadata is rewritten byte-identically (existing byte-compat tests extended)
- [x] Spiral allocation: first chunk outward from (0,0) whose centre resolves to the
      synthetic world region; matches Nucleus's leg-lengthening spiral order
- [x] `populateChunk` reproduces the palette of spec story 10 exactly (assert exact
      blocks, stair facings, slab type, anchor charges in a gametest)
- [x] `/embassy`, `/embassy create`, `/embassy delete` behave per stories 8, 9, 11,
      16, 17, 18 with exact texts, prefixes, colors, and the clickable confirm
- [x] Anchor standing teleport per stories 13–14 (block below feet, sneak bypass,
      destination from metadata via legacy world names, admin back-link clickable)
- [x] Anchor right-click guard per story 15
- [x] `/rg delete` embassy refusal message gains the red clickable `/embassy delete`
      (story 19); the flag-toggle and subregion guards already in place stay covered
- [x] Gametests: create (allocation, palette, region, metadata, teleport-in, origin
      recorded), delete (guard ladder, confirm flow, chunk cleared, region gone),
      anchor teleport + sneak + admin back-link, anchor guard; unit tests for the
      spiral and any pure geometry

## Comments

### Implemented

Three new files in `eu.mctraveler.embassy` carry the ticket: `EmbassyPlots`
(the spiral, the Nucleus block palette, and the plot teardown), `EmbassyCommands`
(the `/embassy` Brigadier tree), and `EmbassyAnchors` (the stand-on teleporter
and the right-click guard). All three are registered from
`EmbassiesFeature.register()`, so `MCTraveler.kt` is untouched.

Region metadata went in underneath: `Region.metadata` is a
`LinkedHashMap<String, JsonElement>` (Gson — `RegionStore` already parses with
it, so no new dependency and no second JSON model), and `RegionStore` gained an
optional `"metadata"` object plus a small pretty-printer that reproduces the
Portal's `JSON.stringify(…, null, 2)` shape for arbitrary nested JSON.

`Paint` grew the two things stories 14/17/19 needed and the codebase had never
had: public `aqua`/`gold`, and `runs(command)` — a `ClickEvent.RunCommand` link
in the style chain, so `Paint.gold.runs("/embassy delete Home")("here")` reads
like the rest of the DSL. This is the codebase's first ClickEvent (deviation 9).

25 new gametests (`EmbassyPlotGameTest` 4, `EmbassyCommandGameTest` 10,
`EmbassyAnchorGameTest` 11) and 13 new unit tests (`EmbassyPlotsTest` 3,
`RegionServiceTest` +4, `PaintTest` +3, and 3 more assertions folded into
existing cases). Full `./gradlew build` green: **185 unit tests, 245 gametests**
(ticket 01 left it at 220).

### Public surface

**Region metadata — for ticket 05's importer**

- `Region.metadata: LinkedHashMap<String, JsonElement>` (`com.google.gson`).
  Insertion-ordered, because the file it round-trips through is.
- `RegionStore` writes `"metadata"` only when non-empty, positioned **after
  `flags` and before `sub-regions`**. A region without metadata is byte-identical
  to before the key existed (asserted in
  `RegionServiceTest.a region with no metadata writes no metadata key`).
- `EmbassyCommands.DESTINATION` = `"embassy-destination"` — the key constant.

The exact bytes `/embassy create` writes into `regions.json` (region fields at
6-space indent; this is a whole region entry, so ticket 05 can copy it):

```json
      "title": "Unnamed Embassy",
      "start-x": 3,
      "start-z": 3,
      "end-x": 13,
      "end-z": 13,
      "world": "embassies",
      "members": [
        "11111111-1111-1111-1111-111111111111"
      ],
      "flags": [
        "EMBASSY"
      ],
      "metadata": {
        "embassy-destination": {
          "x": 123.5,
          "y": 64.0,
          "z": -87.25,
          "yaw": 90.0,
          "pitch": 0.0,
          "world": "world"
        }
      }
```

`x`/`y`/`z` are doubles, `yaw`/`pitch` floats, `world` the **legacy** world
string (`world`, `last_nether`, …) — never a dimension id. Key order inside
`embassy-destination` is `x, y, z, yaw, pitch, world`, and it is preserved on
load/save. Numbers survive a round-trip as the literal they arrived as (Gson's
`LazilyParsedNumber` keeps the raw text), so `64.0` does not come back as `64`
— `RegionServiceTest.saving reproduces a metadata file byte for byte` pins it.
Y bounds are the store defaults (320 / −64) and so are omitted.

**Plot geometry — `eu.mctraveler.embassy.EmbassyPlots`**

- `spiral(): Sequence<ChunkPos>` — Nucleus's order, infinite, pure.
- `isFree(plot)`, `nextFreePlot(): ChunkPos`.
- `plotOf(x, z): ChunkPos` — the plot a block column belongs to.
- `populate(level, plot)` / `clear(level, plot)`.
- Constants ticket 05 will want when it lays imported plots out:
  `FLOOR_Y` = 0, `GRASS_MIN` = 3, `GRASS_MAX` = 13, `ANCHOR_LOCAL` = 8,
  `ANCHOR_CHARGES` = 4. A region's footprint is
  `plot*16 + GRASS_MIN .. plot*16 + GRASS_MAX` on both axes.

**Anchors — `eu.mctraveler.embassy.EmbassyAnchors`**

- `allowsAnchorUse(player, level, pos): Boolean` — the guard's decision, exposed
  so it can be asserted directly as well as through the event path.

**Shared seams added elsewhere**

- `RegionWorlds.dimensionFor(world: String): ResourceKey<Level>?` — the inverse
  of `legacyName`, for stored destinations. Null when this server has no such
  world (a real answer, not an error). **Ticket 05 wants this** to validate
  imported destinations.
- `RegionsFeature.adminGate(player): Component?` — the one definition of
  `ERROR You must be an admin to use this command`; `RegionCommands` now
  delegates to it rather than keeping a second copy of the string.
- `Paint.aqua`, `Paint.gold`, `Paint.runs(command)`.

### For ticket 04 (crystal menu)

- The Embassy destination teleports to `(0.5, 1.0, 0.5)` in
  `EmbassiesFeature.DIMENSION` (spec story 31). That is **not** on a plot, so the
  anchor sweep will not fire on arrival — nothing to guard against.
- `Paint.runs(command)` is the ClickEvent affordance stories 34/35 need, and
  `Paint.aqua` is now public for the aqua "here".
- Teleport with `player.teleportTo(level, x, y, z, emptySet(), yRot, xRot, false)`:
  it routes through `ServerPlayer.teleport(TeleportTransition)`, which is what
  records the embassies origin. Do not touch `EmbassyOrigins` directly.

### Interpretations and deviations

1. **Arriving on an anchor is not stepping onto one.** Nucleus gated its anchor
   branch on `hasChangedBlock()` of a move event; a Bukkit teleport *is* such an
   event, so a literal port would have bounced `/embassy create`'s sender
   straight back out — the command drops them at the plot centre, which is
   exactly the block above the anchor. (Nucleus escaped this only because its
   region tracker lagged a listener behind, leaving `currentRegion` stale.) The
   sweep here therefore treats the first tick a player is seen in the dimension
   as establishing their position, not as a step: `previous == null` fires
   nothing. Stepping on, off and on again works normally, and this is what makes
   story 9 and story 13 coexist. Covered by the create test and by the sneak
   test's step-off/step-on cycle.
2. **The anchor check is a once-a-tick sweep**, not a movement hook — the shape
   ticket 01 used for void-falls, and it catches a player who arrived on the
   anchor by any means. The `hasChangedBlock` gate is reproduced by remembering
   each player's last block position while they are in the dimension.
3. **The guard hooks two Fabric events, not one.** Nucleus had a single
   `PlayerInteractEvent`; Fabric splits a right-click into `ItemEvents.USE_ON`
   (an item applied to the block) and `BlockEvents.USE_WITHOUT_ITEM` (the
   block's own behaviour). Only the first is already refused to visitors by
   region protection — an anchor is neither a door nor a switch, so
   `allowsBlockUse` passes it for everyone — which means the empty-hand path was
   wide open to *any* player, and the item path still open to members. Both are
   asserted: an owner and a visitor each fail to detonate their anchor.
   The dimension sets `respawn_anchor_works: false`, so vanilla's answer to that
   click really is an explosion; these are not theoretical tests.
4. **Main hand only, for the glowstone exemption** — Nucleus read
   `itemInMainHand`, so charging an under-charged anchor from the off-hand was
   refused there and is refused here.
5. **The admin back-link's click event sits on the message body**, covering
   "You can click here to go back to your previous location." but not the INFO
   prefix — which is exactly Nucleus's shape (`INFO_COMPONENT.append(message)`,
   the click event on `message`). Story 14 says "the message is clickable"; the
   aqua "here" is clickable by inheritance, so both readings hold. Deviation 3's
   `/execute in <dimension> run tp @s <x> <y> <z>` uses the pre-teleport **block**
   coordinates, as Nucleus's `/tp` did.
6. **`metadata` is written after `flags` and before `sub-regions`.** The two are
   never seen together in practice — the only regions carrying metadata are
   embassies, and a region cannot be created inside an embassy — so the choice is
   unobservable on any real file. Stated so ticket 05 writes the same order.
7. **Gson, not kotlinx, for metadata values.** `RegionStore` already parses with
   Gson; `PortalJson` is a raw-slice scanner built for the *player* store's
   read-modify-write contract and has no value model to hang metadata off. Using
   Gson keeps one JSON model in the region path, and the byte-compat contract is
   met by writing the pretty-printer by hand (as the rest of the file already
   was) rather than by trusting a serializer's formatting.
8. **`GameTestJanitor` is still unchanged** (ticket 01's deviation 7 asked this
   ticket to re-check). Plots are built at fixed coordinates in the embassies
   dimension, but allocation reads the *region tree*, never the ground, and
   `regions.json` is deleted at boot — so run two allocates from (0,0) again
   regardless of what run one left in the chunk files. Every test that builds
   also clears, and none asserts "this ground was empty first", which is the one
   assertion stale chunks would break. The leftover `.mca` files are sparse and
   harmless; deleting the dimension's region folder would be complexity without
   a failing test behind it.
9. **A build-file addition:** `./gradlew runGameTest -Pmctraveler.gametestFilter=…`
   passes a selector to the runner's `fabric-api.gametest.filter` property, so a
   single gametest class can be iterated on (ids are
   `mctraveler-test:<snake_case(Class_method)>`, wildcards allowed). Without the
   property nothing changes, so `./gradlew build` is unaffected.
10. **Gametest assertions avoid `messages.last()` in multi-tick tests.** A
    `MessageCapturingPlayer` also receives the server's broadcasts — join
    announcements and the gametest framework's own per-test result lines — so in
    a test that spans ticks the reply is rarely the last thing heard. The anchor
    tests select their message by content instead. Worth knowing for ticket 04's
    menu tests, which will also span ticks.
