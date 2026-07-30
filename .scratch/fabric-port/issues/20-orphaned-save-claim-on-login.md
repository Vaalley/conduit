# 20 — Claim orphaned saves at login

**What to build:** Players whose Portal-era save could not be identified at migration time get it back, seamlessly, the first time they log in — because their username (the one thing that can unlock an offline-keyed save) is finally known.

**Blocked by:** 18 (Importer). Must land before the production cutover.

**Status:** done

## Why

An offline-mode backend named each save after `md5("OfflinePlayer:" + username)`, which is one-way. At migration time the only name sources are the Portal's `uuid-cache.json` and the backends' `usercache.json`, and on the real deployment those cover just 778 of ~12,922 offline-keyed saves. The other ~12,144 are unidentifiable *at that moment* — but every one of them becomes identifiable the instant its owner joins and hands us their username.

Measured on production (July 2026, `play.mctraveler.eu`): Primary 11,825 offline-keyed / 2,700 Mojang-keyed; Secondary 1,097 / 3. Of the offline-keyed, 778 resolve from known names. Without this ticket, ~12,144 players lose inventory, XP and position (their builds survive — chunks are world data).

## What to build

- [x] The importer quarantines unidentifiable offline-keyed saves instead of leaving them in the retired Portal tree: `mctraveler/orphaned-saves/<world-id>/<offlineUuid>.dat`, with the matching `advancements/` and `stats/` sidecars beside them. Replaces today's "left behind" reporting for these saves; the report says how many were quarantined.
- [x] On login, before vanilla reads the player's own save, compute `md5("OfflinePlayer:" + username)` and look for quarantined saves under it. If found **and the player has no existing save**, claim them: the save from their last World becomes their live playerdata under their Mojang UUID; the other World's save seeds that World's Per-World Bucket; advancements and stats come across; the quarantined files are removed.
- [x] A player who already has a save is never overwritten — the claim is skipped and the orphan left alone (someone else's data must never land on a live player).
- [x] Which World is "live" follows the same rule as the migration: the Portal record's `lastServer` if one exists for that UUID, otherwise Primary.
- [x] Claiming is logged (username, which World was live, whether a bucket was seeded) so a cutover can be audited.
- [x] Unit tests for the claim decision and the file moves; a gametest proving a real login inherits a quarantined save's position and inventory, and that a player with an existing save keeps theirs.

## Notes for the implementer

- Reuse `PlayerdataImport` for the live-save rewrite and bucket extraction rather than reimplementing it — the claim is the per-player half of a migration, performed later.
- Timing is the crux: the claim must happen **before** vanilla loads the player's save (`PlayerDataStorage.load`), so the file is already in place and the login path stays completely ordinary. A Java mixin per ADR 0002.
- `OfflineUuid` already exists in the importer package and is exactly the hash needed.
- Quarantined saves are Portal-era 1.21.10 data. Vanilla's data fixers upgrade a save on read, so a claim performed after the world has been upgraded still works — but say so explicitly in the comments, and if a DataVersion check is cheap, prefer being loud over silently loading ancient NBT.
- Keep the quarantine directory outside the level so vanilla never walks it.

## Comments

Implemented in `eu.mctraveler.importer` (the claim is the migration's per-player half, built entirely out of the importer's own transforms) plus one Java mixin. `./gradlew build` green twice in a row: **204 headless gametests + 166 unit tests**, up from 202/148 — 2 new gametests (`OrphanedSaveClaimGameTest`), 13 new unit tests (`OrphanedSaveClaimTest`) and net +5 in `PortalImportTest`. `prodServer` not run (unchanged by this ticket).

**Hook point.** `PlayerDataStorageMixin` — `@Inject` at `HEAD` of `PlayerDataStorage.load(NameAndId)`. Signature verified against the mapped 26.2 jar rather than assumed: `public Optional<CompoundTag> load(NameAndId)`, which delegates to a private `load(NameAndId, String)` for `.dat` then `.dat_old`, and then runs `DataFixTypes.PLAYER.updateToCurrentVersion` on whatever it read. It is the deepest common point of a login: `PrepareSpawnTask.start` calls it in the configuration phase to decide the dimension and position the player will spawn at, and `PrepareSpawnTask$Ready.spawn` calls it again to build the player. Claiming at the head of the first call means the file is simply *there* when vanilla looks — no teleport, no second load, nothing the player can see — and the second call is a no-op because the quarantine has been consumed. `NameAndId` is a record of exactly the two things the claim needs (`id()`, `name()`).

`OrphanedSaveClaimFeature` supplies the paths from `server.getWorldPath(LevelResource.PLAYER_DATA_DIR / PLAYER_ADVANCEMENTS_DIR / PLAYER_STATS_DIR)` — i.e. `world/players/{data,advancements,stats}` **after** vanilla's boot-time relayout, which is the layout every claim writes into. This is why a claim needs no file fixer of its own: the relayout is gated on the level's DataVersion and has already happened by the time anyone logs in.

**Quarantine layout** (`SaveQuarantine`, the single place that knows it — writer is `PortalImport`, reader is `OrphanedSaveClaim`):

```
<run dir>/mctraveler/orphaned-saves/
  primary/    <offline uuid>.dat  advancements/<offline uuid>.json  stats/<offline uuid>.json
  secondary/  …
```

Under the mod directory, **outside the level**, so no vanilla chunk walker, file fixer or playerdata reader ever sees a save keyed to an identity this server does not use. Files stay keyed by *offline* uuid because that hash of a username is the only handle anyone will ever have on them. Both Worlds' sidecars are quarantined even though only the live World's are ever used: which World is live depends on a Portal record that cannot be read without the Mojang uuid the migration does not have yet, so that choice belongs to the claim. `WorldTransfer` is honoured (`--worlds move` renames the saves out of the backend), and the quarantine transfer shares `stageTransfers` with the level transfer so it keeps the same copy-first / move-last discipline and the same "never delete staging once data has been moved" failure path.

**The live-player guard — the one line that matters.** `OrphanedSaveClaim` refuses if `Files.exists(<uuid>.dat) || Files.exists(<uuid>.dat_old)`. Both spellings, because vanilla's own `load` falls back to `.dat_old`, so a player whose save survives only as the backup is still a live player. The reasoning, stated in the KDoc and in spec register entry 55: an offline uuid is a hash of a *username*, usernames can be released and re-registered, so the hash is evidence of who a save probably belongs to and never proof — and even where it is the right player, their quarantined save is years older than the one they are playing. Tested three ways: a unit test with a real `.dat` in place (asserts the live XP survives, the orphan file survives, and that not even `lastServer` is rewritten), a unit test with only a `.dat_old`, and a gametest that logs a real player in over a quarantined save keyed to their own name and asserts they get their own XP, inventory and position while the orphan is left on disk. Deleting the guard turns both unit tests red — checked.

**Write order is recovery order.** Bucket, `lastServer` and the sidecars first; the live save last, because it is the file the guard keys on and therefore the point of no return; then the quarantine is consumed. Interrupted before the live save, the next login claims again from an untouched quarantine. Interrupted after it, the next login is refused by the guard and the leftover quarantine files are cleanup, not loss. The claim is also split into a prepare phase (reads and transforms, nothing written) and a perform phase, so a `Failed` outcome can say honestly which one broke — an audit line claiming nothing was written when something was would be worse than no line.

**For the cutover operator** (runbook updated: `docs/migration.md` gained the quarantine layout, the log lines and the limitations):

- `--skip-unidentified` **no longer means "abandon"** — it means "quarantine, to be claimed at login". This inverts step 1 of the cutover checklist: resolving every name is no longer the goal, and on production is not feasible (778 of ~12,922). Build an identities file for the players you want live from the first boot, and pass `--skip-unidentified` for the rest. **Operators are the exception and must be in the identities file**: an op list is read before anyone can connect, so it can never be claimed, and an unresolved operator is dropped and has to be re-`/op`ped. The refusal message says this, but only when operators are actually affected.
- **Watch the log.** Every claim writes one `orphaned-save claim` line at INFO with the username, the live World, whether a bucket was seeded, and the DataVersion the save carried in. A **skipped** line (WARN) means a quarantined save is keyed to a name somebody already plays under — worth a look. A **FAILED** line (ERROR) is an action item, not a warning to file: nothing was written and the quarantine is intact, but once that player plays and gets a save of their own the guard refuses the claim for good. Likeliest cause is pre-1.16 playerdata (register entry 49), so scanning the quarantine before cutover is time well spent.
- **The quarantine only shrinks.** Once the community has cycled through and the log has gone quiet, what remains belongs to players who never came back, and the directory can be archived and deleted.
- **Ancient NBT is handled but reported, not hidden.** A quarantined save is Portal-era 1.21.10 data claimed long after the level was upgraded; vanilla's `DataFixTypes.PLAYER` pass on read is what upgrades it, and the next save rewrites it at the current version. This is now *tested*, not just argued: the gametest's quarantined saves carry DataVersion 4536 against a 4903 server, and the claimed player still arrives with the right position, XP and inventory. Every claim log line names the version it carried in.

**Deviations recorded** in the spec register as entries 53–56 (quarantine replaces "left behind"; the claim is invisible; the live-player guard and its accepted username-reuse consequence; a failed claim is an operator action item). Audit counts and the cutover checklist refreshed.

**Design notes.** `PlayerdataImport.live`/`.bucket` are reused unchanged — the claim adds no transform of its own, which is why the claimed save is keyed exactly as the importer would have keyed it. `OfflineUuid`'s KDoc said the hash "has no place in live gameplay"; that is now false and the claim's own KDoc says why. Only one existing type changed shape: `PersistenceService(root)` exposes `root`, so the quarantine's location is stated once rather than twice. `BackendSave` gained the uuid its file is named after, which it was re-deriving from the filename at the one place that needed it.

**Shared files:** `MCTraveler.kt` +1 registration line (fully qualified, after the persistence hook so `MCTraveler.persistence` is set when the claim is built), `mctraveler.mixins.json` +1 entry, gametest `fabric.mod.json` +1 entrypoint. No build-file changes.

**Limits, deliberately left.** A third World would need `PreparedClaim` to seed more than one bucket — the importer's own `PlayerSaves` has exactly the same single-`other` shape, and the spec ships two Worlds, so the two stay consistent rather than both being generalised speculatively. And no test boots a server whose *level* came from a migration that quarantined saves: the gametest builds the quarantine on the running server instead, for the same reason ticket 18 gave (a running server's chunk storage cannot be swapped underneath it).
