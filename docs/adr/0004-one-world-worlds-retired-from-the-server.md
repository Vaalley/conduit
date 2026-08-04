# One World: the Worlds are retired from the server

**Supersedes ADR 0001 (shared player state across Worlds).**

ADR 0001 decided how player state should be divided between two Worlds. That question no longer
exists. Secondary has been merged into Primary — its overworld and nether relocated as chunk data
into Primary's own at a fixed offset, its End discarded — so the server runs a single trio of
dimensions on one seed, and Secondary is a landmass players can walk to rather than a place they
teleport to. With one World there is nothing to divide state between, and the whole mechanism
that did the dividing is gone from the running server: the Worlds service, Travel, the World-to-
dimension role resolution, the respawn and portal routing that translated between trios, the
`mctraveler:secondary{,_nether,_end}` dimension resources, and the Per-World Bucket and Position
Memory in the persistence model.

**Retired from the server is not the same as deleted.** Two things survive deliberately, and a
reader who expects them to be gone will be surprised to find them:

- The **Per-World Bucket** lives on as legacy data inside player records, and as
  `eu.mctraveler.importer.PerWorldBuckets`. The live store no longer models it — `worlds` is
  simply one more legacy field, covered by the byte-for-byte pass-through guarantee every other
  legacy field gets — but the migration tools still read and write it. They must: `migrate`
  produces the two-World save the merge later reads, and the merge itself runs offline against a
  save that still has Secondary's dimension folders.
- The **legacy world vocabulary** in the Region layer. Regions still record `world`,
  `world_nether` and `world_the_end` as strings, exactly as the Portal wrote them. That is
  stored-data compatibility, not a surviving World concept, and it is deliberately not cleaned
  up. Secondary's `last*` entries *were* removed, so an unswept Region resolves to nothing and is
  visibly nowhere rather than quietly somewhere.

What ADR 0001 got right is unchanged and is now simply the shape of the game: inventory, XP,
ender chest, advancements and statistics are one pool per player, because there is one place to
have them in.

## Consequences

- The gameplay change ADR 0001 accepted knowingly — items and XP moving between Worlds — stops
  being a change at all. There is nowhere for them to move between.
- Position is no longer special. A player has one position, in one map, and vanilla keeps it.
- Retrofitting per-World separation is no longer merely costly, it is meaningless without first
  re-splitting the map. Anything that wants a second World in future is designing a new feature,
  not restoring an old one.
- `/switch` survives as a signpost rather than as Travel, because it is the one command every
  returning player types first and an unknown-command error there reads as a broken server. See
  `docs/merge.md`.
- The merge tool's knowledge of Secondary is load-bearing and must not be tidied away. It
  navigates by storage folder rather than by registry, which is exactly why it still works
  against dimensions this server can no longer create.
