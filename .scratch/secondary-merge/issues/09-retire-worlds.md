# 09 — Retiring the Worlds subsystem

**What to build:** The server stops having Worlds. This is the contract half of the
expand–contract pair begun in ticket 08, and it is a wide refactor: one decision whose blast
radius reaches the dimension resources, the respawn and portal routing, the persistence
model, the Region layer's world vocabulary, and a large number of currently green tests.

Travel goes, and with it the Per-World Bucket, Position Memory, per-World respawn points and
the World-to-dimension resolution that made all three meaningful. The Secondary dimension
resources are removed from the mod so nothing can load them again. Vanilla's respawn and
portal routing stop being translated and go back to being vanilla's. The Region layer keeps
its legacy world vocabulary — migrated data still reads unchanged — but loses its Secondary
entries, the guard that refused a Region spanning two Worlds, and the half of its location
reporting that named a server that no longer exists.

What must not be removed: the value types describing somewhere to go and somewhere
remembered. They live in the same package as the Worlds service but belong to the
Teleportation Crystal and the Embassies, and both still need them.

**⚠️ Deployment hazard.** The build this ticket produces must not reach production until
`mergeWorlds` has actually run. Deployed early, it removes two dimensions whose chunk data
is still inside them. The runbook (ticket 12) gates on this.

**Blocked by:** 07 — The End, and everything anchored in it; 08 — `/switch` becomes a
signpost.

**Status:** ready-for-agent

- [ ] The Worlds service, Travel, and World-to-dimension role resolution are gone
- [ ] The Per-World Bucket, Position Memory and per-World respawn points are gone from the
      persistence model, while every other field in a player record still passes through
      byte for byte
- [ ] The Secondary dimension resources no longer ship in the mod
- [ ] Respawn and portal routing are vanilla's own again, with no trio translation
- [ ] The Region layer's Secondary world entries, its two-World creation guard, and the
      server half of its location reporting are removed
- [ ] The value types for a destination and a remembered place survive, and the crystal and
      the Embassies still work
- [ ] The Worlds gametests are retired; the respawn, portal and tab list gametests keep
      their single-World cases and lose their cross-World ones
- [ ] The production smoke check asserts the dimensions that now exist and fails if a
      Secondary dimension reappears
- [ ] The full suite is green
