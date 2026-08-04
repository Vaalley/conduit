# 04 — Sampled block-for-block verification

**What to build:** Evidence that the terrain actually arrived, not merely that it is
internally consistent. The audit can only find coordinates that are wrong; it cannot find a
chunk that was dropped, truncated or half-written, because a missing chunk has no stale
coordinates to notice.

So the merge samples. It picks chunks from the relocated data, loads each one alongside the
source chunk it came from, and compares them block for block — states, block entities and
entities — allowing for the offset and nothing else. The operator chooses how many, trading
rehearsal time against confidence. A single mismatch fails the merge.

**Blocked by:** 02 — Relocating Secondary's chunk data.

**Status:** done

- [x] The operator can choose how many chunks are sampled, and the choice is recorded in
      the report
- [x] Sampled chunks are drawn from across the whole relocated footprint rather than from
      one corner of it
- [x] Each sampled chunk is compared against its source for block states, block entities
      and entities, with the offset applied and no other difference tolerated
- [x] The sample is reproducible for a given save and sample size, so a rehearsal and the
      real run check the same chunks
- [x] A mismatch fails the merge, names the chunk and describes what differed, and leaves
      the live save untouched
- [x] The report states how many chunks were compared and that they matched
- [x] A test proves a deliberately corrupted relocated chunk is caught
- [x] A test proves a deliberately missing relocated chunk is caught — the case the audit
      structurally cannot see

## Comments

### Implementation summary

- `src/main/kotlin/eu/mctraveler/importer/SampledDiff.kt` — the whole phase, plus
  `SampledDiffReport` and `DimensionSample`. One entry point,
  `SampledDiff(levelDir, stagedLevelDir, offset, sample).verify()`.
- `src/test/kotlin/eu/mctraveler/importer/WorldMergeSampledDiffTest.kt` (13 tests), driving
  the merge command end to end against `MergedDeploymentFixture`, a sibling class rather
  than more of `WorldMergeTest` because 03 and 07 are extending that in parallel.

Shared files, kept as small as they could be:

- `MergeStaging.kt` — one phase line in `write()`, that phase added to the existing
  `listOf(...)`, and one `sampled` accessor beside the other three on `MergeReport`.
- `WorldMerge.kt` — `MergePlan.sample`, its `require`, and `DEFAULT_SAMPLE = 64`.
- `WorldMergeMain.kt` — `--sample <n>` and its usage lines.
- `McaSelector.kt` — the class and `relocate` are now `open`; nothing else. See below.

### Public surface later tickets build on

```kotlin
class SampledDiff(levelDir, stagedLevelDir, offset, sampleSize) { fun verify(): SampledDiffReport }

data class DimensionSample(role, sampled: List<ChunkPos>, available: Int) { val compared: Int }

data class SampledDiffReport(sampleSize, dimensions) : MergeSection {
  val compared: Int; fun dimension(role): DimensionSample; fun lines(): List<String>
}
```

`MergeReport.sampled` reaches the section; `MergePlan.sample` carries the operator's choice.

### Judgement calls

1. **The phase runs immediately after the relocation and before every sweep.** It is
   checking what the tool produced, so it has to look at it before anything else rewrites a
   staged chunk. **Ticket 03 must stage its audit repairs after this line**, or the
   lodestone targets it rewrites in place will be differences this reports.
2. **The sample is a stride, not a shuffle.** The candidates are the source's own chunk
   positions ordered by region file and then by position within it, and the picks are
   evenly spaced indices over that list with both ends included. No clock, no random source,
   seeded or otherwise — so a rehearsal and the night compare the same chunks by
   construction rather than by agreeing on a seed. Even spacing over a region-file-major
   ordering is also what makes the sample span the footprint: with the fixture's two region
   files, a sample of two is the first chunk of the first file and the only chunk of the
   second. Raising the sample size moves every pick rather than adding to them, so a second
   run at a different size is a genuinely different check.
3. **The inventory is read from region-file headers, never by parsing chunks.** Choosing the
   sample therefore costs a few kilobytes per file rather than a pass over the map, and a
   file that is not chunk data at all yields nothing instead of throwing — which is why the
   ticket-06 suite, whose Primary and Secondary chunk files are deliberately unreadable
   nonsense, still runs this phase and reports "chunks compared : none".
4. **Three things are compared, and the KDoc says what is deliberately left out.** A chunk's
   `sections` hold no coordinate at all, so a horizontal move cannot legitimately change a
   byte of them and they are compared for exact equality — that is the block-for-block half,
   and it is what catches a chunk that arrived truncated. Block entities and entities are
   compared by identity and position with the shift applied. Structure starts, ticks, brain
   memories and POI records are **not** compared: re-deriving what the tool should have
   written for each of them would be reimplementing the relocation the merge deliberately
   does not perform, and a comparison that disagrees with the tool for reasons of its own
   would fail a good merge. Those are the audit's subject.
5. **Absent entity data and empty entity data are one case.** A chunk that stores no
   entities and a chunk with no entity storage are the same claim about the chunk, so both
   read as an empty list rather than as a difference between two spellings of nothing.
6. **A sampled frontier chunk is evidence too.** A chunk vanilla never finished must *not*
   have travelled, so for those the check is inverted rather than skipped.
7. **A mismatch is an `IllegalStateException`, not a `MigrationRefused`.** It matches the
   count mismatch ticket 02 already throws: the operator did nothing wrong, the tool did.
8. **`--sample 0` is allowed** and the report says "nothing was compared, so nothing here
   says the terrain arrived" rather than a pass. An operator with a very large save may want
   it; nobody should be able to read a zero-sample run as evidence.
9. **`McaSelector` is `open`.** The merge spec's "Relocation" note already describes it as
   "one narrow interface so the rest of the merge is testable without it"; a test that has
   to prove a *later* phase catches what the tool got wrong cannot wait for the tool to go
   wrong on its own. `WorldMergeTest` already substitutes a broken jar at the same seam; the
   tests here substitute the real tool plus one deliberate act of damage.

### How the tests prove a *missing* chunk is caught

Every failure the suite injects is chosen to be one nothing else in the merge could see —
the terrain count stays exactly where `ChunkRelocation` expects it, and no stale coordinate
is left for the audit to find:

- **Entity data that never arrived.** Entity data lives in a folder of its own and
  `ChunkRelocation` counts the terrain folder, so this passes the count untouched. Its pair,
  `the same merge commits when nothing is sampled, so it is the diff that catches it`, runs
  the identical damage with `sample = 0` and watches the merge commit — which is the whole
  argument for the phase existing, and stops the headline test passing incidentally.
- **A chunk that landed one chunk east of where it belongs.** The same number of chunks
  arrived and every coordinate in them is a Primary one.
- **A frontier chunk swapped in for a relocated one**, which keeps the count intact.
- Plus the corrupted cases: changed block states, and a block entity that never arrived.

### What this found, which belongs to ticket 15

Bringing this phase up turned MCA Selector's selection race from silent data loss into a
loud test failure, which is exactly the class of failure the ticket was written for. Measured
against the merge's own fixture while diagnosing it, in case ticket 15 wants the numbers:

- `--mode select` dropped **an entire region file's chunks** from the CSV in **5 of 120**
  runs (~4%), exiting 0 each time. Always a whole file, never a chunk here and there.
- With `--process-threads 1` it was stable in **120 of 120** runs.
- `--mode import` was not observed to lose anything on its own, and is guarded anyway: the
  destination count is compared against the selection. The **selection** was the unguarded
  step, because the relocated-versus-selected check reads both numbers off the same CSV, so
  they agree and are both wrong together.

No fix is included here — that is ticket 15's. Until it lands, a merge suite that goes red
with a chunk missing from an expected set is that defect and should be re-run, not retuned.
