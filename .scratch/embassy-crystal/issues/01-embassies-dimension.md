# 01 — Embassies dimension and lifecycle

**What to build:** The `mctraveler:embassies` dimension as datapack JSON (flat void,
custom `dimension_type` with noon `fixed_time`, custom `mctraveler:embassies_plains`
biome with no spawns and no precipitation), the synthetic "Embassies World" region
guard on region lookup (`NO_SCOREBOARD`, no members, never persisted), dimension-wide
player damage cancellation, and origin tracking: record a player's pre-entry position
on any change-level into the dimension, return them there on void-fall (y < −64, fall
distance reset), on disconnect, and on SERVER_STOPPING; clear it when they leave the
dimension. Give `RegionWorlds.locateInfo` an embassies case and extend
`SmokeHook.checkWorlds` so the dimension is proven on the production smoke boot.

**Blocked by:** none.

**Status:** done

See ../spec.md (User Stories 1–7; Implementation Decisions "Dimension", "Not a
World", "Synthetic world region", "Origin tracking"; deviations 1, 2, 11, 15) and the
Nucleus source `embassy.kt` (`initEmbassies`, `EmbassyListener.onPlayerMove` /
`onPlayerDamage` / `onPlayerTeleport` / `onPlayerQuit`, `cleanUpEmbassies`) in the
reference clone at /Users/jam/Development/MCTravelerNucleus.

- [x] Dimension, dimension_type, and biome JSONs ship in the mod jar; the dimension
      loads on a dedicated server and under GameTestServer (the existing datapack
      mixin picks it up); generated chunks are pure air over void
- [x] Region lookup in the embassies dimension falls back to the synthetic
      "Embassies World" region: sidebar hidden there, modification refused for
      everyone ("This area is protected by Embassies World"), lookup outside the
      dimension unchanged; the synthetic region never reaches regions.json
- [x] Players take no damage in the dimension (void included)
- [x] Origin recorded on entering from any dimension outside embassies, by any
      teleport path; consumed on void-fall / disconnect / server stop; cleared on
      leaving; a player with no recorded origin falling below −64 is left alone
      (parity)
- [x] Disconnect inside embassies saves the player at their origin (they log back in
      there); SERVER_STOPPING returns everyone inside before the save
- [x] `Worlds`/`/switch` untouched: `worldOf(embassies)` is null and travel/login
      behavior for the two real Worlds is unchanged
- [x] SmokeHook asserts the embassies dimension; GameTestJanitor updated if this
      ticket adds any persisted file (nothing new is persisted — see the notes)
- [x] Gametests: dimension exists + void generation; synthetic region protection and
      hidden sidebar; damage cancellation; origin roundtrip (enter → fall → back;
      enter → disconnect → relog at origin); unit tests for any pure pieces

## Comments

### Implemented

The dimension is three datapack files in the mod jar
(`data/mctraveler/dimension/embassies.json`, `dimension_type/embassies.json`,
`worldgen/biome/embassies_plains.json`), and everything JSON cannot say lives in
`eu.mctraveler.embassy`: `EmbassiesFeature` (synthetic world region, damage
cancellation, the three origin consumers) and `EmbassyOrigins` (the in-memory
tracker). One registration line joins `MCTraveler.kt`. One mixin,
`EmbassyOriginMixin`, records the origin on the way in.

Region lookup grew a guard seam. `RegionsFeature.regionAt(world, x, y, z)` is now
the one lookup — `RegionTracker.regionOf` and `/rg end`'s corner checks were
rewired through it — and it folds a list of guards over the tree's answer, which
is Nucleus's `getRegionAtGuards` in Fabric form.

14 gametests in `EmbassiesGameTest` and 2 unit tests in `RegionWorldsTest`.
Full `./gradlew build` green (220 gametests), and `./gradlew prodServer` logs
`MCTraveler prod smoke: embassies = mctraveler:embassies` — the dimension really
does load on a real dedicated server.

### Public surface for tickets 02 and 04

`eu.mctraveler.embassy.EmbassiesFeature`

- `DIMENSION: ResourceKey<Level>` — `mctraveler:embassies`.
- `BIOME: ResourceKey<Biome>` — `mctraveler:embassies_plains`.
- `worldRegion: Region` — the synthetic "Embassies World". **Ticket 02's spiral
  allocator wants identity**: a plot chunk is free when
  `RegionsFeature.regionAt(RegionWorlds.EMBASSIES, cx * 16 + 8, y, cz * 16 + 8)
  === EmbassiesFeature.worldRegion`.
- `isEmbassies(level: Level): Boolean` — for `/embassy create`'s "You must not be
  in the embassies world" and `/embassy delete`'s mirror of it.
- `returnEveryoneInside(server: MinecraftServer)`.
- `register()`.

`eu.mctraveler.embassy.EmbassyOrigins`

- `data class Origin(dimension: ResourceKey<Level>, x, y, z: Double, yaw, pitch: Float)`.
- `originOf(player: ServerPlayer): Origin?`.
- `sendHome(player: ServerPlayer): Boolean` — false when nothing was recorded.
- `forget(uuid: UUID)`.
- `beforeTeleport(player, destination)` — the mixin's entry point. **Nothing else
  should call it**: `/embassy create` and the crystal menu get their origin
  recorded for free, because every player teleport goes through
  `ServerPlayer.teleport(TeleportTransition)`.

The guard seam, in `eu.mctraveler.region.RegionsFeature`

- `regionAt(world: String, x: Int, y: Int, z: Int): Region?` and the existing
  `regionAt(level: Level, pos: BlockPos)`, both guarded.
- `addLookupGuard(guard: (world, x, y, z, found: Region?) -> Region?)`.

`RegionWorlds.EMBASSIES` = `"embassies"` — the legacy world string embassy regions
are stored under, both for ticket 02's new regions and ticket 05's imported ones.

### Interpretations and deviations

1. **`fixed_time` does not exist in Minecraft 26.2** (spec Implementation
   Decisions "Dimension" asks for "a custom `dimension_type` cloning overworld but
   with `fixed_time` (noon)"). The sky is now driven by *timelines* sampled
   against a *world clock*, and `dimension_type` carries only a boolean
   `has_fixed_time`. Frozen noon is therefore expressed as: `has_fixed_time: true`
   (which is also what takes day/night gameplay out of the dimension), **no
   timelines and no `default_clock`** (nothing animates the sky — the vanilla
   nether omits the clock the same way), and the noon values stated outright in
   the dimension type's own `attributes`: `visual/sun_angle: 0.0`, which is the
   vanilla `day` timeline's own keyframe at tick 6000, and
   `gameplay/sky_light_level: 15.0`. That last one restates the attribute's
   default rather than changing anything — a museum that is always at noon should
   say so, not inherit it. Everything else is the vanilla overworld dimension type
   verbatim, ambient cave sounds, music and bed rules included. The visible result
   is deviation 2 unchanged; only its spelling moved. Worth a line in the register
   if the register is being revised.
2. **The legacy world string is `embassies`, not `mctraveler:embassies`.** Nucleus
   serialised `region.world = world.name`, so its `regions.json` embassy entries
   read `"world": "embassies"`. `RegionWorlds` maps the dimension to that string
   explicitly (it would otherwise have fallen through to the dimension id), which
   is what lets ticket 05 import those twenty regions unchanged and ticket 02
   create new ones under the same name. `locateInfo("embassies")` returns
   `"embassies"` — it is in no World, so there is no `server/dimension` to render.
3. **The biome is a climate/visual clone of plains, not a whole copy.** It carries
   plains' `temperature`, `downfall`, water colour and sky colour, with
   `has_precipitation: false` and empty `spawners`/`spawn_costs` (deviations 1 and
   15). Its `carvers` and `features` are empty rather than plains' lists: the flat
   generator runs with `features: false` over a single air layer, so copying two
   hundred lines of feature ids would have been decoration that never executes.
4. **The guard seam is `RegionsFeature`, not `RegionService`.** The service stays a
   pure tree over a file (its unit tests are untouched and a restart cannot lose a
   guard), and `RegionsFeature` was already the one place both the block-shaped and
   player-shaped lookups met. `/rg end`'s two corner lookups were moved onto it as
   well, which is what makes `/rg create` refuse inside embassies with Nucleus's
   own "You are not a member of the parent region" — covered by a gametest.
5. **Disconnect hangs off `ServerPlayerEvents.LEAVE`, not
   `ServerPlayConnectionEvents.DISCONNECT`.** LEAVE is injected at the head of
   `PlayerList.remove`, one statement ahead of `save(player)`, so the teleport home
   is what the save writes; DISCONNECT fires from `Connection.handleDisconnection`,
   which the gametest logout path does not go through at all.
6. **Void-fall is a once-a-tick sweep** over the players in the dimension, not a
   movement hook — the same shape as `RegionTracker`'s sweep, and it catches a
   player who got under the world by any means. The threshold is Nucleus's
   `y < -64`, which is this dimension type's `min_y`; vanilla's own out-of-world
   damage does not start until −128, so the return always wins.
7. **`GameTestJanitor` is unchanged.** The dimension's (empty) region folder is
   written under the gametest run directory, but no test depends on it being
   absent and nothing in this ticket writes blocks there — the one test that places
   a block to dig at puts it back. Ticket 02 builds real plots and should re-check
   this.
8. **`EmbassiesFeature.returnEveryoneInside` is called directly by its gametest**,
   since a gametest cannot stop the server; only the `SERVER_STOPPING`
   registration line itself goes unreached. That test reaches for every player on
   the server, and tests inside a batch run side by side, so it ships its own
   test environment (`src/gametest/resources/data/mctraveler-test/test_environment/alone.json`,
   a copy of `minecraft:default`) to get a batch to itself. Any later test that
   sweeps all players wants the same treatment.
9. **A shared test helper moved.** The "teleport a test player and then send the
   acknowledgements a real client would" dance existed as `TestPlayer.moveTo`;
   it is now the top-level `ServerPlayer.arriveIn` in `TestPlayers.kt`, which
   `moveTo` delegates to. It matters more than it looks: vanilla holds a player
   invulnerable until their client acks the move, so a damage test that skips it
   passes with the feature deleted.

### For whoever picks up ticket 02

- Entering embassies from anywhere records the origin automatically — do not add
  bookkeeping to `/embassy create`, just teleport.
- A player standing in the void resolves to `worldRegion`, whose `flags` contain
  `NO_SCOREBOARD` and whose `members` are empty. `RegionTracker.regionOf` returns
  it, so guards phrased as "you must be in an embassy" must test the `EMBASSY`
  flag rather than "a region exists here".
- `worldRegion` is a single shared instance with a fixed 0/0 footprint. It is
  returned by position, never by containment — do not add it to the tree, and do
  not read its corners.
- It is also mutable, and it is what `RegionTracker.regionOf` hands an admin
  standing in the void: `/rg rename` or `/rg flag` there would change the one
  shared instance for the rest of the process (nothing persists — it is not in
  the tree, so a restart undoes it). Nucleus had exactly this hole and no
  message for it, so this ticket left it alone; if ticket 02 wants it closed,
  the natural place is the `/rg` guard ladder that already refuses EMBASSY
  regions.
