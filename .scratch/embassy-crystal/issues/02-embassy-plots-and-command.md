# 02 — Embassy plots, /embassy command, anchor teleporter

**What to build:** Region `metadata` support in the model and `RegionStore` (optional
`"metadata"` object, written only when non-empty, legacy entries byte-identical); the
plot spiral allocator and Nucleus block palette (`populateChunk`); the `/embassy`
command family (`create`/`delete`, ops-gated, exact messages, clickable delete
confirmation); the stand-on-anchor teleport to the region's `embassy-destination`
(with sneak bypass and the admin clickable back-link); and the respawn-anchor
right-click guard inside EMBASSY regions. Upgrade the existing `/rg delete` embassy
refusal so `/embassy delete` is red and clickable.

**Blocked by:** 01 (Embassies dimension).

**Status:** ready-for-agent

See ../spec.md (User Stories 8–19; Implementation Decisions "Region metadata",
"Commands", "Text"; deviations 3, 6, 9, 17) and the Nucleus source `embassy.kt`
(`getNextAvailablePlotCoords`, `populateChunk`, `EmbassyCommand`,
`EmbassyListener.onPlayerMove` anchor branch, `onInteract`) plus `util.kt`
(`teleportWithAdminBack`, `sendAdminBack`) in the reference clone at
/Users/jam/Development/MCTravelerNucleus. Destination worlds are stored as legacy
names — map through `RegionWorlds`.

- [ ] `Region` carries metadata; `RegionStore` round-trips it; a store file without
      metadata is rewritten byte-identically (existing byte-compat tests extended)
- [ ] Spiral allocation: first chunk outward from (0,0) whose centre resolves to the
      synthetic world region; matches Nucleus's leg-lengthening spiral order
- [ ] `populateChunk` reproduces the palette of spec story 10 exactly (assert exact
      blocks, stair facings, slab type, anchor charges in a gametest)
- [ ] `/embassy`, `/embassy create`, `/embassy delete` behave per stories 8, 9, 11,
      16, 17, 18 with exact texts, prefixes, colors, and the clickable confirm
- [ ] Anchor standing teleport per stories 13–14 (block below feet, sneak bypass,
      destination from metadata via legacy world names, admin back-link clickable)
- [ ] Anchor right-click guard per story 15
- [ ] `/rg delete` embassy refusal message gains the red clickable `/embassy delete`
      (story 19); the flag-toggle and subregion guards already in place stay covered
- [ ] Gametests: create (allocation, palette, region, metadata, teleport-in, origin
      recorded), delete (guard ladder, confirm flow, chunk cleared, region gone),
      anchor teleport + sneak + admin back-link, anchor guard; unit tests for the
      spiral and any pure geometry

## Comments
