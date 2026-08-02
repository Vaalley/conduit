# 11 — Proving it on a real server

**What to build:** Everything up to here is proven against files. This proves it against a
running server: the merged save is booted for real, and the things a player would actually
do are done.

A relocated chunk loads and its block entities are readable — the chest at the new
coordinates still has what was in it. A Region protects at its new coordinates, so a player
who is not a member still cannot build there. A player dies and wakes up on their own bed,
which exercises the respawn point and the bed together across the two passes that moved
them separately. And `/switch` prints the signpost, with the other base at the coordinates
the merge recorded.

This is the last line of evidence before the operation is run for real, and the one that
covers the gap between "the files are right" and "the game behaves".

**Blocked by:** 09 — Retiring the Worlds subsystem.

**Status:** done

- [x] A server boots on a merged save with only the dimensions that should now exist
- [x] A relocated chunk loads and a container in it still holds its contents
- [x] A relocated Region refuses a non-member at its new coordinates
- [x] A player whose respawn point was transformed dies and respawns on their own bed
- [x] A relocated nether portal still leads to its own twin
- [x] `/switch` prints the signpost, including the other base at its merged coordinates
- [x] The fixture is built by the merge itself rather than by hand, so the test exercises
      the real output
- [x] The test runs headlessly in the standard build alongside the existing gametests

## Comments

### How the fixture is produced

`MergedSave` runs a whole merge — the real MCA Selector, the real audit, the real
block-for-block sampled diff, every sweep — inside the booted gametest server, then copies
what came out into the server's own dimensions. Six cases share the one merge, which is
also the truer shape: a cutover happens once and is then looked at from several directions.

**Secondary's chunks are chunks this server wrote.** A homestead (lit nether portal, chest
with seven diamonds, bed, a block to break) is built in the live overworld and the portal's
twin in the live nether; both are saved through vanilla's own writer; and the region files
are copied into a temporary run directory as `dimensions/mctraveler/secondary{,_nether}`.
That copy is the whole of what makes them Secondary's, which is exactly how they became
Secondary's in production. Inventing chunk NBT would have proved nothing here: invented
data can satisfy every file-level comparison the merge makes and still be something the
game cannot open, and that gap is what this ticket exists to close.

The settler's save is written by vanilla too — a player is logged in, stood where they will
log out and given the bed as their respawn point — and only two strings are then changed:
the dimension it stands in and the dimension its respawn point names. That is the entire
difference between the player in Primary and the same player in Secondary, and it is the
difference the merge is asked to undo.

### Why the suite would fail if the merge produced nothing

Five independent things, in order of how early they fire:

1. **The merge raises rather than returns.** A relocation whose counts disagree, an audit
   leftover, a sampled-diff mismatch or a respawn cross-check failure all abandon the run;
   the fixture would throw before any case ran.
2. **The fixture asserts Primary's dimension folders are absent before the merge.** Nothing
   a booted server later reads out of them can have come from anywhere else.
3. **`layIntoThisServer` refuses when there is nothing to copy**, rather than letting the
   cases quietly assert things about terrain this server generated for itself.
4. **The report is asserted**: chunks relocated in each dimension, chunks compared against
   their source, one Region moved, one banked position.
5. **Every case asserts something only the merge could have put there.** A chunk that did
   not arrive is regenerated flat and has no chest in it; the Region has to cover ground the
   relocation carried; the bed has to be where the sweep says the respawn point is; the
   portal has to find a twin nothing dug; the signpost reads the merge's own artifact.

That last one is not theoretical. The portal case failed on first run *exactly* as designed
— vanilla dug a fresh portal at the scaled position instead of finding the twin — which is
what a case with no discrimination in it looks like when it is working.

### What the running game does that the file tier did not predict

**Vanilla looks only sixteen blocks for a portal's twin on the nether side.**
`PortalForcer.findClosestPortalPosition` searches 16 blocks in the nether against 128 in the
overworld. The fixture originally put the twin 47 blocks off the scaled position — a
distance no file-level test has any opinion about — and vanilla never saw it.

That asymmetry is a real property of this merge and worth stating in the runbook. It is the
reason the nether's ÷8 has to be *exact* rather than approximately right: an offset even
slightly off an eighth pushes every existing pair outside a sixteen-block window, and
nothing in the file tier would notice. The portals would still be there, still relocated,
still paired in the data, and would simply never link again. `MergeGeometry.NETHER_DIVISOR`
is what holds it, and this case is now what would catch a change to it.

Two smaller things, both confirmed rather than surprising: point-of-interest records are
what vanilla actually links portals by (the blocks are decoration as far as the search is
concerned), so a relocation that moved every block and left the poi files behind would fail
here and nowhere else in the suite; and a chunk generated to full status drags its
neighbours into existence, which is why the offset is three lattice steps — one step is a
single region file in the nether, and ticket 18's own-ground refusal correctly rejects it.

### Outside `src/gametest/`

One change: `gradle/merge-worlds.gradle.kts` gives `runGameTest` the same pinned,
checksum-verified MCA Selector jar the `test` task and the `mergeWorlds` command already
get. Without it the gametest would have to stand in for the relocation, and a merge gametest
driving a stub is a gametest about a stub.

### Counts

**460 unit + 300 gametests, 0 skipped, `./gradlew build` green.** The base was 460 + 294;
this ticket is +6 gametests and no unit tests. The whole suite also passes from a wiped
`build/run/gameTest`, so nothing here depends on state left by an earlier run.
