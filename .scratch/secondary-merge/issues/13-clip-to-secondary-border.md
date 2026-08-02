# 13 — Clipping the import to Secondary's world border

**What to build:** Only the part of Secondary anybody was ever meant to reach comes across.

Secondary's world border runs from −50,000 to +50,000 on both horizontal axes. Chunks exist
outside it anyway: an admin teleport, a stray command, anything that put someone past the
border for long enough to generate terrain. None of that is worth carrying, and carrying it
is actively harmful — the placement search sizes its slot from Secondary's whole footprint,
so a single chunk generated a million blocks out would demand an enormous free area in
Primary and drop a speck of junk terrain into the middle of it.

So the import is clipped to the border plus a bleed. The bleed is what stops the terrain
being sliced off at a visible edge: chunks a little way past the border come too, so anyone
standing at the border still sees continuous ground beyond it rather than a wall.

The clip only ever *shrinks* what is taken — it is the intersection of "chunks that exist"
with "inside the border plus bleed", so a Secondary that never generated anything near its
border is unaffected.

**Deliberately not gated:** a Region, player or Embassy destination anchored outside the
border is swept exactly like any other. The operator's call, and the risk is small — but the
consequence is worth knowing, so the report counts them: such a player's coordinates move
while their chunks do not, so they arrive in terrain that regenerates from Primary's seed.

**Blocked by:** 16 — Fixing MCA Selector rather than working around it. The clip is a filter
over the selection before it is handed to the import, so it needs a selection that can be
trusted to be the same twice — which is what 16 delivers.

**Status:** done

- [x] Only chunks inside Secondary's world border plus the bleed are measured into the
      footprint the placement search sizes its slot from
- [x] Only those chunks are relocated; the rest are left behind, not moved and not deleted
- [x] The border half-extent and the bleed are both options, defaulting to 50,000 and 512
      blocks, and both are echoed in the plan output so a rehearsal and the real run can be
      compared
- [x] The border applies at the same coordinates in both relocated dimensions, as a vanilla
      world border does — it is not scaled by the nether's ÷8
- [x] The clip works in whole region files, so it composes with the 4096 alignment and no
      chunk is ever split from the file it lives in
- [x] The report states how many chunks were dropped as outside the border, and how far out
      the furthest one was, so the operator can tell a stray teleport from a real base
- [x] The report counts Regions and players anchored outside the border, without refusing
- [x] A test proves a chunk far outside the border is excluded from the footprint, so the
      placement search is not dragged out to meet it
- [x] A test proves that same chunk is not relocated, and that the ones inside still are
- [x] A test proves a chunk in the bleed — just outside the border — does come across
- [x] A Secondary with nothing near its border relocates exactly as it does today, so the
      existing tests are unchanged

## Comments

### Implementation summary

`src/main/kotlin/eu/mctraveler/importer/SecondaryBorder.kt` — the border, the clip and the
report section. `src/test/kotlin/eu/mctraveler/importer/WorldMergeBorderClipTest.kt`
(18 tests), a sibling of `WorldMergeTest` driving the merge command end to end.

### Public surface later tickets build on

```kotlin
data class SecondaryBorder(
  val halfExtent: Int = WorldMerge.DEFAULT_BORDER,   // 50_000
  val bleed: Int = WorldMerge.DEFAULT_BLEED,         // 512, one region file
) {
  val reach: Int                                     // halfExtent + bleed
  val files: RegionFileArea                          // the region files carried
  fun keeps(file: RegionFilePos): Boolean            // the clip
  fun contains(x: Int, z: Int): Boolean              // the border itself, no bleed
  fun contains(x: Double, z: Double): Boolean
  fun blocksBeyond(file: RegionFilePos): Int
  fun describe(): String
}

data class BorderClipReport(border, leftOutside: Map<DimensionRole, List<RegionFilePos>>)
  : MergeSection { val filesLeftOutside: Int; val furthestBeyond: Int? }

fun Footprint.clippedTo(border: SecondaryBorder): Footprint
```

`MergePlan` gains `border: SecondaryBorder`; `MergeReport` gains `clip`.

### Judgement calls

1. **The clip rounds inward to whole region files.** A file is carried only when *all* of it
   lies within border + bleed, so every chunk carried is genuinely inside the distance the
   operator stated. Rounding outward would have carried chunks from up to 511 blocks beyond
   it, which is the thing the option exists to bound. The cost is that the last part-file of
   bleed is not carried; with the default bleed of exactly one region file that is what the
   bleed is for and is bounded by it. The whole-file unit is what makes the clip expressible
   as a `Footprint` at all, so the footprint the placement search sizes its slot from and the
   selection handed to the import are answered by the very same predicate.
2. **The clip is a filter over MCA Selector's own selection**, not a narrower question asked
   of the tool. Whole lines are struck out of the CSV by the region file their first two
   fields name, so the selection that reaches `--mode import` is still the tool's own opinion
   about which chunks are finished, minus files. Same shape as the frontier drop: a clipped
   chunk is never written anywhere at all.
3. **`dropped` still means "not fully generated".** It is computed as source chunks minus
   arrivals minus the border's exiles, all measured off disk, so the two counts never overlap
   and an unclipped save reports exactly what it reports today.
4. **The census counts against the border, not against the clip.** A number that moved when
   `--bleed` did would be measuring a rendering nicety rather than a boundary anyone was meant
   to cross, and the border is the thing the operator declared and can check against. It
   slightly over-counts, by the width of the bleed, in the safe direction.
5. **`BorderClipReport` is a section of a *plan* as well as of a merge**, and leads the
   sections rather than being staged among them — it is decided in `WorldMerge` before
   `MergeStaging` runs and constrains every phase that follows. `MergeReport`'s "a plan
   carries no sections at all" was true and is not any more.
6. **A border that carries none of Secondary refuses**, naming both numbers. Not an
   acceptance criterion, but the alternative is relocating nothing and reporting success.

### Two phases downstream assumed every Secondary chunk arrives

Both found by the end-to-end seam rather than by reading, and both would have been production
failures rather than test failures:

- **`SampledDiff` drew its sample from the whole source footprint** and failed the merge over
  a clipped chunk "never arriving". Its inventory is now restricted to the files the border
  carries, so `chunks compared : N of M` counts what the merge undertook to move.
- **`RespawnBeds` would have refused the whole merge** over a player whose bed is outside the
  border — the point moved because every point moves, the bed stayed because the operator said
  so, and that is not the disagreement between two passes the check exists to catch. It is now
  counted as `beds outside the border` and reported, which is the same stance the ticket takes
  on Regions and players.

### Things the later tickets and the runbook must know

- **The audit's "Secondary's old footprint" is now the *clipped* footprint**, because it comes
  from the placement. That box is what the merge carried, which is the right question, but it
  is a narrower box than before and ticket 01's judgement call 4 should be read with that in
  mind.
- **A Secondary clipped to ±50,000 spans ~100 region files**, which is far larger than the
  fixtures the other merge suites use. Against a small Primary the nearest clear slot can then
  put the landmass down *inside the box Secondary used to occupy*, and the audit reads every
  arriving coordinate as one that never left. `WorldMergeBorderClipTest` passes an explicit
  offset for that reason. **A rehearsal against production must check the searched offset
  against Secondary's own footprint**, not only against Primary's.
- **What the runbook must say about `--border` and `--bleed`** is written into ticket 12's
  Comments, because `docs/merge.md` is ticket 12's to create and does not exist yet.
