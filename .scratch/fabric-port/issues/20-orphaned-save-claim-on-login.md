# 20 — Claim orphaned saves at login

**What to build:** Players whose Portal-era save could not be identified at migration time get it back, seamlessly, the first time they log in — because their username (the one thing that can unlock an offline-keyed save) is finally known.

**Blocked by:** 18 (Importer). Must land before the production cutover.

**Status:** ready-for-agent

## Why

An offline-mode backend named each save after `md5("OfflinePlayer:" + username)`, which is one-way. At migration time the only name sources are the Portal's `uuid-cache.json` and the backends' `usercache.json`, and on the real deployment those cover just 778 of ~12,922 offline-keyed saves. The other ~12,144 are unidentifiable *at that moment* — but every one of them becomes identifiable the instant its owner joins and hands us their username.

Measured on production (July 2026, `play.mctraveler.eu`): Primary 11,825 offline-keyed / 2,700 Mojang-keyed; Secondary 1,097 / 3. Of the offline-keyed, 778 resolve from known names. Without this ticket, ~12,144 players lose inventory, XP and position (their builds survive — chunks are world data).

## What to build

- [ ] The importer quarantines unidentifiable offline-keyed saves instead of leaving them in the retired Portal tree: `mctraveler/orphaned-saves/<world-id>/<offlineUuid>.dat`, with the matching `advancements/` and `stats/` sidecars beside them. Replaces today's "left behind" reporting for these saves; the report says how many were quarantined.
- [ ] On login, before vanilla reads the player's own save, compute `md5("OfflinePlayer:" + username)` and look for quarantined saves under it. If found **and the player has no existing save**, claim them: the save from their last World becomes their live playerdata under their Mojang UUID; the other World's save seeds that World's Per-World Bucket; advancements and stats come across; the quarantined files are removed.
- [ ] A player who already has a save is never overwritten — the claim is skipped and the orphan left alone (someone else's data must never land on a live player).
- [ ] Which World is "live" follows the same rule as the migration: the Portal record's `lastServer` if one exists for that UUID, otherwise Primary.
- [ ] Claiming is logged (username, which World was live, whether a bucket was seeded) so a cutover can be audited.
- [ ] Unit tests for the claim decision and the file moves; a gametest proving a real login inherits a quarantined save's position and inventory, and that a player with an existing save keeps theirs.

## Notes for the implementer

- Reuse `PlayerdataImport` for the live-save rewrite and bucket extraction rather than reimplementing it — the claim is the per-player half of a migration, performed later.
- Timing is the crux: the claim must happen **before** vanilla loads the player's save (`PlayerDataStorage.load`), so the file is already in place and the login path stays completely ordinary. A Java mixin per ADR 0002.
- `OfflineUuid` already exists in the importer package and is exactly the hash needed.
- Quarantined saves are Portal-era 1.21.10 data. Vanilla's data fixers upgrade a save on read, so a claim performed after the world has been upgraded still works — but say so explicitly in the comments, and if a DataVersion check is cheap, prefer being loud over silently loading ancient NBT.
- Keep the quarantine directory outside the level so vanilla never walks it.
