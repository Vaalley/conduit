# 04 — Worlds + /switch + Position Memory

**What to build:** The two-World topology on one server: Secondary exists as a datapack-defined trio generation-identical to the overworld, `/switch` Travels the player between Worlds with the Portal's exact messages, Position Memory restores where you last stood, and login routes you to the World you left.

**Blocked by:** 01 (Scaffold), 02 (Text DSL), 03 (Persistence store).

**Status:** ready-for-agent

See `../spec.md` (Implementation Decisions: Worlds, Per-World Bucket), ADR 0001, and the topology section of `docs/research/portal-feature-inventory.md`. Worlds service is built N-capable; the product ships with Primary (the vanilla trio) and Secondary.

- [ ] Secondary trio (overworld-like, nether, end) registered via static datapack dimensions shipped in the mod jar
- [ ] `/switch` toggles the player's World with the exact "Switching to Primary/Secondary..." message and a failure message on error; Travel is near-instant
- [ ] Position Memory (position, rotation, dimension-within-trio) is saved on leaving a World and restored on return; first visit lands at the destination World's spawn
- [ ] Inventory, XP, health, hunger, ender chest, advancements, stats ride with the player unchanged across Travel
- [ ] Logging out and back in returns the player to the World and position they left; brand-new players start in Primary
- [ ] Gametests: switch both directions with message assertions, position-memory restore, login routing, shared-inventory invariant
