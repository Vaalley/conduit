# Embassies is an out-of-trio dimension, not a third World

A World in this codebase is a trio of dimensions with Travel, a Per-World Bucket, and
Position Memory. The Nucleus-era embassies place is none of those things: it is a
single showcase dimension entered only by teleport (crystal menu, `/embassy create`,
admin tp) and always exited back to where you came from. We decided
`mctraveler:embassies` is registered as a plain datapack dimension outside every trio:
`Worlds.worldOf` returns null for it, `/switch` ignores it, and no bucket is ever
written for it. The embassy origin tracker (record on entry, return on
void-fall/disconnect/server-stop) is the only position bookkeeping, and it is
in-memory only, matching Nucleus's WeakHashMap.

## Consequences

- Player state inside embassies is transient by design; a crash while a player is
  inside loses only their return trip (they wake up in embassies with no origin, as
  on Nucleus).
- The fabric-port spec's "no third World" out-of-scope line stays true; anything that
  later wants embassies in `/switch` must revisit this ADR, not special-case Worlds.
- Death in embassies would route through vanilla respawn (Primary), but all player
  damage is cancelled there, so the path is unreachable in normal play.
