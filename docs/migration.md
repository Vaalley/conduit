# Cutover runbook: Portal → Fabric

The one-time migration (spec User Stories 43–44) turns a live Portal deployment — the two
backend server directories plus the Portal's own data files — into a Fabric server run
directory ready to boot. It is safe to rehearse: it refuses to touch a run directory that
already holds a migrated save, and writes nothing at all unless the whole migration succeeds.

It is the first of two imports. The embassies predate the Portal and are in none of its files;
they come across separately, from the retired Nucleus server, before the new build's first
boot — see `docs/nucleus-import.md`.

## What it carries over

| From | To |
| --- | --- |
| `minecraft-server/primary/world` | `world` — the Primary trio, in the layout its own server wrote |
| `minecraft-server/secondary/last` (+ `DIM-1`, `DIM1`) | `world/dimensions/mctraveler/secondary{,_nether,_end}` — the Secondary trio |
| Backend `playerdata/<offline uuid>.dat` | `world/playerdata/<mojang uuid>.dat` — the save from the World the player was last in |
| The *other* backend's save | that World's Per-World Bucket in `mctraveler/players/<uuid>.json` |
| Backend `advancements/`, `stats/` | the same, re-keyed — from the live World only |
| Saves nobody could be named for | `mctraveler/orphaned-saves/<world-id>/` — claimed by their owner at login |
| `players/<uuid>.json` | `mctraveler/players/<uuid>.json`, byte-for-byte (legacy fields included) |
| `uuid-cache.json` | `mctraveler/uuid-cache.json`, plus every identity the migration resolved |
| Both backends' `ops.json` | `ops.json`, re-keyed to Mojang UUIDs |
| `regions.json` | `regions.json`, every world string checked against a real dimension |

## Before you run it

1. **Stop everything cleanly** — the Portal and both backends. A backend that is still running
   has unflushed playerdata, and you would migrate a stale save.
2. **Back up** the Portal directory and both backend directories. The importer only reads them,
   but a cutover without a rollback is not a cutover.
3. **Keep the backups for the rehearsal too.** Rehearse into a throwaway target directory, boot
   it, look around, then throw the target away and repeat for real.

## Run it

```sh
./gradlew migrate --args="--portal /srv/mctraveler --target /srv/mctraveler-fabric/run"
```

| Option | Meaning |
| --- | --- |
| `--portal <dir>` | the Portal's working directory (`players/`, `uuid-cache.json`, `regions.json`) |
| `--target <dir>` | the Fabric server's run directory; must hold no migrated save |
| `--primary <dir>` | the Primary backend's server directory (default `<portal>/minecraft-server/primary`) |
| `--secondary <dir>` | the Secondary backend's server directory (default `<portal>/minecraft-server/secondary`) |
| `--level-name <name>` | the level directory to write; must match the new server's `level-name` (default `world`) |
| `--identities <file>` | usernames → Mojang UUIDs, for players the Portal's cache never saw |
| `--skip-unidentified` | quarantine unidentifiable saves (claimed at their owner's next login) instead of refusing |
| `--worlds copy\|move` | how chunk data and quarantined saves reach the new save (default `copy`) |

### Identities

Every backend file is keyed by an *offline* UUID derived from the username; the merged server
keys everything by the player's real Mojang UUID. The migration resolves the two through:

1. the two aliased identities (DemonicNoodle → travelcraft2012, AlsoJames → iElmo) — always,
   and always winning, because the Portal keyed their data to the alias;
2. `--identities`;
3. the Portal's `uuid-cache.json`.

The Portal only ever filled that cache from `/op`, so expect the migration to stop with a list
of players it cannot identify, named from the backends' own `usercache.json`:

```
Migration refused, nothing was written: cannot identify every player the Portal's data mentions:
  Alice (7f2e… , primary)
  Bob (91ab… , secondary)
```

Look each name up against Mojang (`https://api.mojang.com/users/profiles/minecraft/<name>`) and
write a file:

```json
{ "Alice": "11111111-2222-4333-8444-555555555555", "Bob": "…" }
```

then re-run with `--identities identities.json`.

Resolving every name is *not* expected, and on the real deployment not feasible — the Portal's
cache covers a few hundred of roughly thirteen thousand offline-keyed saves. Pass
`--skip-unidentified` for the rest: their saves are quarantined, and each is handed back
automatically the first time its owner logs in. Use `--identities` for the players you want
live from the first boot (operators especially: an op list is read before anyone can connect,
so an operator no identity answers for is simply dropped and has to be re-`/op`ped).

## The quarantine, and claiming at login

A save nobody could be named for is parked under the mod's own directory, outside the level so
no vanilla file walker ever sees it:

```
mctraveler/orphaned-saves/
  primary/    <offline uuid>.dat  advancements/<offline uuid>.json  stats/<offline uuid>.json
  secondary/  …
```

The file name is `md5("OfflinePlayer:" + username)` — the same hash the offline-mode backends
used, and the only handle anyone will ever have on these files. When a player joins, the server
hashes the username they authenticated with and, if the quarantine holds saves under it, does
exactly what the migration would have done for them: their last World's save becomes their live
playerdata, the other World's seeds that World's Per-World Bucket, the live World's advancements
and statistics come across, and the quarantined files are removed. It happens before vanilla
reads their save, so the player just arrives where the Portal left them — there is nothing for
them to do and nothing for them to see.

**A player who already has a save is never overwritten.** That is the rule everything else bends
around: a username can be released and re-registered, so the hash is evidence of who a save
probably belongs to, never proof, and a live player's own data always wins.

Watch the log. Every claim writes one line, and so does every claim that could not be made:

```
[mctraveler] orphaned-save claim: Alice (…) claimed their Portal save — live World secondary,
             Per-World Bucket seeded for primary, DataVersion 4536
[mctraveler] orphaned-save claim: skipped for Bob (…) — they already have a save on this server…
[mctraveler] orphaned-save claim: FAILED for Carol (…): …
```

A **FAILED** line is an action item, not a warning to file away: nothing was written and the
quarantine is intact, but once that player plays and gets a save of their own the claim is
refused for good. The usual cause is playerdata this server cannot place (see the pre-1.16 note
below). A **skipped** line is worth a look too — it means a quarantined save is keyed to the name
of somebody who already plays here.

The quarantine only shrinks, never grows. Once the community has cycled through and the log has
gone quiet, whatever is left belongs to players who never came back, and the directory can be
archived and deleted.

## After it runs

The migration prints a summary. Then:

1. Put the server's own files in the run directory: `eula.txt`, `server.properties` with
   `online-mode=true`, `level-name` matching `--level-name`, and `max-players=20` (the Portal
   hardcoded a limit of 20; the port advertises the real one — deviation register 17).
2. **Run the Nucleus import** — `docs/nucleus-import.md`. It brings back the embassies, which
   predate the Portal and are in no Portal file: their plots, their regions and their owners'
   crystal energy. It has to happen **before** the first boot, because the embassies dimension
   folder must be on disk the first time that dimension loads.
3. Boot the server **once** and watch the log. Minecraft's own file fixer and data fixers do
   the version jump on first boot: the level directory is relaid out
   (`playerdata` → `players/data`, `region` → `dimensions/minecraft/overworld/region`, …) and
   every chunk and save is upgraded as it loads. This first boot takes longer than usual.
4. Verify, in this order:
   - the log lists `mctraveler:secondary`, `mctraveler:secondary_nether`, `mctraveler:secondary_end`;
   - an operator from the old server has operator status (`/op` list, or just use an admin command);
   - a well-travelled player logs in to the World they left, at the position they left;
   - `/switch` puts them back where the other World remembers them;
   - a player whose save was *quarantined* logs in and finds their inventory, XP and position —
     look for their `orphaned-save claim` line in the log;
   - `/rg locate <something you know>` finds a region in the right World;
   - an imported embassy is where it was — see `docs/nucleus-import.md`.

## Known limitations

Cutover facts to communicate, not bugs to fix:

- **Secondary's world seed.** The merged save keeps Primary's `level.dat`, and the whole server
  generates from one seed. Secondary's already-generated chunks come over intact, but terrain
  generated *beyond* its current frontier will not match what the old Secondary backend would
  have generated. Expect seams at the edge of explored Secondary terrain.
- **Secondary's level-wide saved data is not imported** — maps, in-progress raids, its world
  border, force-loaded chunks and scoreboard objectives. Map ids are level-wide and cannot be
  merged with Primary's without renumbering every map item. Primary's carry over normally.
- **Advancements and statistics come from the live World only.** A player's progress on the
  other backend is dropped: two sets cannot merge into the one shared set the port keeps
  (ADR 0001).
- **Whitelists and bans are not imported.** The Portal did its own authentication; set them up
  fresh if you want them.
- **Playerdata that predates 1.16** (a player who has not logged in for years) names its
  dimension in the pre-1.16 form and is refused by name rather than guessed at. Delete the file
  or boot the old backend once to upgrade it. A *quarantined* save in that state is refused the
  same way, as a `FAILED` claim line when its owner logs in — so a scan of the quarantine before
  cutover is time well spent.
- **A username that changed hands claims the wrong save.** The offline hash of a name is all the
  quarantine has to go on, so a player who registered a name a Portal-era player once used would
  inherit that player's save. Nothing can distinguish them — the hash is one-way and no other
  identity was ever recorded. The live-player guard bounds the damage (an established player is
  never overwritten) and every claim is logged, which is how such a case would be spotted and
  undone from backups.
- **The aliased players have no skin.** Their profile carries the alias, and Mojang's textures
  do not sign for it — exactly as on the Portal.
