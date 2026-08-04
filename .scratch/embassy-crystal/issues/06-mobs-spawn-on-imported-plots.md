# 06 — Mobs spawn on the embassies plots

Status: resolved
Type: task

**Reported from production**, 2026-08-03: a player found a creeper waiting at the top
of their embassy tower. The dimension is supposed to be a museum — deviation 1 lists
"empty biome spawners" as the whole of "no natural mobs".

## What was actually wrong

The empty spawners in `mctraveler:embassies_plains` only ever applied to the chunks
this server generated for itself. The plots are not those: ticket 05 copies Nucleus's
`region/`, `entities/` and `poi/` in whole, and **a generated chunk stores its own
biomes in its sections**. Every imported plot therefore still says `minecraft:plains`
— full vanilla spawn list — and the flat generator's `biome` setting never gets a
say over it again.

Measured on production before the fix (`world/dimensions/mctraveler/embassies`):

```
biome palette entries   66384  minecraft:plains
                         3528  mctraveler:embassies_plains

entities on disk            9  minecraft:slime        5  minecraft:zombie
                            4  minecraft:skeleton     2  minecraft:creeper
                            1  minecraft:spider       + chickens, pigs, cows, sheep
```

So the rule could not live in the biome. It now lives in `EmbassiesFeature.accepts`,
on `ServerEntityEvents.ALLOW_LOAD`, where the dimension is the whole of the test:

- anything the world spawns of its own accord is refused (`NATURAL`,
  `CHUNK_GENERATION`, `SPAWNER`, `TRIAL_SPAWNER`, `PATROL`);
- anything hostile is refused whatever brought it, **including `LOAD`** — which is how
  the imported creepers and slimes are dropped on their chunk's next load and gone from
  disk the next time it is written;
- everything a person put there is kept: item frames, paintings, armour stands, and the
  animals somebody penned on a plot as part of an exhibit.

Covered by four cases in `EmbassiesGameTest`, including one asserting the rest of the
map still spawns as it always did.

## Still open, from the same root cause

The imported plots being `minecraft:plains` breaks the *other* half of deviation 1 as
well: that biome has `has_precipitation: true`, and weather state is shared from the
overworld's level data, so it rains on the plots whenever it rains on the map. Mobs
were the reported bug and are fixed; the weather is not, and cannot be fixed the same
way — it wants the imported chunks' biomes rewritten to `mctraveler:embassies_plains`,
which is an offline pass over the region files rather than a guard at runtime. That
rewrite would also make the biome the honest source of truth again and let the runtime
guard shrink back to the hostile-mob sweep. Worth a ticket of its own before anyone
trusts the biome file to mean what it says.

## Comments

Deployed to production ahead of the usual review cycle because it was live and
player-visible; deviation 1 in `spec.md` has been corrected to stop claiming the biome
does this work.
