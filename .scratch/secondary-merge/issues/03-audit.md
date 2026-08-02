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

**Status:** done

- [x] Every relocated chunk is walked and every coordinate-bearing field examined
- [x] A structural coordinate still pointing into Secondary's old footprint fails the
      merge, names what and where it was, and leaves the live save untouched
- [x] Structural coverage includes chunk positions, block entity positions, scheduled block
      and fluid ticks, structure starts and references, entity positions, the tile
      positions of item frames and paintings, brain memories, and point-of-interest records
- [x] Lodestone compass targets are retargeted wherever they are found, including inside
      containers and inside containers nested within containers
- [x] Command blocks whose commands contain literal coordinates are reported with their
      position and their command text, and never rewritten
- [x] Every brain memory naming a bed, workstation or meeting point has a matching
      point-of-interest record at the same position, or the merge fails naming the villager
      and the place
- [x] The report separates what was repaired automatically from what needs an operator
- [x] A test fixture containing one of every coordinate-bearing thing — a stocked chest, a
      villager with a job site, an item frame, a painting, a leashed animal, a lodestone
      compass nested inside a shulker box inside a chest — passes the audit after a real
      relocation *(closed by ticket 16, which fixed the relocation this was waiting on)*
- [x] A test that deliberately leaves one structural coordinate stale proves the merge
      refuses and writes nothing

## Comments

### Implementation summary

- `src/main/kotlin/eu/mctraveler/importer/ChunkAudit.kt` — the whole phase:
  `StaleCoordinate`, `LiteralCoordinates`, `ChunkAuditReport` and
  `ChunkAudit(stagedLevelDir, placement).run()`.
- `src/test/kotlin/eu/mctraveler/importer/CoordinateBearingChunks.kt` — a chunk of
  Secondary holding one of every coordinate-bearing thing, written into the region files
  `SyntheticChunks` already laid out.
- `src/test/kotlin/eu/mctraveler/importer/WorldMergeAuditTest.kt` (14 tests), driving the
  merge command end to end against `MergedDeploymentFixture`.
- `MergeStaging.kt` gained one line in `write()` and one `MergeReport` getter;
  `SyntheticChunks.kt` gained a public `write(folder, type, dimension, chunks)` overload
  that ticket 04 can build its own rich chunks with.

### The finding: MCA Selector 2.8's relocation is incomplete for 26.2

**The last acceptance criterion is not met, and cannot be until the relocation is fixed.**
The audit works; what it finds is that the relocation does not. Four kinds of coordinate
arrive in Primary still naming Secondary, verified by relocating for real and reading the
chunks back:

| what | 26.2 writes | MCA Selector 2.8 relocates |
| --- | --- | --- |
| a leash tied to a fence | `leash`, an int array of three | `Leash`, a compound of `X`/`Y`/`Z` |
| an item frame's tile | `block_pos` | `TileX`/`TileY`/`TileZ` |
| a painting's tile | `block_pos` | `TileX`/`TileY`/`TileZ` |
| a villager's `minecraft:home`, `job_site`, `meeting_point` | `{value:{dimension,pos}}` | reads `pos` off the memory itself |

The first three are 1.21.5's `InlineBlockPosFormatFix` renames, which the tool has not
followed despite its 2.8 notes stating "Updated mappings for Minecraft 26.2". The fourth is
older: `ExpirableValue` has wrapped a memory in `value` for many versions, and
`ChunkFilter_19w11a$Relocate.applyOffsetToVillagerMemory` still looks one level above it.

So a real merge refuses today on any Secondary chunk holding a leashed animal, an item
frame, a painting or a villager — which is most of them. That is the audit doing its job,
but it is a **relocation** defect and needs its own ticket before the runbook can promise a
run that completes. The two candidate fixes are finishing those fields inside
`ChunkRelocation` after the tool has run, or moving to a version of the tool that has
caught up.

`WorldMergeAuditTest` pins each of the four down by name, so the day the relocation learns
them those tests are what say so.

**Closed by ticket 16**, which patched the tool rather than working around it. The four
fields now arrive relocated and the last acceptance criterion above is met: the fixture
passes the audit after a real relocation. The two tests that asserted a refusal now assert
arrival, and they carry both spellings at once so an additive fix is what they prove.

The finding above understated one thing, worth recording because it is the sort of bug that
hides: the villager memories were not merely read at the wrong depth. `ChunkFilter_25w15a`
dereferences a static `Relocate.instance` that `VersionHandler` never assigned, so at 26.2
*every* entity threw NPE partway through its relocation and the per-entity `catchAndLog`
swallowed it. The relocation reported success having half-relocated every entity in the save.
The fields that appeared to work were simply the ones handled before the throw.

One field of the same kind is still not relocated and was deliberately left: a bee's
`hive_pos`. It is what `one structural coordinate left behind fails the whole merge and
writes nothing` now leaves stale, so this suite still proves the refusal path over something
real rather than something invented.

### Judgement calls

1. **Structural leftovers refuse; they are not repaired.** The merge spec and this ticket
   both say so, and the audit could trivially apply the same offset it applies to a
   lodestone target. It deliberately does not: an audit that patches over the relocation's
   gaps stops being able to tell anyone the gaps are there, and the finding above is worth
   more than a green run.
2. **Coordinates are found by shape, not by key path** — the same reasoning ticket 06 used
   for a player's global positions. An int array of exactly three is `BlockPos.CODEC`, so
   one rule reaches a leash knot, a point-of-interest record, a villager's memory and a
   compass target however deeply nested; four would be a uuid, which is the only int array
   in this format that is not a place. A compound of `x`/`y`/`z` ints is the block entity
   and tick spelling. The chunk's own frame and its structures are read by name because
   each has a spelling of its own.
3. **`Pos` is the only list of three doubles that is a place.** `Motion` is a velocity, and
   a velocity near the origin reads as a coordinate inside Secondary's old footprint every
   single time — a false refusal an operator could do nothing about.
4. **The arbitrary-NBT escape hatches are not scanned for bare positions**
   (`minecraft:custom_data`, `entity_data`, `block_entity_data`, `bucket_entity_data`),
   matching ticket 06 exactly. A bucketed axolotl's stored position is not a place anyone
   stands, and the merge does not move it either, so flagging it would refuse over nothing.
5. **A leftover is a coordinate inside Secondary's old footprint**, measured with
   `RegionFileArea.containsBlock` as ticket 01 endorsed — over-inclusive at region-file
   granularity, which errs safe. The one case it cannot answer is an offset small enough
   that the landed footprint overlaps the old one; the placement search makes that
   unreachable in practice, because it only ever lands Secondary clear of Primary's chunk
   data and Primary's data is what covers Secondary's old ground.
6. **The command-block detector is deliberately generous.** Three coordinate tokens in a
   row with at least one absolute — vanilla's own grammar, so `/tp @p 85 64 53` is caught
   and `/setblock ~ ~-1 ~ stone` is not. A wrong guess costs one extra line in a report and
   never a rewrite.
7. **A chunk the audit had nothing to do for is never re-encoded.** Only chunks whose
   lodestone targets were retargeted are written back, so the bytes MCA Selector produced
   are the bytes the merge commits — which is what lets ticket 04 diff relocated terrain
   against its source without this phase standing in the middle of it.
8. **The cross-check is asked once per dimension**, after its points of interest have all
   been read, because a villager's bed is routinely in another chunk and sometimes in
   another region file.

### Things the later tickets and the runbook must know

- **`MergeReport.audit` is the section**, and `ChunkAudit` runs immediately after
  `ChunkRelocation` in `MergeStaging.write()` — before the Regions and player sweeps, so a
  broken relocation costs nothing but the relocation.
- **The audit reads and repairs the *staged* chunk data**, never the live save. A refusal
  leaves the run directory exactly as it was found.
- **`SyntheticChunks.write(folder, type, dimension, chunks)`** is public now: ticket 04
  should build its rich chunks with it rather than a second chunk writer, and
  `CoordinateBearingChunks` is a worked example.
- **Two report keys are new**: `chunks audited`, `coordinates checked`, `repaired
  automatically`, `needs an operator`, and one indented `command block` line per literal
  command. The runbook should say that the last of those is an action list for after the
  server is back up, not a refusal.
- **Books and signs naming Secondary are not scanned.** The spec's "Audit" note lists book
  text as cosmetic but "Out of Scope" says written books and signs are unfixable, and the
  latter is what this follows.
- **The villager cross-check refuses on a pairing that was already broken before the
  merge**, not only on one the merge broke. That is what the ticket asks for, and it is a
  refusal an operator cannot fix from the message alone; if it ever fires in a rehearsal,
  compare against the source save before assuming the merge did it.
