# Spec: `/dragonfight` — a private first Ender Dragon, then `/rtp end`

Status: ready-for-agent

On a shared server the End is "done" the moment one player kills the dragon.
Every later player finds a dead dragon, an open exit portal and picked-over
gateways. This feature gives each player one real first fight, alone, in a
throwaway End of their own — and, once they have won it, a way into the real
End far from everyone else.

Companion docs: `../fabric-port/spec.md` (house conventions), `CONTEXT.md`
(vocabulary), `docs/adr/0003-embassies-out-of-trio-dimension.md` (the model for
a dimension nobody lives in).

## Vocabulary

- **Arena** — a temporary End dimension `mctraveler:arena_<owner uuid, no dashes>`
  created at runtime for one player. Same generator and dimension type as
  `minecraft:the_end`, so vanilla runs a real `EnderDragonFight` in it
  (`DimensionType.hasEnderDragonFight()`), but with a world border of
  `borderRadius` (default 300) around 0,0 so the outer islands — and their
  elytra — are unreachable.
- **Owner** — the player the arena was created for. Exactly one arena per owner.
- **Guest** — a player the owner invited; may enter and fight but cannot complete.
- **Completed** — the owner has left their arena through its exit portal. Permanent
  (admin `reset` aside). Unlocks `/rtp end`; locks `/dragonfight` forever.

## User stories

1. As a player who has killed 10 endermen and 10 blazes, I want `/dragonfight` to
   warn me what is about to happen and, on `/dragonfight confirm`, drop me into
   a fresh End where the dragon is alive.
2. As a player, I want to fight the dragon, collect the XP and the egg, and leave
   through the exit portal to my bed/spawn — and never be able to use the small
   gateways the dragon leaves behind.
3. As a player who died mid-fight, I want `/dragonfight` to put me back into the
   *same* arena (my drops and the damaged dragon are still there).
4. As an owner, I want `/dragonfight invite <player>` so a friend can `/dragonfight
   join <me>` and fight alongside.
5. As a player who completed the fight, I want `/rtp end` to teleport me 200k–2M
   blocks out into the real End, safely, at most once every 30 minutes.
6. As an admin, I want `/dragonfight reset <player>` to delete their arena and
   forget their completion so they can do it again.

## Rules

### Eligibility and entry

- Requirements are vanilla stats: `Stats.ENTITY_KILLED` for `ENDERMAN` ≥
  `requiredEndermanKills` and `BLAZE` ≥ `requiredBlazeKills` (both default 10,
  `dragonfight.json`). Not met → `ERROR Prove yourself first: endermen 3/10,
  blazes 0/10`.
- Completed → `ERROR You have already freed the End. Try /rtp end`.
- Already owns an arena → re-enter it (see *Entering*), no warning, no
  requirements check. Already inside it → `ERROR You are already in your dragon
  fight`.
- Otherwise `/dragonfight` prints the warning (multi-line, `Paint`): you will be
  alone in a private End; the border keeps you near the island; the dragon is
  real; dying sends you home and `/dragonfight` brings you back; leaving through
  the exit portal ends it forever and the world is deleted; the egg is yours; you
  then get `/rtp end`. Ends with a clickable `[Confirm]` running `/dragonfight
  confirm`. Confirmation is valid 60 s; `confirm` without a pending warning →
  `USAGE /dragonfight`.
- `confirm` creates the arena, then enters it.
- `/dragonfight` may be run from any dimension except an arena that is not yours
  (`ERROR Leave this dragon fight first`). No `TeleportCountdown` — there is a
  confirmation step already; re-entry also goes without a countdown.

### Entering

- Landing = vanilla's `ServerLevel.END_SPAWN_POINT` (100, 49, 0) with
  `EndPlatformFeature.createEndPlatform(level, pos.below(), true)`, facing west,
  as the vanilla End portal does. Uses `Landing.send`.

### Inside the arena

- World border: centre 0,0, size `2 * borderRadius`, warning distance 5,
  set on the level before anyone enters; `PlayerList.addWorldborderListener`.
- End gateways do nothing: `EndGatewayBlock.getPortalDestination` returns null for
  any entity in an arena (players get `ERROR The gateways are sealed here`,
  throttled to once per 3 s per player).
- The exit portal (`EndPortalBlock.getPortalDestination` in an arena):
  - non-player entities → null (no item ever leaves).
  - guest → their respawn (`findRespawnPositionAndUseSpawnBlock(false,
    DO_NOTHING)`), nothing recorded.
  - owner → same destination, plus: **completed** is recorded; if a
    `DRAGON_EGG` block still stands within the 5×5 column above the exit portal
    (y from portal to +12) it is removed and one dragon egg item is added to the
    owner's inventory (dropped at their feet in the new level if full); the arena
    is scheduled for deletion. `SUCCESS You have freed the End. /rtp end is
    yours now`.
- Deletion: on the next server ticks, any player still inside (guests, or the
  owner if the teleport was refused) is sent to their respawn with `ERROR This
  dragon fight is over`; when the level is empty it is removed from the server
  and its folder deleted.
- Death: vanilla (respawn at bed/spawn). Arena untouched.
- `/dragonfight leave`: anyone inside an arena → their respawn. Guests use it to
  go home; the owner's arena stays.

### Guests

- `/dragonfight invite <player>`: sender must own an arena; target must be online
  and not the sender. Adds to the arena's guest list (in memory + state file).
  Target gets `<owner> invited you to their dragon fight — /dragonfight join
  <owner>`.
- `/dragonfight join <owner>`: sender must be on that arena's guest list and the
  arena must exist → enter. Completed players may be guests.

### Lifetime

- Arenas do not survive a server restart (decided: rare enough). At
  `SERVER_STARTED` every arena entry is dropped from the state file and every
  `dimensions/mctraveler/arena_*` folder under the world save is deleted.
  Completion records survive.
- TTL: `arenaTtlHours` (default 168). A tick sweep (every 1200 ticks) deletes
  any arena older than the TTL that has nobody inside. An arena with people
  inside is left alone until it empties.
- `/dragonfight reset <player>` (admin, offline names allowed via the name
  cache): evicts and deletes their arena if any, forgets completion, forgets
  any pending confirm. `SUCCESS <player> can fight the dragon again`. Not found
  → `ERROR Unknown player <name>`.

### `/rtp end`

- Requires completed → else `ERROR Free the End first (/dragonfight)`.
- Works from the overworld or the End. Destination level is `minecraft:the_end`.
- Ring around 0,0: `endMinDistance` (200 000) .. `endRadius` (2 000 000) from
  `rtp.json`, uniform over area like `/rtp`. Candidate columns are picked with
  the same `RtpPicker` safety rules (solid dry ground, headroom, inside border,
  unclaimed); void columns (heightmap at min y) are rejected. Up to 25 attempts
  (the outer End is mostly void), then `ERROR No safe spot was found, please try
  again`.
- Separate cooldown bucket, `endCooldownSeconds` (1800), admins exempt, same
  `TeleportCountdown` and messages as `/rtp` (`You can use /rtp end again in
  29m 12s`).
- The way back is `/spawn1` / `/spawn2` (free crystal spawns), already available.

## Non-goals

- Barrier-block shells (the border does the job), per-arena seeds, arenas
  surviving restarts, any change to the shared End or its dragon.

## Config

`mctraveler/dragonfight.json`:
`{ "requiredEndermanKills": 10, "requiredBlazeKills": 10, "borderRadius": 300, "arenaTtlHours": 168 }`

`mctraveler/rtp.json` gains:
`"endRadius": 2000000, "endMinDistance": 200000, "endCooldownSeconds": 1800`

State `mctraveler/dragonfight-state.json`:
`{ "completed": ["<uuid>", ...], "arenas": { "<owner uuid>": { "createdAt": <epoch ms>, "guests": ["<uuid>"] } } }`
