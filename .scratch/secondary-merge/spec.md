# Merging Secondary into Primary

**Status:** ready-for-agent

## Problem Statement

The server runs two Worlds. Primary and Secondary are each a trio of dimensions, and a
player Travels between them with `/switch`, carrying a Per-World Bucket — a Position
Memory and a respawn point — in each. That topology is inherited from the Portal, where
the two Worlds were genuinely two backend servers, and it survived the port because
reproducing it was cheaper than unpicking it.

It costs more than it earns. The community is split across two maps that never meet: a
base in Secondary and a base in Primary are in different universes, no journey connects
them, and the only thing that does is a command that teleports. Half the map's population
is invisible from the other half. Every feature that touches place — Regions, the
Teleportation Crystal's destinations, respawning, the Embassies' saved destinations — pays
a translation tax to keep two Worlds meaningful, and every new one will keep paying it.

Secondary's End compounds it. It is a third of Secondary's dimensions and a rounding error
of its use.

## Solution

One World. Secondary's overworld and nether are relocated, as chunk data, into Primary's
own overworld and nether at a fixed offset in space that Primary has never generated.
Secondary's End is discarded. Afterwards there is a single trio of dimensions, one seed,
and one continuous map on which Secondary is a landmass you can walk to rather than a
place you teleport to.

Everything that recorded a place in Secondary is rewritten to name its new one: Regions
and their cuboids, players' positions and respawn points, the Embassies' saved
destinations, and the crystal's Bed destination by consequence. `/switch` stops travelling
and becomes a signpost that tells each player where they are and where their other base
went. `Worlds`, `Travel`, the Per-World Bucket and Position Memory are retired.

The work is done by `mergeWorlds`, a third offline importer alongside `migrate` and
`importNucleus`, run in a downtime window against the live production save. Like both of
them, it stages everything and writes nothing unless the whole merge succeeds.

## User Stories

**Placing the landmass**

1. As the server operator, I want the tool to find a place for Secondary that Primary has
   never generated, so that the relocated landmass cannot land on top of anything.
2. As the server operator, I want that search to honour the nether's ÷8 relationship, so
   that the nether has as much clearance as the overworld rather than an eighth of it.
3. As the server operator, I want to state the clearance I want in nether blocks, so that
   I am specifying the constraint that actually binds instead of one derived from it.
4. As the server operator, I want the offset aligned so that every source region file maps
   onto exactly one destination region file, so that no chunk is ever re-bucketed.
5. As the server operator, I want to see the chosen offset, both footprints and the
   clearance actually achieved before anything is written, so that I can reject a
   placement I do not like.
6. As the server operator, I want to be able to pass an offset explicitly, so that a
   rehearsal and the real run place the landmass identically.
7. As the server operator, I want the tool to refuse an offset whose footprint is not
   clear, so that a hand-passed offset cannot silently overwrite Primary terrain.

**Relocating the chunks**

8. As a player with a base in Secondary, I want my base to arrive intact — blocks,
   chests and their contents, signs, item frames, paintings — so that nothing I built
   is lost.
9. As a player with a nether portal in Secondary, I want it to still lead where it always
   led, so that my hub and my ice roads keep working.
10. As a player with villagers in Secondary, I want them to keep their beds, workstations
    and meeting points, so that my trading hall still works.
11. As a player with animals in Secondary, I want leashed and penned animals to arrive
    where they were, so that my farms survive.
12. As a player with a lodestone compass, I want it to still point at its lodestone, so
    that navigation I set up still works.
13. As a player, I want mob spawning, crop growth and redstone in the relocated landmass to
    behave exactly as before, so that the move is invisible in play.
14. As the server operator, I want partially-generated chunks at Secondary's frontier
    dropped rather than relocated, so that the frontier regenerates cleanly instead of
    half from one seed and half from another.

**Proving it worked**

15. As the server operator, I want every relocated chunk audited for coordinates that
    still point into Secondary's old footprint, so that I know the relocation was
    complete.
16. As the server operator, I want a structural leftover to fail the whole merge with
    nothing written, so that I never open the server on a broken map.
17. As the server operator, I want a cosmetic leftover repaired where the tool knows how
    and reported where it does not, so that one odd command block cannot block a cutover.
18. As the server operator, I want a sample of relocated chunks compared block-for-block
    against their source, so that I have evidence the terrain arrived rather than merely
    evidence it is self-consistent.
19. As the server operator, I want the sample size to be mine to choose, so that I can
    trade rehearsal time against confidence.
20. As the server operator, I want every command block containing literal coordinates
    listed with its position and its command, so that I have an action list rather than a
    surprise.

**Regions**

21. As a player with a Region in Secondary, I want it to protect the same build after the
    merge, so that my protection is not silently lost.
22. As a player with a sub-region, I want the whole nest to move together, so that
    protection stays layered as it was.
23. As a player with an Embassy, I want its anchor to still send visitors where it always
    did, so that the plot keeps working.
24. As the server operator, I want the merge to refuse if a relocated Region would overlap
    a Primary one, so that two owners can never end up sharing a cuboid.
25. As an admin, I want `/rg locate` to stop reporting a World that no longer exists, so
    that its output describes the map players are actually on.
26. As an admin, I want the guard that refused a Region spanning two Worlds removed rather
    than left to never fire, so that the command's validation says what it means.

**Players**

27. As a player who was last in Secondary, I want to log in standing where I logged out,
    so that the merge is invisible to me.
28. As a player with a bed in Secondary, I want to respawn at it, so that dying works.
29. As a player who logged out in a boat or minecart in Secondary, I want to arrive in it,
    so that I do not lose the vehicle or fall.
30. As a player who died in Secondary, I want my recovery compass to point at where I
    actually died, so that my items are findable.
31. As a player whose ender chest holds a lodestone compass for Secondary, I want it to
    still work, so that stored navigation survives.
32. As a player who was last in Secondary's End, I want to land somewhere sensible rather
    than in a dimension that no longer exists, so that I can log in at all.
33. As a player who was in Primary at merge time, I want nothing about my position to
    change, so that the merge costs me nothing.
34. As a player with a base on both sides, I want to be told where my other base is now,
    so that I can find it again.
35. As a player, I want `/switch` to explain what happened rather than error, so that my
    first instinct after the merge gets an answer.
36. As the server operator, I want each swept player record stamped with the merge and its
    offset, so that months later I can tell a swept record from an unswept one.

**Returning players**

37. As a player who has not logged in since before the Portal cutover, I want my
    quarantined Secondary save to arrive at its relocated coordinates whenever I return,
    so that the merge works for me too.
38. As a player in that position whose save was from Primary, I want no offset applied, so
    that I am not moved somewhere I have never been.
39. As the server operator, I want the claim path to carry the offset as a documented
    constant next to Secondary's footprint, so that the two can never drift apart.
40. As the server operator, I want a claim that applies the merge transform logged as such,
    so that a wrong landing years from now is diagnosable.

**The End**

41. As the server operator, I want the merge to refuse by default when anything is still
    anchored in Secondary's End, so that the loss is something I accept explicitly.
42. As the server operator, I want every Region in Secondary's End listed by title and
    member names before I accept, so that I know who to tell.
43. As the server operator, I want every player anchored in Secondary's End counted and
    their landing stated, so that I know what will happen to them.
44. As the server operator, I want Embassy destinations naming Secondary's End reported and
    cleared, so that no anchor points into nothing.

**Running it**

45. As the server operator, I want the merge to write nothing unless all of it succeeds, so
    that a failed run leaves the save exactly as it was.
46. As the server operator, I want it to refuse to run twice against the same save, so that
    a rehearsal is safe to repeat and a double-run is impossible.
47. As the server operator, I want a rehearsal against a copy of production to behave
    exactly as the real run, so that the rehearsal is evidence.
48. As the server operator, I want a report with counts for everything it did, so that I
    can check them against what I expected.
49. As the server operator, I want every refusal to name the specific thing it refused over,
    so that I can fix it and re-run rather than guess.
50. As the server operator, I want an interrupted run to leave its staging directory in
    place and say so, so that I can see what it had built before it died.
51. As the server operator, I want a runbook covering the whole operation, so that the
    person running it at 2am is not reconstructing it from the source.

**Afterwards**

52. As a player, I want the server to have one map with one set of dimensions, so that
    everyone I play with is reachable.
53. As the server operator, I want the `mctraveler:secondary*` dimensions gone from the mod
    and the save, so that nothing can load them again.
54. As the server operator, I want the production smoke check to assert the dimensions that
    now exist, so that a regression that resurrects Secondary fails the build.

## Implementation Decisions

**Topology.** The server collapses to a single World. `Worlds`, `World`, `DimensionRole`'s
role-to-World resolution, `WorldRouting` and its respawn/portal mixins, the Per-World
Bucket, Position Memory and the `mctraveler:secondary{,_nether,_end}` dimension resources
are all retired. `Landing` and `Waypoint` stay — they are load-bearing for the
Teleportation Crystal and the Embassies and have nothing to do with Worlds.

*This contradicts ADR 0001 (shared player state across Worlds), which is superseded rather
than amended: its subject stops existing. ADR 0003 (Embassies as an out-of-trio dimension)
is amended, not superseded — the Embassies remain exactly what they were, but the
definition has to be restated against dimensions rather than against a trio.*

**The offset.** One vector, a multiple of 4096 on X and Z, applied to Secondary's
overworld; the nether gets exactly one eighth of it. 4096 is the smallest alignment for
which both dimensions relocate whole region files 1:1 — the nether's ÷8 of a 4096 multiple
is a 512 multiple, which is one region file. The ÷8 relationship is what keeps existing
nether portal pairs linking. Y is never offset.

**Placement search.** A 4096-lattice slot is a candidate when no region, entity or POI file
exists for any part of the overworld footprint plus the ring, *and* the same holds for the
nether footprint plus the ring. Clearance is specified in nether blocks and multiplied by
eight for the overworld, because the nether is the binding constraint and nether travel
covers eight times the ground. Candidates are tried in ascending distance from origin. The
chosen offset is printed with both footprints and the achieved clearance before anything is
written, and can be passed explicitly instead — in which case it is validated by the same
test rather than trusted.

**Relocation.** MCA Selector performs the chunk relocation, resolved as a pinned,
checksummed Gradle artifact and invoked headless as a subprocess. It is a tool we run, not
a library we link: its transitive tree never touches the mod's compile classpath. It is
reached through one narrow interface so the rest of the merge is testable without it, but
the tests drive the real thing. Chunks whose status is not `full` are dropped rather than
relocated.

**Audit.** After relocation, every relocated chunk's NBT is walked and every coordinate
classified. Two tiers:

- *Structural* — chunk position, block entity positions, block and fluid ticks, structure
  starts and references, entity positions, item frame and painting tile positions, villager
  and other brain memories, POI records. A leftover pointing into Secondary's old footprint
  fails the merge, nothing is written.
- *Cosmetic* — lodestone tracker targets, command block contents, book text. Lodestone
  targets are rewritten in place, recursively through containers and bundles, wherever they
  are found. Everything else is reported.

Two cross-checks beyond "no stale coordinates", because self-consistency is not
correctness: every brain memory naming a workstation or bed must find a POI record at the
same position, and every transformed respawn point must have a bed or respawn anchor at it.

**Verification.** A sampled block-for-block diff loads N relocated chunks and their sources
and compares block states, block entities and entities modulo the offset. The sample size
is an option. A mismatch fails the merge.

**Data sweep.** Everything that records a place in Secondary is rewritten in the same
staged pass:

- `regions.json` — world strings mapped to Primary's, cuboid X/Z offset, recursively
  through sub-regions, including each Embassy's saved destination in region metadata. Y
  bounds untouched. Overlap against existing Primary Regions is asserted, not assumed.
- Player saves — dimension, position, the respawn point, last death location, the nether
  entry position, and any vehicle logged out in. Inventory and ender chest are walked for
  lodestone targets.
- Player records — the last-World field, the Secondary Per-World Bucket and its respawn
  point, and a new stamp recording that the merge was applied and with what offset. All
  other fields, including legacy ones, pass through byte-for-byte as they do today.
- The banked position — a player's *other* Per-World Bucket — is transformed into merged
  coordinates and written to a read-only artifact the signpost reads back. It is not
  restored to anyone.

**The End.** Secondary's End chunk data is deleted. The merge refuses by default if any
Region, player or Embassy destination is anchored there, listing each; an explicit flag
accepts the loss. Players so anchored land at their Secondary overworld bucket position if
they have one, and at the relocated Secondary spawn otherwise.

**`/switch`.** Kept, Travel deleted. It reports the player's position, where their other
base is if the artifact names one, and that Bed and Spawn on the crystal are unchanged.

**The claim path.** `OrphanedSaveClaim` learns the merge: a save claimed from the
`secondary/` quarantine has the offset applied on the way in, exactly as the sweep would
have applied it, and is stamped identically. The offset lives as one documented constant
beside Secondary's footprint, shared by the merge and the claim path so they cannot drift.
A save from the `primary/` quarantine is untouched. Claims that applied the transform say
so in their log line.

**Staging.** The existing discipline, unchanged: everything is read, converted and checked
first; output is built under a staging directory in the target; only a complete merge is
moved into place. A staging directory left over from an interrupted run is refused rather
than reused. The merge refuses to run against a save that already carries the merge stamp.

**Runbook.** `docs/merge.md`, in the shape of the two existing ones: what it carries over,
what to do before, how to run it, what it prints, what each refusal means, what to check
after, and known limitations. `docs/migration.md` is updated where the merge changes what
it says.

## Testing Decisions

A good test here asserts what the operator or the player can observe — the report, the
files on disk after a run, and the behaviour of a booted server — never how the merge
reached them. The strongest evidence available is a real region file relocated for real and
inspected afterwards, so the tests build real chunk data rather than stubbing it.

**Primary seam — the merge command, end to end.** One function taking a target directory
and options and returning a report, driven against a synthetic run directory. Prior art:
`EmbassyImportTest`, which builds a `NucleusDeploymentFixture` under `@TempDir` and asserts
on both the report and the bytes written; and `PortalImportTest` for the refusal cases.
A `MergedDeploymentFixture` plays the same role here, building a two-World save containing
one of every coordinate-bearing thing: a chest with contents, a villager with a job site
and a bed with matching POI records, an item frame, a painting, a leashed animal, a linked
nether portal pair, a lodestone compass nested inside a shulker box inside a chest, a
Region with a sub-region, an Embassy with a destination, players in each World, a player in
Secondary's End, and a proto-chunk at the frontier. This seam covers the slot search, the
offset arithmetic, the relocation, the audit and its two cross-checks, the sampled diff,
the whole data sweep, the End handling, every refusal, the staging discipline and the
report.

Because MCA Selector is resolved by the build, this seam exercises the real relocation on
every build. No test stubs it.

**Gametest seam.** A merge gametest booting a real server on a merged fixture: a relocated
chunk loads and its block entities are readable, a Region protects at its new coordinates,
a transformed respawn point puts a dying player on a real bed, and `/switch` prints the
signpost. Prior art: `MigrationGameTest`, which does the equivalent for `migrate`.

**Existing suites extended, not duplicated.** `OrphanedSaveClaimTest` and
`OrphanedSaveClaimGameTest` gain the merge cases — a secondary-quarantine claim offset and
stamped, a primary-quarantine claim untouched. `RegionCommandGameTest` covers the changed
`/rg locate` output. `prodServer`'s dimension assertion changes.

**Suites retired.** `WorldsGameTest` goes with Travel. `RespawnAndPortalsGameTest` loses its
cross-World cases and keeps its vanilla ones. `TabListGameTest`'s cross-World case becomes
a single-World one.

## Out of Scope

- **Running the migration.** This spec delivers the tooling and the runbook. The operation
  itself is performed later, against production, over SSH.
- **Importing Secondary's End.** Decided against; the chunk data is deleted.
- **Secondary's level-wide saved data** — maps, raids, world border, force-loaded chunks,
  scoreboard objectives. Never imported at the Portal cutover (deviation 47) and not
  imported now.
- **Command blocks with literal coordinates.** Reported, never rewritten.
- **Written books, signs and chat-shared coordinates** naming Secondary. Unfixable.
- **Duplicate terrain.** Since the Portal cutover both Worlds have generated from one seed,
  so Secondary's post-cutover chunks are twins of Primary's at the same coordinates. After
  the merge both exist in one world at different places. No offset can prevent this and
  nothing here tries.
- **The seam** where Primary's generation eventually meets the relocated landmass. Accepted,
  bounded by the clearance ring, and communicated.
- **A drain period.** The merge is cold; players are told afterwards.
- **A general login-time correction.** The claim path plus the stamp is the whole live
  surface.
- **Any new travel feature** to replace `/switch`. Bed and Spawn on the crystal already give
  every player a round trip.

## Further Notes

**Rollback.** The staging discipline means a *failed* merge needs no rollback — nothing was
written. The exposure is a merge that succeeds and proves wrong after players are back on,
where restoring the pre-merge backup costs everyone's play since. A rollback window is
declared at reopening rather than in advance, so the cold merge is preserved. Two things
must exist before the downtime starts: a written list of what counts as serious enough to
trigger it, and one named person who makes the call.

**Numbers the tool must be given real values for.** Primary's explored overworld and nether
extents and Secondary's current footprint are all measured by the tool at plan time, but
the clearance is an operator judgement and the resulting distance should be sanity-checked
against the map before the real run. Secondary has grown since the Portal cutover.

**Order.** `mergeWorlds` runs after `migrate` and `importNucleus` — both already done in
production — so it sees every Region including the imported Embassies.

**`--worlds move`.** Not offered. The merge always copies; the pre-merge backup is the
rollback and a moved source would compromise it.
