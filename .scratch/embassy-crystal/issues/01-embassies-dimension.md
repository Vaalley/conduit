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

**Status:** ready-for-agent

See ../spec.md (User Stories 1–7; Implementation Decisions "Dimension", "Not a
World", "Synthetic world region", "Origin tracking"; deviations 1, 2, 11, 15) and the
Nucleus source `embassy.kt` (`initEmbassies`, `EmbassyListener.onPlayerMove` /
`onPlayerDamage` / `onPlayerTeleport` / `onPlayerQuit`, `cleanUpEmbassies`) in the
reference clone at /Users/jam/Development/MCTravelerNucleus.

- [ ] Dimension, dimension_type, and biome JSONs ship in the mod jar; the dimension
      loads on a dedicated server and under GameTestServer (the existing datapack
      mixin picks it up); generated chunks are pure air over void
- [ ] Region lookup in the embassies dimension falls back to the synthetic
      "Embassies World" region: sidebar hidden there, modification refused for
      everyone ("This area is protected by Embassies World"), lookup outside the
      dimension unchanged; the synthetic region never reaches regions.json
- [ ] Players take no damage in the dimension (void included)
- [ ] Origin recorded on entering from any dimension outside embassies, by any
      teleport path; consumed on void-fall / disconnect / server stop; cleared on
      leaving; a player with no recorded origin falling below −64 is left alone
      (parity)
- [ ] Disconnect inside embassies saves the player at their origin (they log back in
      there); SERVER_STOPPING returns everyone inside before the save
- [ ] `Worlds`/`/switch` untouched: `worldOf(embassies)` is null and travel/login
      behavior for the two real Worlds is unchanged
- [ ] SmokeHook asserts the embassies dimension; GameTestJanitor updated if this
      ticket adds any persisted file
- [ ] Gametests: dimension exists + void generation; synthetic region protection and
      hidden sidebar; damage cancellation; origin roundtrip (enter → fall → back;
      enter → disconnect → relog at origin); unit tests for any pure pieces

## Comments
