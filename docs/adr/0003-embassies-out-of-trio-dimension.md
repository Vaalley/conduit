# Embassies is an out-of-trio dimension, not somewhere players live

> **Amended, not superseded, by ADR 0004 (one World).** The Embassies are exactly what they
> always were; what changed is the thing they were originally defined *against*. This ADR was
> written when the server ran two Worlds and said the Embassies belonged to neither. There are no
> Worlds now, so the decision is restated below against dimensions. Nothing about the Embassies
> was decided again.

The map is the vanilla trio — overworld, nether, end — and that is where players live, build and
respawn. The Nucleus-era embassies place is none of those: it is a single showcase dimension
entered only by teleport (crystal menu, `/embassy create`, admin tp) and always exited back to
where you came from. We decided `mctraveler:embassies` is registered as a plain datapack
dimension **outside the map**: nothing routes players into it, nothing routes them out of it
except the return trip, `/switch` says nothing about it, and no per-player state is ever
persisted for it. The embassy origin tracker (record on entry, return on
void-fall/disconnect/server-stop) is the only position bookkeeping, and it is
in-memory only, matching Nucleus's WeakHashMap.

The Region layer stores it under the legacy world name `embassies`, which is the one legacy world
string that does not name a vanilla dimension. That is deliberate and is how `/rg locate` finds
an embassy Region at all.

## Consequences

- Player state inside embassies is transient by design; a crash while a player is
  inside loses only their return trip (they wake up in embassies with no origin, as
  on Nucleus).
- The embassies dimension is not somewhere anyone travels to as part of the map, and
  never was. Anything that later wants it treated as a place players live in must
  revisit this ADR rather than special-case it.
- Death in embassies would route through vanilla respawn, but all player damage is
  cancelled there, so the path is unreachable in normal play.
- The merge left the Embassies untouched except for their saved destinations, which
  were offset like every other place that named somewhere in Secondary. A destination
  that pointed into Secondary's End was cleared rather than left aiming at nothing.
