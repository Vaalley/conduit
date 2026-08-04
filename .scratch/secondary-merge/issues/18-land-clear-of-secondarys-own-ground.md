# 18 — Landing clear of Secondary's own ground

**What to build:** A placement search that will not put Secondary somewhere the audit cannot
reason about.

The audit's test for "did this coordinate move?" is *does it still point into Secondary's old
footprint*. Ticket 03 knew that test has one blind spot and said so plainly (judgement call
5): an offset small enough that the *landed* footprint overlaps the *old* one makes the
question unanswerable, because a coordinate inside the overlap reads the same whether it
travelled or not. It accepted the blind spot on the grounds that the placement search makes it
unreachable — the search only lands Secondary clear of Primary's chunk data, and Primary's
data is what covers Secondary's old ground.

Ticket 13 found that reasoning does not hold. Clipped to its border, Secondary spans about a
hundred region files, and against a *small* Primary the nearest clear slot can sit inside the
box Secondary used to occupy. The search checked the landing against Primary's footprint and
never against Secondary's own.

**How much this matters in production is honestly not much** — Primary has thirteen thousand
players and years of play behind it, so its data almost certainly covers Secondary's old
ground, and the nether-measured clearance pushes the offset out past 90,000 blocks anyway. The
reason to fix it is that "almost certainly" is the wrong standard for a one-shot irreversible
operation, the fix is one more condition on a search that already exists, and an assumption
worth writing down in a judgement call is worth enforcing rather than hoping for.

**Blocked by:** 13 — Clipping the import to Secondary's world border.

**Status:** done

- [x] A slot is only a candidate when the landed footprint clears Secondary's own source
      footprint, as well as Primary's chunk data
- [x] That applies in both relocated dimensions, since either can rule a slot out alone
- [x] The refusal, when no slot satisfies it, says which constraint could not be met — an
      operator reading "no slot found" needs to know whether to ask for less clearance or
      whether something else is wrong
- [x] Ticket 03's judgement call about the audit's blind spot is updated to say the search now
      enforces what it used to assume
- [x] A test proves a save whose nearest Primary-clear slot overlaps Secondary's own footprint
      is rejected, and that the search goes on to find one that does not
- [x] The rehearsal step in the runbook says to check the searched offset against Secondary's
      own footprint as well as Primary's, because that is the check this ticket automates and
      a rehearsal should confirm it rather than trust it *(written into ticket 12's Comments;
      `docs/merge.md` is ticket 12's to create and does not exist yet)*

## Comments

### Implementation summary

One condition, in the search that already existed.

- `src/main/kotlin/eu/mctraveler/importer/WorldMerge.kt` — `PlacementSearch` gained
  `ownGround(offset)`, asked of every slot before the Primary clearance test and of every
  supplied `--offset` before it too. Two refusals are new or reworded.
- `src/main/kotlin/eu/mctraveler/importer/MergeGeometry.kt` — `RegionFileArea.overlaps`, the
  rectangle test the condition is expressed with.
- `src/test/kotlin/eu/mctraveler/importer/WorldMergeTest.kt` — four new tests (28 in the
  suite now), and one existing refusal's expected text updated for the new tally.

### Judgement calls

1. **The overlap is tested at rectangle granularity, against exactly the box the audit will
   later read.** `PlacementSearch` is handed the *clipped* footprints — the same ones that
   become `DimensionPlacement.secondary` and that `ChunkAudit` measures `containsBlock`
   against — so the box the search rules out and the box the audit cannot read are one box
   rather than two that agree today. Ticket 01's judgement call 4 already argued the
   region-file granularity is conservative in the safe direction, and this inherits that.
2. **Touching is not overlapping.** Two footprints one region file apart share no file, so no
   landed coordinate can be read as one that stayed. The condition is disjointness, not
   clearance — it deliberately does not take the ring into account, because the ring is about
   how far Secondary sits from Primary's terrain and this is about whether the merge can be
   checked at all.
3. **Own ground is asked before Primary, on both paths.** It is answered from two rectangles
   rather than from a list of files, so it is the cheaper of the two in the search's inner
   loop; and on the supplied path the Primary refusal ends "or ask for less clearance", which
   is advice that cannot help an offset that simply has not left yet. Refusing in the wrong
   order would have sent an operator to the wrong lever.
4. **The exhaustion refusal states both tallies, including the zero.** `0 of them ruled out by
   the ground Secondary is being moved off and 24 by Primary's chunk data` is the sentence
   that tells an operator at 2am which lever to pull, and the zero carries as much information
   as the twenty-four does. It costs one clause and removes a guess.
5. **No new option, and no way to turn it off.** An offset that overlaps is one the audit
   cannot check, so a flag to permit it would be a flag to make the merge unverifiable.

### Things the later tickets and the runbook must know

- **The searched offset for a wide Secondary has changed**, which is the point. Against
  `WorldMergeBorderClipTest`'s hundred-region-file Secondary the search used to answer
  `x +8192, z +0` — a slot inside Secondary's own box — and now answers `x +0, z +8192`. That
  suite passes an explicit `--offset` for exactly this reason and is unaffected, but its
  `OFFSET` doc comment's reasoning is now enforced rather than merely observed.
- **No existing fixture's `--offset` became invalid.** `MergedDeploymentFixture`'s Secondary
  spans two overworld region files and one nether file, so only the origin overlaps — and the
  origin was already refused by name. The offsets `(8192, -4096)`, `(8192, 0)` and
  `(0, 8192)` that the merge suites pass all clear it.
- **The nether is the dimension that fails this first**, in general. It moves one region file
  per lattice step where the overworld moves eight, so a Secondary of equal width in both
  needs eight times the lattice distance to clear its own nether ground.
