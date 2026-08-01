# 05 — Nucleus-era import: embassies world, regions, crystal energy

**What to build:** A post-cutover import command (gradle task alongside `migrate`)
run against a stopped Fabric server: copy the Nucleus embassies world's
`region/`, `entities/`, `poi/` into `world/dimensions/mctraveler/embassies/`;
convert the embassy regions (world `"embassies"`, EMBASSY flag, x/z/y bounds,
`embassy-destination` metadata with legacy world names) from the Nucleus
`regions.json` into the live store's schema; import
`mctravelernucleus:tc-teleportation-energy` / `tc-next-regen-at` from playerdata
`PublicBukkitValues` into the PlayerStore fields. Idempotent and refusing rather
than double-importing or overwriting; document the run in the migration doc.

**Blocked by:** 02 (region metadata schema), 03 (energy store fields).

**Status:** done

See ../spec.md (User Stories 38–39; Implementation Decisions "Importer"; deviations
6, 14) and docs/migration.md for the house importer conventions (refuse loudly,
write nothing on partial failure, rehearse-then-run). Real sources on the dedi:
`/root/MCTraveler-Old-Data/Server/embassies/`,
`/root/MCTraveler-Old-Data/Server/plugins/MCTravelerNucleus/regions.json` (2674
regions, 20 with world "embassies"),
`/root/MCTraveler-Old-Data/Server/world/playerdata/*.dat` (25 files carry the tags;
online-mode server, so UUIDs are Mojang UUIDs — no re-keying). Target:
`/root/mctraveler-server`.

- [x] CLI: `--old <nucleus-server-dir> --target <fabric-run-dir>` (+ `--worlds
      copy|move` defaulting to copy); refuses when the target already has an
      embassies dimension folder or embassy regions; all-or-nothing writes
- [x] Region conversion: titles, members, EMBASSY flag, full-height bounds, and
      metadata land in the live regions.json; non-embassy entries and formatting
      byte-identical; count reported
- [x] Energy import: gzip NBT read of playerdata; tags written to the player store
      only where the store has no value; players without tags untouched; count
      reported
- [x] Unit tests against fixture directories (embassies world tree, Nucleus
      regions.json snippet, a crafted playerdata .dat), including idempotence and
      the refusal cases
- [x] docs/migration.md (or a linked doc) gains the run instructions, including
      "server must be stopped; import before the new build's first boot"

## Comments

### Implementation summary

A second gradle task beside `migrate`, built as its twin: same argument grammar,
same `MigrationRefused`/"nothing was written" contract, same read-everything-
then-stage-then-commit shape. `gradle/import-nucleus.gradle.kts` registers it and
`build.gradle.kts` gains one line.

Four new files in `eu.mctraveler.importer`:

- **`NucleusRegions`** — Nucleus's `regions.json` (a *different* format from the
  Portal's: a top-level array, `kotlinx`'s rendering of its `RegionData`, corners
  as nested `start`/`end` points) into the live store's `Region`. Selects by
  world string, carries `metadata` across as the raw Gson `JsonElement`s so
  number literals survive (`64.0` does not come back as `64`).
- **`NucleusPlayerdata`** — the two `mctravelernucleus:` ints out of a Bukkit
  persistent data container at the root of a save.
- **`EmbassyImport`** — the engine: refusals, conversion, staging in
  `<target>/.mctraveler-embassy-import/`, commit, report.
- **`EmbassyImportMain`** — the CLI.

`docs/nucleus-import.md` is the operator runbook; `docs/migration.md` now names
it as step 2 of "After it runs", ahead of the first boot, and says why the order
is not negotiable.

**46 new unit tests** (`EmbassyImportTest` 26, `NucleusRegionsTest` 14,
`NucleusPlayerdataTest` 6) over `NucleusDeploymentFixture` — a miniature cutover
night on disk. Full `./gradlew build` green.

Also smoke-tested through the real gradle task against a hand-built fixture: the
happy path prints its summary and writes the dimension folder, and a second run
exits 1 with the already-imported refusal.

### The command to run on the dedi

With the server **stopped**, after `migrate`, and **before the new build's first
boot**:

```sh
./gradlew importNucleus --args="--old /root/MCTraveler-Old-Data/Server --target /root/mctraveler-server"
```

Expect `embassy regions imported : 20` and `players' energy imported : 25`. A
zero on either line means `--old` is not pointing where it should be.

### Public surface

- `EmbassyImportPlan(oldDir, targetDir, levelName = "world", worldTransfer)`,
  `EmbassyImport(plan).run(): EmbassyImportReport`.
- `NucleusRegions.regionsIn(text, world): List<Region>` and
  `unknownDestinationWorlds(regions): List<String>`.
- `NucleusPlayerdata.energyOf(tag): Energy?`, plus the two key constants.
- `WorldTransfer` and `MigrationRefused` are reused from `PortalImport.kt`
  unchanged.

### Deviations and judgement calls

1. **Both Bukkit container spellings are read, and the answer is reported.**
   CraftBukkit names an *entity's* persistent data container `BukkitValues` and
   an *item's* `PublicBukkitValues`; the ticket named the latter, my reading of
   CraftBukkit says the former for a player, and neither can be settled without a
   real Bukkit save to hand. `NucleusPlayerdata` reads whichever the save
   carries — one extra map lookup — and the summary prints which one answered
   (`players' energy imported : 25 (from "BukkitValues")`). A wrong guess would
   otherwise have shown up as a silent zero on cutover night. **Worth confirming
   against one real `.dat` before the run**; the importer is correct either way,
   but the reported string is the cheap proof.
2. **`region/` missing refuses; `entities/`/`poi/` missing is reported.** The
   ticket asked for a refusal on any missing expected source. A world with no
   `region/` is not the world we were asked for, so that refuses. But a world can
   legitimately have never written a `poi/`, and refusing there would leave the
   operator with nothing to do but `mkdir` an empty directory to satisfy us. They
   are named in the summary as `NOT IN THE OLD WORLD : poi` instead.
3. **A player is skipped when the target store holds *either* crystal field.**
   The ticket says "where the store currently has NO crystalEnergy value"; the
   live code never writes a threshold without an energy, so the two readings
   agree on every real record — but checking both makes "never overwrite"
   airtight rather than nearly so.
4. **A point that omits its `y` keeps Nucleus's own defaults (320 and 15), not
   the live store's (320 and −64).** The twenty real embassies all carry explicit
   320/−64, so this never fires in production; it exists so the conversion is
   faithful rather than lucky. Converting a `y`-less region to −64 would silently
   deepen it by 79 blocks.
5. **A malformed `embassy-destination` refuses; a missing one does not.** The
   destination is the whole point of an imported plot, and one that teleports
   nowhere is worse than a refused import — but an owner who never set one is an
   ordinary state.
6. **A destination naming a world this server does not have warns, not refuses**
   — `RegionWorlds.dimensionFor`'s own stated contract ("null is a real answer").
   The embassy still imports; the summary names it as `destination world gone`.
7. **Out-of-range energy is clamped to 0..3 and named.** Nucleus clamped on
   write, so anything outside the range is damaged data rather than intent.
8. **`--level-name` exists beyond the ticket's CLI**, defaulting to `world`, for
   the same reason `migrate` has it: if the cutover used a different `level-name`
   this import must follow it or write the dimension into a level nobody loads.
9. **The commit is three steps, not one atomic move** (dimension folder, then
   `regions.json`, then the player records). The target is a live run directory,
   so there is nothing to rename wholesale the way `migrate` does. A crash
   *between* steps leaves a partial import — and the next run refuses and says
   which half landed, which is the right hand-off to a human. Everything that can
   fail happens before the first of the three.
10. **The double-import guard scans the whole region tree, not just the roots.**
    An embassy is always a root, so this is belt-and-braces on the one guard that
    stands between a slip and a doubled region file.

### Things the deploy runbook must know

- Reading the energy tags opens **every** save in `<old>/world/playerdata/`, one
  gzip file at a time, and prints nothing while it does. On a directory of
  thousands that is minutes of apparent silence — expected, not a hang.
- `--worlds move` is the one operation that is not undoable: a failure *after*
  the chunk data has moved leaves the staging directory holding the only copy,
  and the importer says so at length and refuses to delete it. Use the default
  `copy` unless disk space forces the other.
- Nucleus-era crystal **items** are not rewritten by this importer and do not
  need to be — deviation 18 has `CrystalItem` recognising the Bukkit marker
  layout at runtime. This ticket only ever touches energy.
- If the server boots before the import runs, vanilla creates an empty
  `world/dimensions/mctraveler/embassies/` and the importer then refuses. The
  recovery is to delete that empty folder and run the import; it is in the doc.
