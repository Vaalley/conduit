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

PLACEHOLDER preparation.

### The relocation tool is a patched build

PLACEHOLDER mcaselector.

### The rehearsal

PLACEHOLDER rehearsal.

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
