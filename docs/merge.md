# Cutover runbook, part three: Secondary → Primary

The one-time merge (merge spec, User Stories 1–7) collapses the server's two Worlds into one map.
Secondary's overworld and nether are relocated, as chunk data, into Primary's own overworld and
nether at a fixed offset that Primary has never generated; Secondary's End is discarded; and
everything that recorded a place in Secondary — Regions, players' positions and respawn points,
the Embassies' saved destinations — is rewritten to name its new one. Afterwards there is one
trio of dimensions and one seed, and Secondary is a landmass you can walk to.

It is the third and last of the cutover tools, after `migrate` (`docs/migration.md`) and
`importNucleus` (`docs/nucleus-import.md`), both of which have already run in production. It has
their shape and their guarantees: it is run offline with the server stopped, it stages everything
and writes nothing at all unless the whole merge succeeds, and it refuses to run twice against
the same save — which is what makes a rehearsal safe to repeat.

It is also the only one of the three that is **irreversible in a way a backup cannot undo
cheaply**, because players are back on the map afterwards. Read the next section before you plan
the night.

## Read these three things first

Everything else here is procedure. These three are the ones that go wrong quietly.

**1. The Worlds-retirement build must not reach production until `mergeWorlds` has actually
run.** That build removes the `mctraveler:secondary{,_nether,_end}` dimension resources from the
mod. Deployed before the merge, the server simply stops creating those dimensions — and every
chunk still inside them becomes unreachable. There is no error, no warning and nothing in the
log. **The danger is the silence.** The merge tool itself still reads a Secondary save, and is
meant to: it navigates by storage folder rather than by registry, so it works against dimensions
the new server can no longer create. The order is `mergeWorlds` first, then the build.
`./gradlew prodServer` is the gate — see [After it runs](#after-it-runs).

**2. `mctraveler/merge.json` is live data, not an artifact of the run.** The merge writes it when
it commits, recording the offset it actually applied. The claim path reads it back on **every**
returning player who still has a quarantined Portal-era save, and there are roughly thirteen
thousand of those — some of whom will not log in for years. It must go into every backup and it
must not be edited or deleted for as long as the quarantine exists. A damaged marker fails every
claim loudly rather than silently misplacing everybody; see
[The merge marker](#the-merge-marker-and-returning-players).

**3. The rollback trigger list and one named decision-maker are agreed *before* the downtime
starts.** Not during it. The staging discipline means a *failed* merge needs no rollback at all —
nothing was written. The exposure is a merge that succeeds and proves wrong after players are
back on, where restoring the pre-merge backup costs everyone their play since. That is a decision
nobody should be making at 1am for the first time. See [Rollback](#rollback).

## What it carries across

| From | To |
| --- | --- |
| `world/dimensions/mctraveler/secondary/…` | `world/dimensions/minecraft/overworld/…` — the same chunks, at the offset |
| `world/dimensions/mctraveler/secondary_nether/…` | `world/dimensions/minecraft/the_nether/…` — at one eighth of it |
| `world/dimensions/mctraveler/secondary_end/…` | **nothing.** Discarded, not moved |
| `regions.json` entries in `last`, `last_nether` | the same entries in `world`, `world_nether`, cuboids offset, sub-regions and all |
| Each Embassy's saved destination in region metadata | the same destination, offset |
| Player saves in Secondary | position, respawn point, death location, nether entry, logged-out vehicle, and lodestone compasses anywhere in the inventory or ender chest |
| Player records' last-World field and Secondary bucket | Primary, plus a `merge` stamp recording the offset and the World they were last in |
| Each player's *other* Per-World Bucket | `mctraveler/banked-positions.json` — read-only, told to them by `/switch`, never restored |
| The world spawn | offset, for players who were standing in Secondary's End |
| — | `mctraveler/merge.json`, the marker. See item 2 above |

Y is never offset, in any of it. The offset is a multiple of 4096 blocks on X and Z, which is the
smallest alignment for which **both** dimensions relocate whole region files one-for-one: the
nether's eighth of a 4096 multiple is 512, which is exactly one region file. That is also what
keeps existing nether portal pairs linking.

Not carried, and each is a decision rather than an oversight: Secondary's End, Secondary's
level-wide saved data (maps, raids, its world border, force-loaded chunks, scoreboard
objectives), and anything outside the border you state. See
[Known limitations](#known-limitations).

## Before you run it

1. **Stop the server.** The merge rewrites `regions.json` and player records directly, and a
   running server holds both in memory and would overwrite the merge at its next save. It also
   reads player saves, which a running server has not flushed.
2. **Back up the whole run directory**, and verify the backup opens. This backup *is* the
   rollback — there is no undo in the tool. Keep it until the rollback window has closed.
3. **Find out Secondary's real world border.** `--border` defaults to 50,000, which is what
   Secondary ran, but the tool cannot measure it: if you give it the wrong number nothing will
   say so. Check it against the server's own configuration. Chunks outside it are left behind
   for good.
4. **Rehearse against a copy of production.** See [The rehearsal](#the-rehearsal). This is not
   optional; it is where you meet the End refusal and where the placement gets checked against
   the real map.
5. **Agree the rollback trigger list and name the decision-maker.** See [Rollback](#rollback).
6. **Have the patched relocation tool built and verified.** See below.

`mergeWorlds` runs *after* `migrate` and `importNucleus`, both of which are long done in
production, so it sees every Region including the imported Embassies.

### The relocation tool is a patched build

The merge does not move chunks itself — MCA Selector does, and its per-version relocation chain
is what copes with Secondary's chunks being a mixture of DataVersions, because vanilla only
upgrades a chunk when something loads it. **It is not a released MCA Selector.** It is a local
build of the 2.8 tag with `gradle/mcaselector/2.8-mctraveler1.patch` applied, pinned by path and
sha256 in `gradle/merge-worlds.gradle.kts`, and run headless as a subprocess.

**Why it is patched.** Released 2.8 has defects that make it unusable here, all found by this
project and all fixed at source rather than routed around:

- `--mode select` races. A non-thread-safe map is mutated from every per-region-file job at once,
  so about one run in twenty silently returned an entire region file's worth of chunks fewer than
  it matched — **and exited 0**. In production that is player builds left behind with the merge
  reporting success.
- The relocation is incomplete for 26.2. A static field the entity relocation dereferences for
  every entity was left null, so each entity was abandoned partway through; and the tool's
  hand-written switch over entity ids still speaks only the pre-1.21.5 spellings, so a leash, an
  item frame's and a painting's tile position, every villager's memories, a bee's hive, a
  phantom's anchor, a mob's home, anything asleep in a bed and much else arrived in Primary still
  naming Secondary. The audit refuses on those, so a merge on stock 2.8 could not complete at
  all.

**How to build it.** The build prints the whole procedure when the jar is missing, so the short
correct instruction is:

```sh
./gradlew provideMcaSelector
```

and follow what it says. For reference that is: clone the 2.8 tag to
`~/.mctraveler/src/mcaselector`, `git apply` the patch, `./gradlew shadowJar`, and copy
`build/libs/mcaselector-2.8-all.jar` to
`~/.mctraveler/tools/mcaselector-2.8-mctraveler1.jar`. It needs a JDK 21 and it downloads JavaFX,
which its build needs even though the merge only ever runs it headless. Pass
`-PmcaSelectorJar=<path>` if you keep it somewhere else.

**The build is reproducible**, so a rebuilt jar matches the pinned checksum exactly. A mismatch
means something is wrong, not that a rebuild drifted — do not run the merge until you know why,
and never take a new checksum from a jar that just failed the check.

### The rehearsal

Copy the run directory somewhere throwaway, merge into the copy, look at what it says, then throw
it away. **The merge refuses to run against a save that already carries the merge stamp**, which
is exactly what makes this repeatable: a rehearsal cannot half-succeed into the state a second
attempt would trip over, because a run that refuses or fails clears its own staging directory and
leaves the copy as it found it.

A rehearsal predicts the real run by construction rather than by luck. Ties in the placement
search break towards +X and then +Z, so the same save gives the same slot every time. The sampled
diff picks its chunks by a stride, not a shuffle — no clock and no random source — so the
rehearsal and the night compare the *same* chunks. Both properties hold only if you keep the
options the same: in particular, **raising `--sample` moves every pick rather than adding to
them**, so rehearse and run at one sample size or you have not repeated the check.

Work through it in this order:

1. **Plan it.** `--plan-only` chooses the offset, prints the placement and writes nothing at all —
   not a staging directory, not a marker, not a byte. Ask as often as you like.
2. **Read the placement against the real map.** The default clearance of 512 nether blocks is a
   starting point for a judgement, not a recommendation. Secondary has grown since the Portal
   cutover.
3. **Check `lands at` against `Secondary's footprint`, per dimension.** This is the one check
   that protects the audit itself, so it is worth making by hand even though the search now
   enforces it. The two lines are printed together:

   ```
     Secondary's footprint  : x 0…50175  z 0…511  (98 region files)
     lands at               : x 8192…58367  z -4096…-3585
   ```

   `lands at` must not overlap `Secondary's footprint` on **both** axes at once, in the overworld
   *and* in the nether. Above it does not: the X ranges overlap heavily, but the Z ranges do not
   meet at all, so no landed coordinate can be mistaken for one that stayed. Inside an overlap
   the audit cannot tell a coordinate that moved from one that never left — it decides that by
   asking whether a coordinate still points into Secondary's old footprint, and in the overlap
   the answer is the same either way. A merge whose audit is unreadable would report success.
4. **Read `left outside the border`.** `nothing — every region file of Secondary is inside it` is
   the expected answer. Anything else is a number to understand now rather than at 2am; see
   [What it prints](#what-it-prints).
5. **Run it for real against the copy, without `--accept-end-loss`.** If anything is still
   anchored in Secondary's End the merge refuses and names it — every Region by title *and* by
   its members' names, every Embassy whose destination points there, and every player standing
   there with where they would land. That list is the whole point of meeting this on the
   rehearsal: those people have to be told before the night, because afterwards their builds are
   gone and there is nothing left to show them. Note that this refusal comes **late**, after the
   chunk relocation has been staged, so a rehearsal that refuses has still spent the
   relocation's time.
6. **Run it again with `--accept-end-loss` and the offset the plan chose**, and read the whole
   report. Compare `coordinates completed` and `left outside the border` between this run and the
   night — they should match, and a count that grew means the save changed underneath.
7. **Boot the merged copy** and walk some of it. Log in as a player who had a base in Secondary.

Nothing about Secondary's own chunk data is modified by any of this: the merge only ever copies —
`--worlds move` is deliberately not offered, because a moved source would compromise the backup
that is your rollback — so Secondary's folders come out of a merge byte-for-byte identical.

## Run it

PLACEHOLDER command and options.

## What it prints

PLACEHOLDER report walkthrough.

## It refuses rather than half-merging

PLACEHOLDER refusals.

## After it runs

PLACEHOLDER verification and the deploy gate.

## Rollback

PLACEHOLDER trigger list and decision-maker.

## Known limitations

PLACEHOLDER what cannot be fixed.
