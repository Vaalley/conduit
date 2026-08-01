# 03 — Auditing the relocated chunks

**What to build:** Proof that the relocation was complete. Every relocated chunk is walked
and every coordinate it carries is classified, so the merge can state — rather than hope —
that nothing still points into the place Secondary used to be.

Two tiers, because the consequences differ. A **structural** leftover means the map is
broken: a chest that cannot be opened, a villager pathing into nowhere, a scheduled tick
firing on the wrong block. Any of those fails the whole merge and nothing is written. A
**cosmetic** leftover degrades gracefully — a lodestone compass that reads as uncalibrated,
a command block that sends someone to the wrong place. Those are repaired where we know
how and reported where we do not, so one odd command block written in 2019 cannot block a
cutover at 2am.

Self-consistency is not correctness, so the audit also cross-checks the one invariant that
spans two files: a villager remembering a workstation or a bed must find a matching
point-of-interest record at the same place, or the whole trading hall is quietly dead.

**Blocked by:** 02 — Relocating Secondary's chunk data.

**Status:** ready-for-agent

- [ ] Every relocated chunk is walked and every coordinate-bearing field examined
- [ ] A structural coordinate still pointing into Secondary's old footprint fails the
      merge, names what and where it was, and leaves the live save untouched
- [ ] Structural coverage includes chunk positions, block entity positions, scheduled block
      and fluid ticks, structure starts and references, entity positions, the tile
      positions of item frames and paintings, brain memories, and point-of-interest records
- [ ] Lodestone compass targets are retargeted wherever they are found, including inside
      containers and inside containers nested within containers
- [ ] Command blocks whose commands contain literal coordinates are reported with their
      position and their command text, and never rewritten
- [ ] Every brain memory naming a bed, workstation or meeting point has a matching
      point-of-interest record at the same position, or the merge fails naming the villager
      and the place
- [ ] The report separates what was repaired automatically from what needs an operator
- [ ] A test fixture containing one of every coordinate-bearing thing — a stocked chest, a
      villager with a job site, an item frame, a painting, a leashed animal, a lodestone
      compass nested inside a shulker box inside a chest — passes the audit after a real
      relocation
- [ ] A test that deliberately leaves one structural coordinate stale proves the merge
      refuses and writes nothing
