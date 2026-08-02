# 14 — Hardening the claim path

**What to build:** Two corrections to the one part of the merge that has to keep working for
years — the path that hands a returning player their quarantined Portal-era save. Both were
found by ticket 10 while building it; neither could be fixed there without editing files
another ticket had open.

**The offset must not be a constant somebody remembers to edit.** Ticket 10 put the applied
offset in source, null until an operator fills it in between planning the merge and running
it. If they forget, the claim path silently moves nobody, for the life of the quarantine,
with no error and no log line — and the people it fails are exactly the ones nobody is
watching for. The merge already writes the offset it used into the save's own merge marker,
so the claim path should read it from there: one value, written by the operation that
actually happened, with no manual step to skip and nothing to keep in sync. A save that
carries no marker has not been merged, and the claim path stays inert exactly as it does
today.

**A returning player quarantined on both sides gets the wrong save made live.** The player
sweep rewrites every record's last-World field to Primary — correct, since there is only one
World now — but the claim path uses that same field to decide which of a player's two
quarantined saves becomes their live one and which seeds the other World. After the merge it
always answers Primary, whichever World they were actually last in. Their *coordinates* are
right either way, which is what makes this so quiet; what may be wrong is which save's
inventory, XP and advancements they get back. The fix is for the sweep to record the
pre-merge value where the claim path can find it, and for the claim path to prefer it.

**Blocked by:** None — tickets 06 and 10 are complete. Land after wave 3 is reconciled,
since it edits files that were open during it.

**Status:** done

- [x] The claim path takes the offset from the merge marker the merge itself wrote, not from
      a value anyone has to edit by hand
- [x] There is no step in the runbook between planning and running that, if skipped, leaves
      the claim path unable to move anyone
- [x] A save in a run directory carrying no merge marker is not moved, exactly as today
- [x] A marker that cannot be read is refused loudly rather than treated as "no merge" — a
      returning player silently not moved is the failure this ticket exists to remove
- [x] The sweep records each player's pre-merge last World somewhere the claim path can read
- [x] A player quarantined on both sides gets the save from the World they were actually
      last in made live, with the other seeding what the merge kept of the other World
- [x] A player quarantined on one side only is unaffected
- [x] A player who was never swept — no record at cutover — still claims exactly as today
- [x] Both corrections are covered by tests that would fail if the old behaviour returned,
      extending the existing claim suites rather than replacing them
- [x] `docs/migration.md`'s quarantine section reflects what the claim path now does

## Comments

### Implementation summary

- `src/main/kotlin/eu/mctraveler/importer/MergeMarker.kt` — new. Both ends of
  `mctraveler/merge.json` in one place: `contents(offset, at)`, which `MergeStaging` now writes,
  and `offsetApplied()`, which the claim path reads. They are together because a writer and a
  reader in different files can drift, and this is the one fact that has to still be true years
  after anyone is watching.
- `MergeGeometry.APPLIED_OFFSET` — **deleted**, along with `WorldMerge.run()`'s fallback to it.
  The offset is now a fact about a save rather than a line of source; the class note says so and
  says why.
- `OrphanedSaveClaim` — `mergeOffset: MergeOffset?` becomes `mergeMarker: MergeMarker`, with no
  default. `prepare()` asks the marker once, before a byte is written.
- `MergeStamp` — `json`/`into` take an optional `wasLastIn: WorldTrio?`; `wasLastIn(record)`
  reads it back. `PlayerSweep.sweepRecord` passes the World it found before rewriting the field.
- `OrphanedSaveClaimTest` 23 → 30; `WorldMergePlayerSweepTest` 17 → 18;
  `OrphanedSaveClaimGameTest`'s merged case now claims a both-sides player through a real login.

### The three states of a marker, which is the whole of correction 1

`MergeMarker.offsetApplied()` answers **null** for a run directory with no marker (unmerged; the
claim path stays exactly as inert as it was), the **offset** for one that reads, and **throws**
for one that does not. The third is the point: an unreadable marker collapsing into "no merge"
is precisely the silent no-move this ticket exists to delete, so it cannot be allowed to.

It throws inside the claim's *first* phase, so an unreadable marker lands as
`ClaimOutcome.Failed(anythingWritten = false)` — a `FAILED` line in the log, nothing written,
the quarantine whole, and the next login after a repair claiming normally. That is the existing
"a claim that cannot be made writes nothing" guarantee doing the work, not a new mechanism, and
it fails only the players who actually have something quarantined rather than refusing to boot.

An offset of `0,0` is refused with the unreadable ones. No real run can produce it —
`PlacementSearch` excludes the origin and refuses an operator who supplies it — so a marker
naming it is a damaged marker in the shape of a legitimate one, and honouring it would move
nobody just as quietly as no marker at all.

### Judgement calls

1. **The marker is read per claim, not cached at server start.** It costs one small file read,
   and only for a login that has something quarantined — an ordinary login still costs the one
   directory check it always did. In exchange, an operator who repairs the file does not have to
   restart the server, and a marker written after boot is seen.
2. **The pre-merge World is recorded for every record the sweep stamps, not only the ones whose
   `lastServer` it rewrote.** Recording only the Secondary ones would have been smaller and is
   the only case that needs it today; recording all of them means the claim path prefers the
   stamp unconditionally and cannot be broken by a later change to what the sweep rewrites.
3. **The claim writes the pre-merge World back out in its own stamp.** The claim consumes the
   quarantine and nothing will ask again, but overwriting the stamp would delete the record's
   own account of why its owner was handed the save they were handed.
4. **The gametest's merged claim reads a marker in a run directory of its own.** The gametest
   server has not been merged and its other cases depend on that, so a marker at the live path
   would quietly turn them all into merged-server tests.

### Things the runbook must know

- **The step ticket 10 added to the runbook is gone.** There is no longer an
  `APPLIED_OFFSET` to fill in between `--plan-only` and the real run. The sequence is: plan with
  `--plan-only`, check the placement against the live map, run for real with
  `--offset <x>,<z>`. `WorldMergeMain` already prints that instruction and needed no change.
- **`mctraveler/merge.json` is live data, not an artifact.** It must survive into every backup
  and must not be edited or deleted for as long as the quarantine exists — the claim path reads
  it on every claim. `docs/migration.md` now says this, including what a claim does when it is
  damaged.
