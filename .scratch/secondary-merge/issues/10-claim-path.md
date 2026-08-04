# 10 — Returning players from the quarantine

**What to build:** The merge sweeps every player it can see. It cannot see the thousands of
saves still sitting in quarantine from the Portal cutover, waiting for their owners to come
back — and some of those owners will come back years from now, long after anyone is
watching for it.

So the claim path learns the merge. A save claimed out of Secondary's quarantine has the
offset applied on the way in, in exactly the way the sweep would have applied it, and is
stamped exactly as the sweep would have stamped it. A save from Primary's quarantine is
untouched, because its owner was never anywhere that moved. Both say which happened in the
log, so a wrong landing years from now is diagnosable rather than mysterious.

The offset is a permanent constant in the codebase after this, living beside Secondary's
footprint and shared with the merge itself so the two can never drift apart. This is the
whole live-code surface of the migration: everything else is offline and one-shot.

**Blocked by:** 01 — Merge geometry and the placement search; 06 — Moving the players.

**Status:** done

- [x] A save claimed from Secondary's quarantine arrives at its relocated position, in the
      corresponding Primary dimension
- [x] Everything the sweep transforms is transformed here too — respawn point, last death
      location, nether entry position, logged-out vehicle, and lodestone compasses in the
      inventory and ender chest
- [x] The resulting record carries the same merge stamp the sweep would have written
- [x] A save claimed from Primary's quarantine is not moved
- [x] A claim that applied the merge transform is distinguishable in the log from one that
      did not
- [x] The existing guarantee holds unchanged: a player who already has a save is never
      overwritten
- [x] A claim that cannot be made still writes nothing and leaves the quarantine intact
- [x] The offset and Secondary's footprint are stated once and used by both the merge and
      the claim path
- [x] The existing claim unit tests and gametests are extended rather than duplicated

## Comments

### Implementation summary

- `src/main/kotlin/eu/mctraveler/importer/MergeGeometry.kt` — `MergeGeometry.APPLIED_OFFSET`,
  the permanent constant, beside `Footprint` and the arithmetic that consumes it. It is
  `MergeOffset?` and **null until the operation is actually run**, which is the honest value
  for a save that has not been merged: a claim then behaves exactly as it did before.
- `src/main/kotlin/eu/mctraveler/importer/MergedPlayerdata.kt` — `mergedFromSecondarysQuarantine`,
  the claim path's door into ticket 06's transform.
- `src/main/kotlin/eu/mctraveler/importer/PlayerSweep.kt` — `MergeStamp.into(record, offset, at)`;
  the sweep's `stamp()` is now one line delegating to it.
- `src/main/kotlin/eu/mctraveler/importer/OrphanedSaveClaim.kt` — `MergeOnClaim`, and the claim
  path applying the transform to the live save and to a banked Secondary bucket.
- `src/main/kotlin/eu/mctraveler/importer/OrphanedSaveClaimFeature.kt` — the log clause.
- `OrphanedSaveClaimTest` 12 → 23 tests; `OrphanedSaveClaimGameTest` gains one.

### How the offset stays one statement

`WorldMerge.run()` takes its offset from `plan.offset ?: MergeGeometry.APPLIED_OFFSET ?: search`,
and `OrphanedSaveClaim` defaults its `mergeOffset` parameter from the same constant. Once the
constant is filled in, the real run moves the landmass by the very value the claim path will
still be reading years later, and no flag anyone forgets can make the two disagree. Until then
it is null, the search answers, and the claim path is inert.

**The runbook must say this:** the placement is found with `--plan-only`, the chosen offset is
written into `MergeGeometry.APPLIED_OFFSET`, and only then is the real run performed. An
operator who skips that step leaves the claim path unable to move anybody, silently, for the
whole life of the quarantine.

### Judgement calls

1. **A quarantined save's places are located by the directory it sat in, not by what they
   name.** This is the one place ticket 06's rule — "which side a place is on is the place's
   own answer" — does not hold, and it inverts cleanly rather than being weakened. A backend
   wrote these saves and both backends were plain vanilla, so every place in one names the
   vanilla trio however deep in Secondary it is. It is sound because a save untouched since
   before the Portal cutover *cannot* name both Worlds: one backend wrote all of it. So
   `mergedFromSecondarysQuarantine` passes `WorldLayout::backendRole` where the sweep passes
   its own `secondaryRole`, and everything downstream of that one question is shared.
2. **The re-pointing happens before the move, and re-points at Primary.**
   `PlayerdataImport.live(tag, PRIMARY)` then `mergedFromSecondarysQuarantine` — that order
   leaves the overworld's and the nether's ids exactly as they were, still gives a save naming
   a dimension this server has never heard of the overworld landing it has always had, and
   never routes anybody through a World that is being retired.
3. **A banked Secondary bucket is moved too.** Not polish: the sweep rewrote every existing
   record's `lastServer` to Primary, so a returning player quarantined on *both* sides has
   their Primary save made live and their Secondary one banked — which is exactly the half
   that moved. It reuses `MergedPlayerdata.merged(bucket, offset)`, the sweep's own call.
4. **A merged claim records Primary as the player's World**, for ticket 06's judgement-call-4
   reason: there is one World afterwards, and a record still naming Secondary would have the
   login path place its owner into a World that is being retired.
5. **The stamp's instant is the claim's, not the merge's.** Everything else about it is
   `MergeStamp`'s own bytes with the same offset. Backdating a claim into the night would be a
   lie, and "this record was moved years later" is the more useful answer to the question the
   stamp exists for. A claim that moved nothing writes no stamp, matching the sweep.
6. **The log clause is absent on an unmerged server**, because there is nothing to say and the
   Portal cutover's runbook already documents that line as it stands.

### Things the later tickets must know

- **`MergeStamp` and `MergedPlayerdata` both grew**, additively — ticket 07 builds on the same
  types. `MergeStamp.json` and `FIELD` are untouched; `MergedPlayerdata.merged(tag, offset)`
  and `merged(bucket, offset)` keep their signatures and behaviour exactly.
- **`OrphanedSaveClaim` gained two constructor parameters**, `records: Path` and
  `mergeOffset: MergeOffset?`. `PersistenceService` now states `playersDir` once so the wiring
  has somewhere to read it from.
- **A hazard ticket 09 inherits, which is not this ticket's to fix.** The sweep rewrites every
  record's `lastServer` from `secondary` to `primary`, *including* the records of players whose
  saves are still quarantined — those records survived the Portal cutover keyed by Mojang uuid.
  `OrphanedSaveClaim.liveWorld` uses that field to choose which of two quarantined saves becomes
  the live one, so after the merge a returning player quarantined on both sides always has their
  **Primary** save made live, whichever World they were actually last in. Judgement call 3 above
  keeps the coordinates right either way, and the inventory question is untouched by ticket 10 —
  but if it matters, the fix is for the sweep to record the pre-merge `lastServer` (the stamp is
  the obvious place) and for the claim path to prefer it.
