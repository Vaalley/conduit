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

**Status:** ready-for-agent

See ../spec.md (User Stories 38–39; Implementation Decisions "Importer"; deviations
6, 14) and docs/migration.md for the house importer conventions (refuse loudly,
write nothing on partial failure, rehearse-then-run). Real sources on the dedi:
`/root/MCTraveler-Old-Data/Server/embassies/`,
`/root/MCTraveler-Old-Data/Server/plugins/MCTravelerNucleus/regions.json` (2674
regions, 20 with world "embassies"),
`/root/MCTraveler-Old-Data/Server/world/playerdata/*.dat` (25 files carry the tags;
online-mode server, so UUIDs are Mojang UUIDs — no re-keying). Target:
`/root/mctraveler-server`.

- [ ] CLI: `--old <nucleus-server-dir> --target <fabric-run-dir>` (+ `--worlds
      copy|move` defaulting to copy); refuses when the target already has an
      embassies dimension folder or embassy regions; all-or-nothing writes
- [ ] Region conversion: titles, members, EMBASSY flag, full-height bounds, and
      metadata land in the live regions.json; non-embassy entries and formatting
      byte-identical; count reported
- [ ] Energy import: gzip NBT read of playerdata; tags written to the player store
      only where the store has no value; players without tags untouched; count
      reported
- [ ] Unit tests against fixture directories (embassies world tree, Nucleus
      regions.json snippet, a crafted playerdata .dat), including idempotence and
      the refusal cases
- [ ] docs/migration.md (or a linked doc) gains the run instructions, including
      "server must be stopped; import before the new build's first boot"

## Comments
