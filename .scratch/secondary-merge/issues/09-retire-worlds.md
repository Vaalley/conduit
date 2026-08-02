# 09 — Retiring the Worlds subsystem

**What to build:** The server stops having Worlds. This is the contract half of the
expand–contract pair begun in ticket 08, and it is a wide refactor: one decision whose blast
radius reaches the dimension resources, the respawn and portal routing, the persistence
model, the Region layer's world vocabulary, and a large number of currently green tests.

Travel goes, and with it the Per-World Bucket, Position Memory, per-World respawn points and
the World-to-dimension resolution that made all three meaningful. The Secondary dimension
resources are removed from the mod so nothing can load them again. Vanilla's respawn and
portal routing stop being translated and go back to being vanilla's. The Region layer keeps
its legacy world vocabulary — migrated data still reads unchanged — but loses its Secondary
entries, the guard that refused a Region spanning two Worlds, and the half of its location
reporting that named a server that no longer exists.

What must not be removed: the value types describing somewhere to go and somewhere
remembered. They live in the same package as the Worlds service but belong to the
Teleportation Crystal and the Embassies, and both still need them.

**⚠️ Deployment hazard.** The build this ticket produces must not reach production until
`mergeWorlds` has actually run. Deployed early, it removes two dimensions whose chunk data
is still inside them. The runbook (ticket 12) gates on this.

**Blocked by:** 07 — The End, and everything anchored in it; 08 — `/switch` becomes a
signpost.

**Status:** done

- [x] The Worlds service, Travel, and World-to-dimension role resolution are gone
- [x] The Per-World Bucket, Position Memory and per-World respawn points are gone from the
      persistence model, while every other field in a player record still passes through
      byte for byte
- [x] The Secondary dimension resources no longer ship in the mod
- [x] Respawn and portal routing are vanilla's own again, with no trio translation
- [x] The Region layer's Secondary world entries, its two-World creation guard, and the
      server half of its location reporting are removed
- [x] The value types for a destination and a remembered place survive, and the crystal and
      the Embassies still work
- [x] The Worlds gametests are retired; the respawn, portal and tab list gametests keep
      their single-World cases and lose their cross-World ones
- [x] The production smoke check asserts the dimensions that now exist and fails if a
      Secondary dimension reappears
- [x] The full suite is green

## Comments

### Where the line was drawn: runtime out, tool intact

The single decision this ticket turns on is that "retire Secondary" means the **running
server**, never the **merge tool**. `mergeWorlds` runs offline against a save that still has
Secondary's dimension folders, and it runs *before* this build is deployed. So its knowledge
of `mctraveler:secondary{,_nether,_end}` had to survive the removal of everything that
created them.

Three pieces of that knowledge used to live in live code and had to move rather than die:

- **Secondary's legacy world strings.** `RegionWorlds` mapped `mctraveler:secondary*` to
  `last`/`last_nether`/`last_the_end`, and `MergeRegions` and `MergeEnd` derived their sweep
  keys from it. The Region layer lost those entries — a `last*` string must now resolve to
  *nothing*, so an unswept Region or Embassy destination is visibly nowhere rather than
  quietly somewhere — and `WorldTrio.legacyWorld` took over as the tool's statement of them.
  Primary's half is still derived from `RegionWorlds`, because those dimensions do still
  exist and the two must not drift.
- **The Per-World Bucket.** `PerWorldBucket`, `RespawnPoint` and the store's bucket codec
  moved from `eu.mctraveler.persistence` to `eu.mctraveler.importer.PerWorldBuckets`, keyed
  by record path exactly as `MergeStamp` already was. On the store's side `worlds` is now
  simply one more legacy field and gets the byte-for-byte pass-through guarantee for free;
  on the tool's side nothing about the format changed.
- **`RegionImport` and `NucleusRegions`.** Both validate world strings, and both are
  *pre-merge* tools — `migrate` writes the two-World save the merge later reads, and
  `importNucleus` imports Embassies whose destinations name `last_nether`. Both were asking
  the live `RegionWorlds`, so removing Secondary from it made `migrate` refuse every
  Secondary Region and `importNucleus` report every Secondary embassy as broken. They now
  ask `RegionImport.dimensionOf`, which is the importer's own map (falling back to
  `RegionWorlds` so the out-of-trio `embassies` still resolves in both).

This was caught by the suites rather than by inspection: the first full unit run after the
`RegionWorlds` change was 29 red, all of them the merge and migration tiers saying the tool
could no longer find Secondary. That is the check working.

`WorldLayout.SECONDARY`, `Footprint.storageFolder` and `MergeGeometry` were not touched.
`storageFolder` computes a path from a dimension key via vanilla's own
`DimensionType.getStorageFolder`, so it keeps finding `dimensions/mctraveler/secondary/` with
no registry involved — which is why the merge still works against dimensions the server can
no longer create.

### Judgement calls

1. **`WorldsFeature` keeps its name.** It now wires only `/switch` and the banked-positions
   artifact. Renaming the object without renaming the `worlds` package would be half a
   rename, and renaming the package would touch ~25 importer imports and collide with ticket
   18, which was in flight in `MergeGeometry`/`WorldMerge`. The KDoc says plainly what is
   left and why the name stayed. Ticket 12 may want to revisit the package name once the
   glossary is rewritten.
2. **`DimensionRole` stays in `eu.mctraveler.worlds`** alongside `Landing` and `Waypoint`,
   for the same reason. Its KDoc now says it is the importer's vocabulary rather than the
   server's: overworld/nether/end, not "a place in a World's trio".
3. **The claim path still banks the other save into a Per-World Bucket.** It writes legacy
   data nothing reads back — but so does the sweep, which *moves* Secondary's bucket rather
   than deleting it, and the two must stay identical or a returning player's record differs
   from a swept one. Changing that is a behaviour change this ticket does not ask for.
4. **`locateInfo` keeps the Portal's substring mapping** and loses only the server half, so
   `world_nether` still reads `nether`. Rewriting the fallback would have been a second,
   unasked deviation.

### Tests that changed or died

Retired outright, all of them assertions about behaviour this ticket removes:

- **`WorldsGameTest`** — all 5 cases. Its subject was the two-World topology: that
  Secondary's trio exists, that Travel toggles between Worlds, Position Memory, per-World
  login routing, and shared state riding through Travel. Every one names something deleted.
- **`RespawnAndPortalsGameTest.aBedInPrimaryNeverCatchesADeathInSecondary`** — a bed not
  reaching across Worlds, when there is one World.
- **`portalsKeepNonPlayersInTheirOwnTrioToo`** → `portalsRouteEntitiesThatAreNotPlayersToo`,
  rewritten against the vanilla trio. Its Secondary assertions were about the deleted portal
  mixins.
- **`eachWorldsBedCatchesOnlyItsOwnDeaths`** → `aBedCatchesTheDeathOfThePlayerWhoSleptInIt`,
  keeping the single-World half (a bed catches deaths beside it, repeatedly) and dropping the
  cross-World swap.
- **`dyingWithNoRespawnPointLandsAtTheWorldOfDeathsSpawn`** and the two portal cases keep
  their "Primary behaves exactly as vanilla does" halves, which are now the whole case. Worth
  keeping precisely *because* there is no longer a mixin between vanilla and the player:
  they are what will catch it if something starts translating again.
- **`TabListGameTest.playersInDifferentWorldsShareOneList`** →
  `playersInDifferentDimensionsShareOneList`, sending the traveler to the nether. This is
  what the case asserted before the Worlds existed at all.
- **`EmbassiesGameTest.embassiesBelongsToNoWorldSoTravelIgnoresIt`** →
  `embassiesIsStoredUnderItsOwnWorldName`. The `Worlds.worldOf(...) == null` assertion had
  nothing left that could claim the dimension; the legacy-name half is kept and a
  `locateInfo` assertion added.
- **`MigrationGameTest`** — both cases were about the Secondary half of a migration (logging
  into `mctraveler:secondary` and Travelling to the seeded bucket; a Region under
  `last_nether`). Neither is assertable on a server with no such dimension, so the fixture's
  migrant is now last in Primary and its Region is under `world_nether`. The suite's claim —
  what the importer wrote is what the live code reads — is unchanged for the half that can
  still be booted. **The Secondary half is ticket 11's, not this suite's.**
- **`OrphanedSaveClaimGameTest.aQuarantinedSaveIsClaimedByTheFirstLoginThatNamesIt`** — the
  claimant is now last in Primary, and the banked half is asserted by reading the record
  rather than by Travelling to it. The merged-claim case, which is the one that matters in
  production, is untouched and still drives a Secondary quarantine end to end.
- **`JsonPlayerStoreTest`** — the 6 bucket cases moved to `PerWorldBucketsTest` (7 cases,
  one added: the field keeps its position in the record, so a rehearsal diff says only what
  the merge did). One case replaces them in place, asserting `worlds` now passes through
  byte-for-byte as legacy data. That is the acceptance criterion, tested.
- **`RegionWorldsTest`** — `isSecondaryWorld` and the Secondary `legacyName` cases went with
  the entries. Two cases replace them, asserting the *opposite*: those dimensions map to no
  legacy name, and `last*` resolves to no dimension. `locateInfo` now asserts `overworld`
  rather than `primary/overworld`.
- **`WorldLayoutTest`** — gained two cases pinning Secondary's dimension ids and legacy
  strings as literals, since there is no longer anything shipped to cross-check them
  against, and that is exactly what `mergeWorlds` navigates by.
- **`RegionAdminCommandGameTest`** — three `/rg locate` assertions lose the server half.
- **`RegionImportTest`** — three renames and two refusal-message texts, following the
  message change from "no World of the merged server" to "neither of the Portal's Worlds".

Nothing was weakened to get green. Every removed assertion named Travel, a Per-World Bucket,
a Secondary dimension or a two-World rule.

### Counts

**460 unit + 294 gametests, 0 skipped, `./gradlew build` green.**

The starting point was 451 + 300 at `02be2c7`. Ticket 18 landed underneath this work and
added 4 unit tests to `WorldMergeTest` (40 → 44), so the base at `df0eb2f` was 455 + 300.
This ticket is therefore **+5 unit and −6 gametests**:

| | |
|---|---|
| `PerWorldBucketsTest` (new) | +7 |
| `WorldLayoutTest` | +2 |
| `RegionWorldsTest` | +1 |
| `JsonPlayerStoreTest` | −5 |
| `WorldsGameTest` (deleted) | −5 |
| `RespawnAndPortalsGameTest` | −1 |

All 148 `WorldMerge*` tests pass unchanged, driving the real MCA Selector against a fixture
that writes `dimensions/mctraveler/secondary{,_nether,_end}` on disk — which is the direct
evidence the merge tool still reads a Secondary save.

`./gradlew prodServer` was run and passes on a real dedicated server:

```
MCTraveler prod smoke: minecraft:overworld is live
MCTraveler prod smoke: minecraft:the_nether is live
MCTraveler prod smoke: minecraft:the_end is live
MCTraveler prod smoke: mctraveler:embassies is live
MCTraveler prod smoke: none of Secondary's dimensions exist, as expected
```

### What ticket 11 (the merge gametest) inherits

- **`MigrationGameTest` no longer covers the Secondary half of anything, and cannot.** Its
  two cases are now Primary-only. Everything ticket 09 took away from it — a player logging
  in at relocated Secondary coordinates, a Region protecting at its new place — is the merge
  gametest's to assert, against a *merged* fixture. That is the right home for it: a merged
  save names Primary's dimensions, which this build has.
- **There is no `travelToTheOtherWorld` helper any more**, and no Worlds service to reach. A
  gametest that wants a player somewhere puts them there with `arriveIn` (still in
  `TestPlayers.kt`).
- **The gametest server no longer creates Secondary's dimensions.** A merged fixture must
  therefore be a *post-merge* save — Secondary's chunk data already inside Primary's
  dimension folders. A fixture that still had `dimensions/mctraveler/secondary/` in it would
  be ignored by the server, silently.
- `PerWorldBuckets.of(record, world)` is how a test reads a banked bucket now;
  `MCTraveler.persistence.players` no longer has `bucket`.
- `SwitchSignpostGameTest` is untouched and still passes; it is the model for asserting
  player-facing merge output.

### What ticket 12 (runbook, ADRs, glossary) needs

- **The deployment hazard is real and now has teeth.** This build removes the three
  `data/mctraveler/dimension/secondary*.json` resources. Deployed before `mergeWorlds` has
  run, the server simply stops creating those dimensions and the chunk data still inside them
  becomes unreachable — no error, no warning, nothing in the log. The runbook line should say
  that the silence is the danger.
- **The smoke check is the gate, and it is cheap.** `./gradlew prodServer` now fails if any
  `mctraveler:secondary*` dimension exists. Its output is quotable as the post-deploy
  verification step (the five lines above).
- **ADR 0001 (shared player state across Worlds) is fully superseded**, not partly: `Worlds`,
  `World`, Travel, the Per-World Bucket and Position Memory are all gone from the running
  server. The Per-World Bucket is not *dead*, though — it survives as legacy data in player
  records and as `eu.mctraveler.importer.PerWorldBuckets`, which the migration tools still
  read and write. The successor should say "retired from the server" rather than "deleted",
  or a future reader will be surprised to find the type.
- **ADR 0003 (Embassies) needs its first sentence rewritten.** It currently defines a World
  as "a trio of dimensions with Travel, a Per-World Bucket, and Position Memory" and says
  `Worlds.worldOf` returns null for the Embassies — a method that no longer exists. The
  Embassies are unchanged; only the thing they were defined *against* is gone.
- **Glossary.** Beyond retiring World / Travel / Per-World Bucket / Position Memory: the
  **Region** entry says "in a World's dimension" and the **Embassies** entry says "not a
  World (ADR 0003)". Both need restating against dimensions. Note also that the Region layer
  deliberately *keeps* the Portal's legacy world vocabulary (`world`, `world_nether`,
  `world_the_end`) — that is stored-data compatibility, not a surviving World concept, and is
  worth one sentence so nobody "cleans it up" later.
- **One naming loose end.** `eu.mctraveler.worlds` still exists as a package, holding
  `/switch`, `BankedPositions`, `Landing`, `Waypoint` and `DimensionRole`, and `WorldsFeature`
  still wires it. Nothing in it is about Worlds any more. Renaming was deliberately left out
  of this ticket — it would have collided with ticket 18 in the importer — so if the glossary
  rewrite wants the code to match, that is the change.
