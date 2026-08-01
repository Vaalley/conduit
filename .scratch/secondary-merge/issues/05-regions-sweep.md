# 05 — Moving the Regions

**What to build:** A player who owns a Region in Secondary still owns it, protecting the
same build, after the merge. Every Region recorded against one of Secondary's worlds is
rewritten to name Primary's equivalent and to sit at the relocated coordinates — the whole
nest, so a sub-region moves with its parent and stays inside it.

The Embassies come along too. An Embassy's anchor remembers where it sends a visitor, and
that memory names a world and a position in exactly the same legacy form a Region does; a
destination naming one of Secondary's worlds is rewritten with everything else, so the plot
keeps working.

Regions are the one place the merge can collide with something that already exists, so it
checks rather than assumes: a relocated Region overlapping a Primary one fails the merge.

**Blocked by:** 01 — Merge geometry and the placement search.

**Status:** done

- [x] A Region in Secondary's overworld is rewritten to Primary's overworld at the offset
- [x] A Region in Secondary's nether is rewritten to Primary's nether at one eighth of it
- [x] Sub-regions move with their parents, to any depth, and remain nested inside them
- [x] Vertical bounds are never changed
- [x] An Embassy's saved destination naming one of Secondary's worlds is rewritten the same
      way, and the anchor still sends visitors to the same build
- [x] Regions already in Primary, and the Embassies' own regions, pass through byte for
      byte
- [x] The stored file's formatting is unchanged for every region the merge did not touch,
      as the existing importers guarantee
- [x] A relocated Region overlapping an existing Primary Region fails the merge, naming
      both, and nothing is written
- [x] The report states how many Regions were moved, in which dimensions, and how many
      Embassy destinations were rewritten
- [x] Tests cover a Region with a sub-region, an Embassy with a destination, a Region
      already in Primary, and the overlap refusal

## Comments

### Implementation summary

- `src/main/kotlin/eu/mctraveler/importer/MergeRegions.kt` — the whole sweep and
  `MergeRegionsReport`. One entry point, `MergeRegions(targetDir, staging, offset).sweep()`.
- `src/test/kotlin/eu/mctraveler/importer/WorldMergeRegionsTest.kt` (19 tests), driving the
  merge command end to end against `MergedDeploymentFixture`.
- `WorldMerge.kt` gained `MergeReport` (the placement plus one section per sweep), and
  `run()` returns it. `MergeGeometry.kt` gained the `Double` overloads of `mergedX`/
  `mergedZ` that ticket 01 asked for.

### Judgement calls

1. **Untouched Regions are the very objects the store parsed.** A Region that does not move
   is not rebuilt, so "byte for byte" is a property of the code rather than a hope. Only a
   relocated Region is constructed anew — `Region.world` is a `val`, deliberately, so the
   moved Region has to be a new one.
2. **The overlap test is asked of the *unswept* tree.** While Secondary's Regions still say
   `last`, `RegionService.firstIntersecting(<Primary's world string>, …)` can only match a
   Region that was already in Primary. That is the whole of User Story 24 with no exclusion
   bookkeeping, and two relocated Regions that overlap each other — they overlapped before
   the merge too — are correctly not its business.
3. **A file with nothing to change is not rewritten at all.** The merge is not the thing
   that normalises a `regions.json` it has no change to make to: a save whose Regions were
   all in Primary comes out identical down to a trailing newline, and a rehearsal's diff
   says only what the merge did. It is also why ticket 01's "planning writes nothing"
   test is still green.
4. **Secondary's End is carried across verbatim and named in the report.** Ticket 07 owns
   what happens to it; this sweep neither moves it (`MergeGeometry` refuses to invent an
   offset for the End) nor deletes it. `MergeRegionsReport.endAnchored` is the list 07
   turns into its refusal — Regions as `the Region "…"`, destinations as
   `the destination of the Embassy Region "…"`.
5. **An Embassy destination is swept wherever its Region lives.** The plot itself is in the
   out-of-trio `embassies` world and never moves, but its saved destination is in the same
   legacy form a Region is, and may name Secondary. A destination the strict reader cannot
   parse is refused by name rather than guessed at.
6. **The world strings are derived, never spelled out.** `SECONDARY_ROLES` and
   `PRIMARY_WORLDS` come from `RegionWorlds` + `WorldLayout`, so there is still exactly one
   statement of the dimension-to-legacy-string mapping.

### Things the later tickets must know

- **`WorldMerge.run()` now returns `MergeReport(placement, regions)`.** Tickets 02 and 06
  should add a section to it rather than replacing it; `reportLine` is `internal` now so
  every section aligns to the same 24-column key.
- **The sweep stages `regions.json` under `WorldMerge.STAGING_DIRECTORY` and commits it
  itself**, then removes the staging directory if it is empty. Once 02 and 06 also stage,
  the commit wants hoisting out of `MergeRegions.commit` into one whole-merge commit, so
  that a later phase failing cannot leave `regions.json` rewritten beside unrelocated
  chunks. It is one private function, kept separate for exactly that.
- **`MergeOffset.mergedX/mergedZ` now take `Double` too**, so ticket 06 does not need to add
  them.
