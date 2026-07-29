# 05 — Respawn-in-World + portal routing

**What to build:** Death never crosses Worlds, and each World is a self-contained trio: respawn points are per-World (part of the Per-World Bucket), and nether/end portals route within the player's current trio.

**Blocked by:** 04 (Worlds + /switch).

**Status:** done

See `../spec.md` (User Stories 22–23, Implementation Decisions: Per-World Bucket) and ADR 0001.

- [x] A bed/respawn anchor set in a World applies only to deaths in that World; dying without one in the World of death respawns at that World's spawn
- [x] Dying in Secondary with a bed only in Primary respawns the player in Secondary (at its spawn), never in Primary
- [x] Nether portals in Secondary's overworld lead to Secondary's nether and back; end portals likewise; Primary's trio behaves vanilla
- [x] Gametests: cross-World bed scenario, same-World bed honored, portal round-trips in both Worlds

## Comments

Key decisions, for later tickets (13 region membership, 18 importer):

- **Bucket schema addition** (`eu.mctraveler.persistence.PerWorldBucket`): one new optional field, `respawn: RespawnPoint?`, persisted as a nested `"respawn"` object inside each World's bucket slice in `players/<uuid>.json`:
  `"worlds":{"secondary":{"dimension":"overworld","x":…,"y":…,"z":…,"yaw":…,"pitch":…,"respawn":{"dimension":"overworld","x":1,"y":2,"z":3,"yaw":0.0,"pitch":0.0,"forced":false}}}`.
  `RespawnPoint.dimension` is the same trio-relative role id (`"overworld"|"nether"|"end"`) the bucket uses; x/y/z are the **block** the bed or anchor stands on (ints, vanilla's `RespawnData` pos); `forced` is vanilla's "needs no block behind it" flag (what `/spawnpoint` sets, as opposed to sleeping). Absent `"respawn"` = no bed set in that World. **Ticket 18** writes respawn tags through `PlayerStore.setBucket` like any other bucket field — one respawn per World, taken from that World's legacy playerdata; a legacy respawn dimension must be mapped to its role, not to a dimension id.
- **Respawn lifecycle**: the *live* vanilla `ServerPlayer.respawnConfig` is always the current World's — `Worlds.travel` saves it into the origin World's bucket and `place()` restores the destination's (null on a first visit, and on the `handleLogin` mismatch path, which now swaps the respawn along with position). Nothing hooks bed/anchor *setting*: vanilla playerdata already persists the live point across logout, so, exactly as ticket 04 decided for Position Memory, the bucket is written only when *leaving* a World.
- **Respawn hook point**: `ServerPlayerRespawnMixin` — `@ModifyReturnValue` on `ServerPlayer.findRespawnPositionAndUseSpawnBlock`. That single method is where vanilla decides every respawn (`PlayerList.respawn` after a death, and `EndPortalBlock` for a player leaving an End), and it runs *before* the player is moved, so there is no visible detour through the wrong World. If the transition it returns lands outside the World the player died in, it is replaced by `Worlds.spawnTransition` — that World's own overworld spawn, built the way `TeleportTransition.createDefault` builds Primary's (same `adjustSpawnLocation` search, and the incoming `missingRespawnBlock` flag preserved so the "no home bed" message and vanilla's clearing of a broken respawn point still behave).
- **Portal hook points**: `NetherPortalBlockMixin` and `EndPortalBlockMixin`, both on `getPortalDestination`, both using the same two-nudge pattern rather than reimplementing vanilla's exit-portal search:
  1. `@ModifyExpressionValue` on every `ServerLevel.dimension()` the method reads → `WorldRouting.asVanillaTrio`, so vanilla's hardcoded `== Level.NETHER` / `== Level.END` comparisons read a dimension's **role** instead of its identity;
  2. `@ModifyArg` on the `MinecraftServer.getLevel(ResourceKey)` argument → `WorldRouting.withinTrio(portalLevel.dimension(), target)`, resolving the key vanilla picked inside the portal's own trio.
  Nudge 1 matters twice in `EndPortalBlock`, where `dimension() == Level.END` decides the *direction* of travel: untranslated, an end portal in Secondary's End would read as an overworld portal and throw the traveller deeper into Primary's End. It also matters in `NetherPortalBlock`, where the second `dimension()` read sets the exit-portal search radius. Coordinate scaling needs nothing: it reads the dimension **type**, and Secondary's nether is a `minecraft:the_nether`.
- **Non-players are covered**, deliberately: `getPortalDestination` routes by the level the *portal block* stands in, not by any player, so items, mobs and minecarts stay in-trio for free. Gametested with a dropped item in both directions.
- **Extension beyond the acceptance boxes** (flagged for review): `EndPortalBlockMixin` also translates the `Level.dimension()` read in `entityInside`, which gates the end-credits sequence. Without it Secondary's End would silently skip the credits and teleport instead — a divergence from "Primary's trio behaves vanilla" applied to Secondary. Six lines, same helper, no new concept.
- **Mixin bridge**: `eu.mctraveler.worlds.WorldRouting` holds the three `@JvmStatic` entry points the mixins call (`asVanillaTrio`, `withinTrio`, `withinDeathWorld`); the topology logic itself stays on `Worlds` (`roleOf`, `withinTrioOf`, `spawnTransition`). Every entry point degrades to the identity when `WorldsFeature.worlds` is null (before SERVER_STARTING) or when the dimension is outside every trio, so the mixins are inert on a server where the service never came up.
- `DimensionRole` gained a `vanilla` key (`Level.OVERWORLD`/`NETHER`/`END`) — the role→vanilla-dimension map both nudges need, which also lets `Worlds.all` build Primary from the roles instead of repeating the trio.
- **Not covered**: respawn *anchors* are handled by the same `RespawnConfig` path and stored identically (role `"nether"`), but are gametested only via beds — charging an anchor in a headless test buys no extra coverage of this ticket's code. Secondary's End has no separate dragon-fight handling; that is out of this ticket's scope.
- Shared files touched with one line each: gametest `fabric.mod.json` (the new test entrypoint) and `mctraveler.mixins.json` (three mixin entries). `MCTraveler.kt` needed nothing — the Worlds feature was already registered.
