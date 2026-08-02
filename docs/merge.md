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

Plan first, check the placement against the real map, then run for real with the offset the plan
chose:

```sh
./gradlew mergeWorlds --args="--target /srv/mctraveler-fabric/run --plan-only"
./gradlew mergeWorlds --args="--target /srv/mctraveler-fabric/run --offset 8192,-4096 --accept-end-loss"
```

| Option | Meaning |
| --- | --- |
| `--target <dir>` | the live server's run directory (`regions.json`, `mctraveler/`, `world/`), with the server stopped |
| `--plan-only` | choose the offset, print it and write **nothing at all** |
| `--level-name <name>` | the level directory to work in; must match the server's `level-name` (default `world`) |
| `--clearance <blocks>` | empty ground to leave around the landmass, in **nether** blocks; the overworld is given eight times as much (default `512`) |
| `--offset <x>,<z>` | place Secondary here instead of searching. Checked by the same test a searched offset passes. Both axes must be multiples of 4096 |
| `--search-limit <n>` | how many 4096-block steps out to look (default `64`, maximum `256`) |
| `--border <blocks>` | Secondary's world border, in blocks from the origin on each horizontal axis (default `50000`) |
| `--bleed <blocks>` | how far past the border terrain is still carried (default `512`, one region file) |
| `--accept-end-loss` | go ahead even though something is still anchored in Secondary's End. Run without it first and read what it names |
| `--sample <n>` | relocated chunks of **each** dimension compared block for block against their source (default `64`; `0` compares nothing and proves nothing) |

**Pass `--offset` on the night.** It is not a shortcut past the search — a supplied offset goes
through exactly the test a searched one passes, and is refused by name if it fails. What it buys
is that the rehearsal and the night put the landmass in the same place, which is the only way the
rehearsal is evidence about the run. The command prints the flag to use after every plan.

There is nothing to fill in between planning and running. The offset the run applies is written
into `mctraveler/merge.json` by the run itself, and everything downstream — the claim path most
of all — reads it back from there, so there is no constant to edit and no step that can be
skipped.

### `--border` and `--bleed`

`--border 50000` is the border Secondary actually ran. The tool cannot measure it, so if it is
wrong nothing will say so; it is the one value in the merge that has to be checked against the
server's own configuration beforehand. Chunks past it are **left behind**: not moved, not deleted,
still in Secondary's folders after the merge — and gone for good when the Worlds-retirement build
removes those folders. Carrying them is actively harmful, because the placement search sizes its
slot from the whole footprint and a single chunk generated a million blocks out would demand an
enormous free area in Primary.

`--bleed 512` exists so the ground does not end at a visible wall at the border, and it is the
smallest value that can do anything at all, because the clip carries whole region files. The clip
rounds *inward*: a file is carried only when all of it lies within border + bleed.

**The border is not divided by eight in the nether.** It is a vanilla world border, which applies
at the same coordinates in every dimension, so the nether is clipped at ±50,000 *nether* blocks.
That is deliberate, and it is what catches a stray in the nether at all.

### `--accept-end-loss`, precisely

It is a decision, not a formality, and it does **not** decide whether Secondary's End is
destroyed. The End goes either way — its chunk data is discarded and every player save is
scrubbed of references to it, whether or not the flag is given, because that dimension stops
existing regardless. What the flag permits is the loss of things that are *anchored* there:
Regions in Secondary's End are deleted, Embassy destinations pointing into it are cleared rather
than left aiming at nothing, and the players standing in it are put down elsewhere — at their
Secondary overworld bucket position if they have one, and at the relocated world spawn otherwise.

Run without it first, read the list, tell the people on it, then pass it.

## What it prints

One section per phase, in the order the phases ran. The numbers below are illustrative; the
shape is not.

```
Merged the merge of Secondary into Primary in /srv/mctraveler-fabric/run:
  offset                   : x +8192, z -4096  (nether x +1024, z -512)
  offset came from         : --offset, checked rather than trusted
  clearance asked for      : 512 nether blocks, 4096 in the overworld
  overworld
    Secondary's footprint  : x -12288…14335  z -9216…11775  (2013 region files)
    lands at               : x -4096…22527  z -13312…7679
    clearance achieved     : 4096 blocks
    Primary has reached    : x -8192…9215  z -7168…8191
  nether
    Secondary's footprint  : x -1536…2047  z -1536…1535  (49 region files)
    lands at               : x -512…3071  z -2048…1023
    clearance achieved     : 512 blocks
    Primary has reached    : x -1024…1535  z -1024…1023
  Secondary's border       : ±50000 blocks, with 512 of bleed carried past it
  left outside the border  : nothing — every region file of Secondary is inside it
  overworld
    chunks relocated       : 418327
    chunks dropped         : 1104 (not fully generated)
    files written          : 2013
    bytes transferred      : 9214859264
  nether
    chunks relocated       : 28788
    chunks dropped         : 83 (not fully generated)
    files written          : 49
    bytes transferred      : 533354496
  relocated in total       : 447115 chunks, 1187 dropped, 9748213760 bytes
  discarded                : Secondary's End — 31 files
  sample size              : 64 chunks from each relocated dimension
  chunks compared          : 128 — overworld 64 of 418327, nether 64 of 28788
  sampled diff             : every sampled chunk matched its source, block for block
  coordinates completed    : none — the relocation tool moved everything it should have
  chunks audited           : 447115
  coordinates checked      : 3918442
  repaired automatically   : 7 lodestone compass targets
  needs an operator        : 2, listed below and never rewritten
    command block          : overworld 412, 68, -1180 — /tp @p 85 64 53
    cannot be repaired     : a lodestone compass pointing into Secondary's End
  Regions moved            : 184 — overworld 171, nether 13
  Regions left alone       : 602
  Embassy destinations     : 6 moved to Primary
  regions.json             : rewritten
  players swept            : 1842
  players left alone       : 11106
  banked positions         : 973
  Secondary's End          : discarded — nothing was anchored in it
  respawn points moved     : 1197 — 1181 confirmed against the relocated chunks, 16 had no bed before the merge either

The merge is committed and /srv/mctraveler-fabric/run now carries the merge stamp, so this will
refuse to run again. Secondary's End and its level-wide saved data were discarded rather than
moved.
```

A `--plan-only` run prints the placement and the border section, and then says so:

```
Nothing was written. Check that distance against the live map before the real run — Secondary
has grown since the Portal cutover — and pass --offset 8192,-4096 when you run it for real, so
the rehearsal and the night put the landmass in the same place.
```

### Lines worth stopping on

**`offset came from`.** On the night this must say `--offset, checked rather than trusted`. If it
says `the search — the nearest clear slot, N tried`, you did not pass the offset the rehearsal
chose, and the landmass may have gone somewhere else.

**`clearance achieved`.** Blocks of genuinely empty ground between the landed footprint and
Primary's nearest chunk data. Region-file granularity: adjacent files read as zero.

**`left outside the border`.** `nothing — every region file of Secondary is inside it` is the
expected answer. It names region *files*, not chunks, and says how far past the border the
furthest one reached. Tens of thousands of blocks out is a stray teleport or an admin excursion,
and is exactly what the clip is for. **A few hundred blocks past the border, or a lot of files,
is somebody's base** — stop and find out whose, because the clip will leave it behind and the
merge will not mention it again.

**`chunks dropped`.** Chunks vanilla had not finished generating, left behind so the frontier
regenerates cleanly rather than half from one seed and half from another. Expected and
uninteresting. It is a different count from `chunks outside border`, and the two never overlap.

**`chunks compared`.** `none` means either you passed `--sample 0` or there was no readable chunk
data to sample — and the next line says outright that nothing here says the terrain arrived. On a
real run this should be `--sample` × 2.

**`coordinates completed`.** See below; this is the one line whose meaning is not obvious.

**`repaired automatically` and `needs an operator`.** The first is work already done — lodestone
compass targets re-pointed at the relocated landmass, recursively through containers and bundles
wherever they were found. The second is **an action list for after the server is back up, not a
refusal**. Command blocks holding literal coordinates are listed with their position and their
command and are never rewritten, because a command is a program and the numbers in one can be a
place, a count, a score or a tick.

**`Regions outside border`, `destinations outside it`, `players outside border`, `beds outside
the border`.** These lines appear only when the count is non-zero, and each is a person to tell.
They are swept like everyone else — that is the deliberate call — but the chunks under them
stayed in Secondary, so a Region will protect terrain regenerated from Primary's seed, a player
will log in somewhere that looks nothing like where they logged out, and a bed's owner will
respawn at the world spawn instead. **They are named in no other report**, so this count is the
only warning there will be.

**`still in Secondary's End` and `Region deleted`.** The same Region can appear under both, and
it is not a double count: the first is what the Regions sweep found and left, the second is what
the End gate then destroyed, in phase order.

**`banked positions`.** How many players had a second base recorded for them in
`mctraveler/banked-positions.json`, which is what `/switch` reads back. Nobody's banked position
is restored to them — it is told to them.

### A non-zero `coordinates completed`

Zero is the expected reading and the boring one: MCA Selector moved every coordinate Minecraft
26.2 writes, and there was nothing left for the merge to finish. A non-zero count is a finding,
and looks like this:

```
  coordinates completed    : 2 in 1 chunk, which the relocation tool did not move. See below.
    the tool left behind   : minecraft:bee_nest.flower_pos — 1 coordinate
    the tool left behind   : minecraft:bee.hive_pos — 1 coordinate
    what this means        : MCA Selector has fallen behind what Minecraft writes. The merge
                             finished these itself and the audit below still checked all of
                             them, so the map is sound — but the patch wants widening before
                             the next run.
```

It means the pinned tool does not know about a coordinate the game writes, and the merge applied
the offset itself rather than stopping. **It is not a reason to abort the run.** The audit runs
afterwards, unchanged, over the completed chunks, and would still have refused if anything were
left — so a merge that prints this and then completes is a merge whose map is sound.

What to do about it, in order:

1. **Nothing, during the window.** The run is good. Do not stop it, and do not try to widen the
   patch at 2am.
2. **Afterwards, record the field names.** They are the whole value of the section: each names a
   field to add to `gradle/mcaselector/2.8-mctraveler1.patch`, keyed by NBT name rather than by
   entity id.
3. **Expect it to be non-zero on the first real run.** The tool's *block entity* switch was
   deliberately left unfixed, so that the completion pass was proved against a defect that is
   real rather than mocked. A bee nest in a relocated chunk is what will show up here, and it is
   expected rather than alarming.
4. **Compare it between the rehearsal and the real run.** They should match. A count that grew
   means the save changed underneath, and is worth understanding before reopening.

The one thing this does *not* clear is an end gateway's `exit_portal`. It names a place in the
End, and Secondary's End is discarded, so the merge cannot know where to point it and
deliberately does not try. If the audit refuses over one, that is a genuine decision for a
person: the gateway is in relocated terrain and its destination no longer exists.

## It refuses rather than half-merging

PLACEHOLDER refusals.

## After it runs

PLACEHOLDER verification and the deploy gate.

## Rollback

PLACEHOLDER trigger list and decision-maker.

## Known limitations

PLACEHOLDER what cannot be fixed.
