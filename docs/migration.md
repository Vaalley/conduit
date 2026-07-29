# Cutover runbook: Portal → Fabric

The one-time migration (spec User Stories 43–44) turns a live Portal deployment — the two
backend server directories plus the Portal's own data files — into a Fabric server run
directory ready to boot. It is safe to rehearse: it refuses to touch a run directory that
already holds a migrated save, and writes nothing at all unless the whole migration succeeds.

## What it carries over

| From | To |
| --- | --- |
| `minecraft-server/primary/world` | `world` — the Primary trio, in the layout its own server wrote |
| `minecraft-server/secondary/last` (+ `DIM-1`, `DIM1`) | `world/dimensions/mctraveler/secondary{,_nether,_end}` — the Secondary trio |
| Backend `playerdata/<offline uuid>.dat` | `world/playerdata/<mojang uuid>.dat` — the save from the World the player was last in |
| The *other* backend's save | that World's Per-World Bucket in `mctraveler/players/<uuid>.json` |
| Backend `advancements/`, `stats/` | the same, re-keyed — from the live World only |
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
| `--skip-unidentified` | leave unidentifiable saves behind on purpose, instead of refusing |

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

then re-run with `--identities identities.json`. Only pass `--skip-unidentified` for players
you have decided to leave behind — their inventory, position and advancements stay behind
with them; they will log in as brand-new players.

## After it runs

The migration prints a summary. Then:

1. Put the server's own files in the run directory: `eula.txt`, `server.properties` with
   `online-mode=true`, `level-name` matching `--level-name`, and `max-players=20` (the Portal
   hardcoded a limit of 20; the port advertises the real one — deviation register 17).
2. Boot the server **once** and watch the log. Minecraft's own file fixer and data fixers do
   the version jump on first boot: the level directory is relaid out
   (`playerdata` → `players/data`, `region` → `dimensions/minecraft/overworld/region`, …) and
   every chunk and save is upgraded as it loads. This first boot takes longer than usual.
3. Verify, in this order:
   - the log lists `mctraveler:secondary`, `mctraveler:secondary_nether`, `mctraveler:secondary_end`;
   - an operator from the old server has operator status (`/op` list, or just use an admin command);
   - a well-travelled player logs in to the World they left, at the position they left;
   - `/switch` puts them back where the other World remembers them;
   - `/rg locate <something you know>` finds a region in the right World.

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
  or boot the old backend once to upgrade it.
- **The aliased players have no skin.** Their profile carries the alias, and Mojang's textures
  do not sign for it — exactly as on the Portal.
