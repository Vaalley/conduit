# 03 — Teleportation Crystal item, energy, recipes

**What to build:** The crystal item (Echo Shard + `CUSTOM_DATA` marker + tier,
Notepad idiom), per-player energy behind `PlayerStore` (energy + next-regen-at typed
pairs), the play-time-driven regen loop, the viewer-relative damage display (outgoing
container packet rewrite), the three datapack recipes with the server-side crafting
guard, and `/set-teleportation-crystal-energy`.

**Blocked by:** none.

**Status:** ready-for-agent

See ../spec.md (User Stories 20–25, 37; Implementation Decisions "Crystal item",
"Energy", "Recipes"; deviations 4, 5, 7, 10, 12) and the Nucleus source
`teleportation-crystal.kt` (item/lore/tier constants, `modifyEnergy`/`getEnergy`/
`getNextRegenAt`, `updateItemEnergy`, `initTeleportationCrystal` recipes + packet
adapters + regen task, `onCraft`, `SetTeleportationCrystalEnergyCommand`) in the
reference clone at /Users/jam/Development/MCTravelerNucleus.

- [ ] Crystal stacks identified by custom data; name, lore, glint, max stack 1, max
      damage = tier per spec story 22; tiers 1–3 constructible in code
- [ ] Energy 0–3 (default 3) persisted per player via PlayerStore; unknown-field
      pass-through in players/*.json intact; regen per story 25 driven by
      `Stats.PLAY_TIME` thresholds, checked every 20 ticks, message on each point;
      dropping below 3 arms the first threshold exactly once (Nucleus `modifyEnergy`
      semantics)
- [ ] Outgoing set-slot and set-content packets rewrite crystal stacks to
      damage = 3 − viewer's energy (story 23); stored stacks never mutated; verified
      at the packet level in a gametest
- [ ] Recipes: tier 1 shapeless (ender eye), tiers 2/3 plus-pattern per story 20;
      guard blocks a plain echo shard centre and blocks crystals as ingredients in
      any foreign recipe (story 21, recovery compass case tested)
- [ ] `/set-teleportation-crystal-energy` per story 37 (USAGE before admin gate,
      house rule; feedback to sender, deviation 5)
- [ ] GameTestJanitor updated if any new persisted file appears
- [ ] Gametests: crafting all three tiers + both guard cases, regen over
      fast-forwarded play time, damage-bar packets, admin command ladder; unit tests
      for pure energy/threshold logic and store round-trip

## Comments
