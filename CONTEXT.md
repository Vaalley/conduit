# MCTraveler

MCTraveler is a community Minecraft survival server whose custom gameplay is being ported from a standalone TypeScript proxy (the Portal) to a server-side Fabric mod, collapsing a two-server topology into one server with one continuous map.

## Language

**The map**:
The vanilla trio of dimensions — overworld, nether, end — which is everywhere players live, build and respawn. There is one of each. The server once ran two such trios, called Worlds, and the terms for that topology (World, Travel, Per-World Bucket, Position Memory) are retired: see ADR 0004 and `docs/merge.md`.
_Avoid_: World, trio (for the map as a whole), server (for a place players play in)

**Secondary**:
The landmass that was once a World of its own, now relocated into the map at a fixed offset and reachable on foot. It is a place with a known footprint — not a destination, not a dimension, and with no spawn or per-player state of its own. Primary is not a term at all any more; it is simply the map.
_Avoid_: the Secondary World, the second World, Secondary's dimensions

**The merge**:
The one-time offline operation (`mergeWorlds`) that relocated Secondary's chunk data into the map, rewrote everything that recorded a place in it, and discarded Secondary's End. It stamps `mctraveler/merge.json` with the offset it applied, which the claim path reads for the life of the quarantine.
_Avoid_: the migration (that is the Portal cutover), the import (that is Nucleus)

**Region**:
A player-owned protected cuboid in a dimension, with members, flags, and optional sub-regions. Protection applies to player actions and (since the port) environmental damage. Regions record their dimension as one of the Portal's legacy world strings — `world`, `world_nether`, `world_the_end`, plus `embassies` — and that is stored-data compatibility, deliberately kept, **not** a surviving World concept. Do not "clean it up".

**Teleportation Crystal**:
A craftable hand-held teleporter: a re-skinned Echo Shard, identified by a custom-data marker, in three tiers. Each tier has a charge capacity — 1, 3, or 5 — which determines how far into an empty Energy pool it still works.
_Avoid_: crystal item, teleporter (ambiguous with the embassy anchors)

**Energy**:
The pool of 0–5 teleport charges a player carries, shared by every Teleportation Crystal they own and recharging one point per 15 minutes of play time. Shown to each player as the damage bar of every crystal they see, so the same crystal reads differently for different viewers.
_Avoid_: charges, durability (the damage bar is a display of Energy, not wear)

**Admin**:
A player with vanilla server operator status. The port keeps no separate admin flag; vanilla /op and /deop are the management commands.
_Avoid_: isAdmin (the Portal's stored flag)

**Intent Parity**:
The port's fidelity policy: reproduce what the Portal's code plainly meant, not its bugs. Message formats and deliberate behaviours are identical; outright bugs are fixed; every deviation is listed in the spec's deviation register.
_Avoid_: bug-for-bug parity

**Portal**:
The legacy system being ported away from — the standalone TypeScript/Bun proxy that sat between clients and the backend servers.
_Avoid_: proxy (ambiguous once the Fabric mod exists)

**Nucleus**:
The pre-Portal legacy system — the MCTravelerNucleus Paper plugin. Source of the Embassies and Teleportation Crystal features and of their era's data (embassies world, embassy regions, crystal energy).

**Embassies**:
The plot-museum dimension (`mctraveler:embassies`): flat void, admin-allocated 11×11 plots on a chunk spiral, each an EMBASSY-flagged region with a respawn-anchor teleporter to its saved destination. A dimension outside the map, not part of it (ADR 0003) — entered and left only by teleport, with the player's origin restored on void-fall, disconnect, or server stop, and no per-player state persisted for it.
_Avoid_: the embassy world, a place players live
