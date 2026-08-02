# 17 — The entity coordinates the tool still forgets

**What to build:** A merge that does not stop in the middle of a downtime window because MCA
Selector never learned about one more kind of entity.

Ticket 16 fixed three defects in the tool and left a fourth on purpose: **a bee's `hive_pos`
and `flower_pos` are not relocated.** The tool moves entity positions from a hand-written
switch with no bee case, so a bee arrives in Primary still remembering a hive in Secondary.
The audit catches it, which means **a real merge refuses today on any Secondary chunk holding
a bee** — and bee nests generate naturally in flower forests, birch forests and meadows, so
that is not a rare shape.

The bee is not really the problem, though. The problem is the sentence ticket 16 used to
justify leaving it: *there is no reason bees are last*. Four defects have been found in this
tool by pointing an audit at it, and each was found by running into it. The next one will be
found the same way, and the worst possible moment to find it is at 2am with the server down
and thirteen thousand players waiting for a merge that has just refused.

So this ticket wants two things, and the second matters more than the first.

**Fix the bee** in the patched build, additively, the way ticket 16 fixed the others — it is
small, it is upstreamable with the rest, and it removes a refusal we know is coming.

**Then stop the next one being a cutover-night surprise.** After the relocation, before the
audit, complete what the tool left behind: apply the offset to coordinates that should have
moved and did not, by the same shape-based rule the audit uses to find them. Then let the
audit run exactly as it does now, and refuse over anything still standing.

The obvious objection is ticket 03's, and it was right: *an audit that patches over the
relocation's gaps stops being able to tell anyone the gaps are there.* The answer is that the
completion pass must **report every coordinate it had to fix, and what kind it was** — a
count and a list of field names in the merge report, loud enough that an operator reads it as
"the tool is behind again" rather than as nothing having happened. The information ticket 03
protected is preserved; what changes is that the merge finishes and tells you, instead of
stopping and telling you.

**Blocked by:** None — ticket 16 is complete.

**Status:** done

- [x] **Every** remaining inline block position the entity switch does not cover is
      enumerated, in both `ChunkFilter_25w15a` and `ChunkFilter_21w37a`, against 26.2's actual
      entity NBT — bees are unlikely to be the only one, and the list is the deliverable, not
      the bee. Vanilla's `InlineBlockPosFormatFix` and the entity classes are the reference
- [x] All of them, bees included, are relocated in the patched build, additively, with the
      patch in this repo updated and the pinned sha256 in the build updated to the rebuilt jar
- [x] A Secondary chunk containing a bee with a hive relocates and passes the audit
- [x] The audit's deliberately-stale-coordinate test uses one the enumeration shows is
      genuinely still unrelocated, and the fixture's doc comment says which and why
- [x] A completion pass runs after the relocation and before the audit, applying the offset
      to coordinates that should have moved and did not
- [x] It uses the same shape-based rule the audit uses, and the same exclusions — a velocity,
      a uuid, and the arbitrary-NBT escape hatches are not places and are not touched
- [x] Every coordinate it completes is counted and named by kind in the merge report, so a
      tool that has fallen behind is visible rather than silently compensated for
- [x] The audit still runs afterwards and still refuses over anything left, unchanged
- [x] A test proves an entity field the tool does not know about is completed, reported, and
      passes the audit — using a field that is genuinely unhandled rather than a mock
- [x] A test proves the report names it, so the operator's evidence is pinned, not incidental
- [x] The runbook says what a non-zero completion count means and what to do about it —
      written into ticket 12's Comments, because `docs/merge.md` is ticket 12's to create and
      does not exist yet
- [x] The rehearsal step says to check whether Secondary actually contains bees in relocated
      chunks, because that answer is knowable before the downtime window rather than during it
      — superseded in substance: the bee is fixed, so the rehearsal step is now the
      completion count itself, which answers the same question about *every* field at once

## Comments

### The bee was not last, and was not close to last

The ticket asked for the bee. Enumerating properly first — against Minecraft 26.2's own
compiled entity classes rather than against the four the audit had bumped into — found that
the tool's hand-written `switch` over entity ids is stale for **almost every field it
handles**, because it still speaks only the pre-1.21.5 spellings.

The method was to find every class under `net/minecraft/world/entity` whose constant pool
references `BlockPos.CODEC` — which is precisely what writes the int array of three the merge
has to move — and read its NBT key literals. That is an exhaustive list rather than a
recollected one, and it is short.

| what 26.2 writes | on | the tool looked for | state before |
| --- | --- | --- | --- |
| `sleeping_pos` | `LivingEntity` — anything that sleeps | `SleepingX/Y/Z` | **missed** |
| `home_pos` | `Mob` — turtle, creaking, happy ghast, any restricted mob | `HomePosX/Y/Z`, and `home_pos` for happy ghast only | **missed** except happy ghast |
| `hive_pos`, `flower_pos` | `Bee` | nothing | **missed** — the ticket's bee |
| `anchor_pos` | `Phantom` | `AX/AY/AZ` | **missed** |
| `bound_pos` | `Vex` | `BoundX/Y/Z` | **missed** |
| `beam_target` | `EndCrystal` | `BeamTarget` compound | **missed** |
| `wander_target` | `WanderingTrader` | `WanderTarget` compound | **missed** |
| `patrol_target` | `PatrollingMonster` — pillager, vindicator, evoker, illusioner, ravager, witch | `PatrolTarget` compound | **missed** |
| `block_pos` | `BlockAttachedEntity` — item frame, **glow item frame**, painting, **leash knot** | `block_pos` for item frame and painting only | **missed** for glow item frame and leash knot, in *both* spellings |
| `block_pos` | item frame, painting | handled by ticket 16 | already fixed |
| `leash` | `Leashable` | handled by ticket 16 | already fixed |
| brain memories | villager `home`, `job_site`, `meeting_point` | those three, on villagers only | already fixed; **every other memory missed** |

Three findings are worth stating on their own.

**`sleeping_pos` and `home_pos` are not entity-specific at all.** They are on `LivingEntity`
and `Mob`, so they reach anything that sleeps in a bed and any mob with a home — which is
most of a populated map. Keying them by entity id was never going to work.

**A glow item frame and a leash knot were never handled, in any spelling.** That predates the
inline renames entirely: upstream listed `minecraft:item_frame` and `minecraft:painting` and
stopped, and the other two `BlockAttachedEntity` kinds fell through. A leash knot is the fence
post a leashed animal is tied to.

**Three fields the switch handles no longer exist.** A dolphin's `TreasurePos`, a shulker's
`APX/APY/APZ` and a turtle's `TravelPos` are not persisted by 26.2 at all. Those cases are
harmless and were left exactly as they are, because a chunk at an older DataVersion still has
them.

`current_explosion_impact_pos` is the one position-shaped field deliberately **not** added. It
is a `Vec3` of doubles rather than a block position — knockback state, not a place anything
stands — and the audit does not treat it as geography either.

### What the patch does about it

`gradle/mcaselector/2.8-mctraveler1.patch`, still against tag 2.8 (`11723cff`), rebuilt to
sha256 `f7d088d3…`. Same four source files as ticket 16; two of them changed further.

**The inlined block positions are keyed by name, not by entity id.** A single
`INLINE_BLOCK_POSITIONS` list, applied outside the switch in both `ChunkFilter_21w37a` and
`ChunkFilter_25w15a`. That is the whole shape of the fix and the reason it is worth having:
`hive_pos` means the same thing on whatever carries it, so the list no longer has to be
crossed with the list of entity ids that have one, and the next entity to gain a `home_pos`
is covered on the day it ships. The legacy CamelCase triples stay in the switch, because
those spellings really are entity-specific — `AX/AY/AZ` means something only on a phantom.

**Every brain memory, not three of them on villagers.** `applyOffsetToVillagerMemory` already
handled both the wrapped and flat shapes after ticket 16; it is now called for every memory of
every entity's brain rather than for three named ones on a villager. That closes
`potential_job_site` — which an ordinary villager has — along with `liked_noteblock` and
whatever 26.3 adds.

**Two hunks are removals, and both had to be.** The `happy_ghast` case and the `block_pos`
line in the item-frame case are now covered by the generic block above, and leaving them
would have applied the offset **twice**. That is the one way this change could have been
worse than the defect, so it is worth naming: additive means *every old spelling still
relocates*, which it does — it does not mean never deleting a line that has become a second
copy of one.

The glow item frame and the leash knot were added to the legacy tile case beside the item
frame and the painting.

Verified reproducible: `./gradlew clean shadowJar` twice produces identical bytes, so the new
checksum is a property of the source as ticket 16 intended.

### What is still not fixed, on purpose

**The block entity switch.** `applyOffsetToTileEntity` is a *second* hand-written switch and it
has not followed the renames at all: it looks for `FlowerPos`, `Bees`/`EntityData` and
`ExitPortal` where 26.2 writes `flower_pos`, `bees`/`entity_data` and `exit_portal`. So a bee
nest arrives having moved itself and nothing it remembers — the flower its bees were working,
and each stored bee's memory of the nest it is sitting in.

This was left deliberately, and it is not an oversight to be tidied up later. **It is what
proves the completion pass works.** The ticket requires that pass to be tested against a field
the tool genuinely does not know about rather than a mock, and fixing every defect in the tool
would have left nothing real to test it with. A bee nest is also about as ordinary as terrain
gets. Whoever widens the patch next should take this with them — and should expect the
completion count in the report to drop to zero when they do, which is the check that they
got it right.

### The completion pass, and ticket 03's objection

`src/main/kotlin/eu/mctraveler/importer/ChunkCompletion.kt`, running in `MergeStaging.write()`
between the sampled diff and the audit. `src/main/kotlin/eu/mctraveler/importer/StagedChunks.kt`
is the region-file reader it now shares with `ChunkAudit`, extracted so that the phase which
finishes leftovers and the phase which proves none are left cannot come to disagree about
which chunks they mean.

It walks the staged chunks by shape — an int array of exactly three, a compound of `x`/`y`/`z`
ints — and applies the offset to any that still falls inside Secondary's old footprint, which
is the audit's own test for a leftover and no other. A coordinate that already moved is
therefore never moved twice.

**Ticket 03's objection was that an audit which patches over the relocation's gaps stops being
able to tell anyone the gaps are there, and it was right.** The answer is that this is a phase
of its own with a section of its own, and it reports every coordinate it completed *and what
kind it was*, named from the nearest thing with an id — `minecraft:bee_nest.flower_pos`,
`minecraft:bee.hive_pos`. A non-zero count prints what it means in the report itself: MCA
Selector has fallen behind what Minecraft writes, the merge finished it, and the patch wants
widening. The audit then runs **unchanged**, after it, and still refuses over anything left.

**Where it runs, and why not earlier.** After the sampled diff rather than before it, so the
diff still compares the tool's own output against the source — ticket 04's evidence is that
the terrain arrived, and it would be worth less if another phase had been over the chunks
first. Both phases are still after the relocation and before the audit, which is what the
ticket asks.

### Judgement calls

1. **The exclusions are ticket 03's, reused rather than restated.** A velocity is not a place
   (`Motion` near the origin reads as inside Secondary's footprint every time, and `Pos` is
   the only list of three doubles that is a place — which the relocation already moves). A
   uuid is four ints, not three. The arbitrary-NBT escape hatches are somebody's stored data.
   Lodestone trackers are skipped because the audit *repairs* those and knows the dimension
   each names; completing one here would apply the offset twice.
2. **The chunk's frame is not completed, and this is the load-bearing exclusion.** The chunk's
   own position, its structure starts and the references that point back at them are read by
   the audit *by name*, and this pass leaves all of them alone. They are not coordinates the
   chunk carries, they are what the chunk *is*, tied to which region file and which slot it is
   stored in — rewriting one would not finish the relocation's job, it would make the chunk lie
   about where it is. A leftover there means the relocation failed structurally, which is
   exactly what ticket 03 refused to let anything paper over.
3. **An exit portal is not ours to move.** An end gateway's `exit_portal` names a place in the
   End, and Secondary's End is discarded rather than relocated, so there is no destination to
   point it at and the overworld's offset would invent one. It is the same reasoning the audit
   already uses to *name* rather than move a lodestone compass pointing into Secondary's End.
   This is the one coordinate the merge finds, can see is stale, and deliberately refuses over
   — because an operator with an end gateway in relocated terrain has something the merge
   genuinely cannot decide for them.
4. **`WorldMergeAuditTest`'s deliberately-stale coordinate is now that exit portal**, replacing
   the bee. It is genuinely unrelocated by the patched tool — verified, not assumed — *and*
   genuinely declined by the completion pass, which is what a refusal test needs after this
   ticket: any field that is merely unhandled now gets completed, so the refusal path would
   have quietly stopped being exercised. `CoordinateBearingChunks.addEndGateway` says so in
   its doc comment, because it is load-bearing rather than arbitrary.
5. **The suite is 19 tests, up from 15.** The four new ones are the bee arriving relocated, a
   bee nest being completed rather than refused over, the report naming what it completed, and
   the ordinary case saying nothing needed it. No existing test was weakened or removed; the
   refusal test changed only which coordinate it leaves stale, and still asserts that nothing
   was written and the staging directory is gone.
