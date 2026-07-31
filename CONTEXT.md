# MCTraveler

MCTraveler is a community Minecraft survival server whose custom gameplay is being ported from a standalone TypeScript proxy (the Portal) to a server-side Fabric mod, collapsing a two-server topology into one server with multiple Worlds.

## Language

**World**:
One of the overworld-style places players inhabit and travel between — Primary or Secondary today. Formerly a separate backend server; now a trio of dimensions (overworld, nether, end) on the single server.
_Avoid_: server (for a place players play in), dimension (in player-facing language)

**Travel**:
A player's move from one World to another. Restores that player's Per-World Bucket in the destination World.
_Avoid_: server switch, transfer

**Per-World Bucket**:
The player state each World keeps separately: Position Memory, respawn point (bed/anchor), and the dimension within the World the player last occupied. All other player state (inventory, XP, ender chest, advancements, stats) is shared across Worlds.

**Position Memory**:
The per-World record of where a player last stood, restored when they Travel back. Part of the Per-World Bucket.

**Region**:
A player-owned protected cuboid in a World's dimension, with members, flags, and optional sub-regions. Protection applies to player actions and (since the port) environmental damage.

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
The plot-museum dimension (`mctraveler:embassies`): flat void, admin-allocated 11×11 plots on a chunk spiral, each an EMBASSY-flagged region with a respawn-anchor teleporter to its saved destination. An out-of-trio dimension, not a World (ADR 0003) — entered and left only by teleport, with the player's origin restored on void-fall, disconnect, or server stop.
_Avoid_: embassy world as a World, third World

**Teleportation Crystal**:
A craftable Echo Shard (tiers 1–3) that opens the destination menu (Bed, Spawn, Player, Embassy, Wilderness). All of a player's crystals share one 0–3 energy pool shown as the item's damage bar (viewer-relative); one energy regenerates per 15 minutes of play time.
_Avoid_: charge (the respawn anchor's word), mana
