# 05 — Respawn-in-World + portal routing

**What to build:** Death never crosses Worlds, and each World is a self-contained trio: respawn points are per-World (part of the Per-World Bucket), and nether/end portals route within the player's current trio.

**Blocked by:** 04 (Worlds + /switch).

**Status:** ready-for-agent

See `../spec.md` (User Stories 22–23, Implementation Decisions: Per-World Bucket) and ADR 0001.

- [ ] A bed/respawn anchor set in a World applies only to deaths in that World; dying without one in the World of death respawns at that World's spawn
- [ ] Dying in Secondary with a bed only in Primary respawns the player in Secondary (at its spawn), never in Primary
- [ ] Nether portals in Secondary's overworld lead to Secondary's nether and back; end portals likewise; Primary's trio behaves vanilla
- [ ] Gametests: cross-World bed scenario, same-World bed honored, portal round-trips in both Worlds
