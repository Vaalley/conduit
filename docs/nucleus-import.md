# Cutover runbook, part two: Nucleus → Fabric (the embassies)

The Portal migration (`docs/migration.md`) carries over everything the *Portal* era held.
It does not touch the embassies, because the Portal never had them: they belong to
MCTravelerNucleus, the Bukkit plugin that ran before it, whose server directory has been
sitting in cold storage ever since.

This second import (spec User Stories 38–39) brings that world back:

| From the Nucleus server | To the run directory |
| --- | --- |
| `embassies/{region,entities,poi}` | `world/dimensions/mctraveler/embassies/…` — the plots, as bytes |
| `plugins/MCTravelerNucleus/regions.json`, entries in world `embassies` | `regions.json` — appended, with titles, members, the `EMBASSY` flag and each embassy's saved destination |
| `world/playerdata/<uuid>.dat` → `mctravelernucleus:tc-teleportation-energy` / `tc-next-regen-at` | `mctraveler/players/<uuid>.json` → `crystalEnergy` / `crystalNextRegenAt` |

Nucleus ran in online mode, so its uuids are already Mojang uuids: there is no identity
step here and nothing to re-key.

## When to run it

**With the server stopped, after `migrate`, and before the new build's first boot.**

That order is not a preference. The embassies dimension folder has to be on disk the first
time the dimension loads; if the server boots first, vanilla creates an empty
`world/dimensions/mctraveler/embassies/` and the plots are gone — and because the importer
refuses to write into a dimension folder that already exists, the recovery is to delete
that empty folder before running the import.

The regions and the crystal energy have no such deadline, but they are part of the same
command, so run the whole thing before the first boot and there is nothing to remember.

## Before you run it

1. **Stop the server.** The importer writes `regions.json` and player records directly; a
   running server holds both in memory and would overwrite the import at its next save.
2. **Back up the run directory**, and keep the Nucleus directory read-only if you can. The
   import only reads it under the default `--worlds copy`.
3. **Rehearse.** Copy the run directory somewhere throwaway, import into the copy, boot it,
   walk the embassies, then throw it away and do it for real. The import refuses to run
   twice against the same directory, which is what makes a rehearsal safe to repeat.

## Run it

```sh
./gradlew importNucleus --args="--old /root/MCTraveler-Old-Data/Server --target /root/mctraveler-server"
```

| Option | Meaning |
| --- | --- |
| `--old <dir>` | the Nucleus server directory (holds `embassies/`, `plugins/MCTravelerNucleus/`, `world/`) |
| `--target <dir>` | the migrated Fabric run directory; must already hold `regions.json`, `mctraveler/` and the level |
| `--level-name <name>` | the level directory to write into; must match the server's `level-name` (default `world`) |
| `--worlds copy\|move` | how the embassies chunk data reaches the new save (default `copy`) |

`--worlds move` renames the chunk folders out of the Nucleus directory instead of copying
them: instant, and free of the need for room for a second copy, at the price of the
all-or-nothing guarantee. Use `copy` unless disk space forces the other.

Reading the energy tags means opening every save in `<old>/world/playerdata/`, one gzip
file at a time, so the command spends most of its wall clock there and prints nothing while
it does. That is normal.

## What it prints

```
Imported /root/MCTraveler-Old-Data/Server into /root/mctraveler-server:
  embassy regions imported : 20
  players' energy imported : 25 (from "BukkitValues")
  players already set here : 0
  chunk files transferred  : 6
  world bytes transferred  : 4194304
```

Check the first two numbers against what you expect: **20 embassy regions and 25 players**
on the real deployment. A zero on either line means `--old` is not pointing where you think
it is, and the command says so.

Extra lines appear only when there is something to act on:

| Line | Meaning |
| --- | --- |
| `NOT IN THE OLD WORLD   : poi` | the Nucleus world had no such chunk folder; harmless for `poi`/`entities`, and the import continues |
| `destination world gone : <embassy> → "<world>"` | that embassy's anchor points at a world this server does not have. The plot imports fine; standing on the anchor simply goes nowhere. Worth fixing with `/embassy` before players find it |
| `energy clamped to 0..3 : <uuid> had 9` | a damaged energy value, brought into range |
| `ignored non-uuid file  : …` | a file in `playerdata/` that is not `<uuid>.dat`; left where it is |

## It refuses rather than half-importing

Nothing is written unless the whole import succeeds: everything is read, converted and
checked first, the output is built in `<target>/.mctraveler-embassy-import/`, and only a
complete import is moved into place. Every refusal below leaves the run directory untouched.

| Refusal | What it means |
| --- | --- |
| `<target>/world/dimensions/mctraveler/embassies already exists — the embassies have been imported already` | the import has run. This is also what a second run hits |
| `<target>/regions.json already holds N region(s) in world "embassies" … — the embassy regions have been imported already` | the regions half has run |
| `<target> has no "regions.json" — import into the migrated server run directory …` | wrong `--target`, or `migrate` never ran. Also raised for a missing `world`/`mctraveler` |
| `<target> is not a directory` | wrong `--target` |
| `<old>/embassies does not exist — is <old> the Nucleus server directory?` | wrong `--old`. The same wording covers `embassies/region`, `plugins/MCTravelerNucleus/regions.json` and `world/playerdata` |
| `<target>/.mctraveler-embassy-import is left over from an interrupted import; remove it and run again` | a previous run was killed. Check what is inside it, then remove it |
| `Import failed, nothing was written: …` | a Nucleus file the importer would rather refuse than guess about — a malformed `embassy-destination`, a member that is not a uuid, an unreadable save |

The one case that is *not* recoverable by re-running is a failure under `--worlds move`
after the chunk data has moved. The command says so in full: the staging directory then
holds the only copy of those chunks, and it is left in place deliberately.

## After it runs

1. Boot the server once and watch the log for `mctraveler:embassies`.
2. `/rg locate` an embassy owner's name — the imported region should be found in
   `embassies`.
3. Visit one: the crystal menu's Embassy destination drops you at the dimension's origin;
   walk to a plot and check the build is there and the anchor works.
4. Ask a returning player to check their crystal's charge, or read
   `mctraveler/players/<uuid>.json` for `crystalEnergy`.

## Known limitations

- **A player who already has crystal energy on this server is skipped**, both energy and
  threshold. They have played since the cutover and what they have now is newer than
  anything Nucleus remembers. The count is reported as `players already set here`.
- **The plots are 1.21-era chunks.** Vanilla's own data fixers upgrade them the first time
  each is loaded (deviation register 14), so the first visit to the dimension is slower
  than later ones. Nothing is converted by the importer.
- **Nucleus-era crystals in migrated inventories keep working** — crystal identification
  reads the Bukkit marker layout as well as ours (deviation register 18) — but the importer
  does not rewrite them. It cannot: they are in chests, ender chests and shulkers all over
  the map.
- **Which Bukkit container the energy tags sit in is reported, not assumed.** CraftBukkit
  names an entity's persistent data container `BukkitValues` and an item's
  `PublicBukkitValues`; the importer reads whichever the save carries and prints the answer
  beside the count, so a wrong guess would show up as `0` rather than as silence.
