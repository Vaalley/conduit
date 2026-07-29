# 12 — Region core: model, storage, create/rename/delete, admin queries

**What to build:** The Region service's foundation: the data model and legacy-format storage, corrected geometry, and the region lifecycle commands — `/rg` help, `/rg start`/`/rg end` creation with every Portal validation, `/rg rename`, `/rg delete`, plus the admin commands `/rg flag` (toggle + list), `/rg bounds`, and `/rg locate`.

**Blocked by:** 01 (Scaffold), 02 (Text DSL), 03 (Persistence store).

**Status:** done

See `../spec.md` (User Stories 28–32, 37; deviation register 2, 3, 5) and the RegionFeature section of `docs/research/portal-feature-inventory.md` — it lists every validation, message, and flag name exactly. Admin means vanilla operator status (vanilla /op /deop; check permission level) — there is no mod-side admin flag. Flag *enforcement* beyond storage is tickets 13–15.

- [x] Region model (title, corners, world, members, flags, sub-regions, parent) persists in the legacy JSON format, including the omit-default-y conventions, read/write compatible with migrated data
- [x] Geometry: recursive deepest-match containment; full-intersection overlap detection (fixes corner-only); new regions span y −64..320 (fixes 255/15)
- [x] `/rg end` enforces the Portal's validation sequence and messages: started-first, same World, area 10–5000 (admin override above), overlap refusal, sub-region rules including embassy and admin-flag refusals and parent membership
- [x] `/rg rename` (name regex, exact errors), `/rg delete` (embassy refusal, parent detach), `/rg` help panel — all with exact messages
- [x] `/rg flag <flag>` toggles with the Portal's valid-flag list, EMBASSY untoggleable, exact messages; `/rg flag` lists enabled/disabled in green/red; `/rg bounds` get/set with the y-range and 16-block-span validations; `/rg locate` substring search over titles and member names with the Portal's output formats — all admin-gated with the exact non-admin error
- [x] Unit tests: geometry (containment, overlap, sub-regions, bounds), storage round-trip; gametests: every command flow and error message

## Comments

Implemented in `eu.mctraveler.region` (branch `worktree-ticket-12-region-core`). `./gradlew build` green: unit tier (RegionServiceTest, RegionWorldsTest) + 42 headless gametests (RegionCommandGameTest, RegionAdminCommandGameTest).

**Public surface for tickets 13–15:**

- `RegionsFeature.service` / `requireService()` — the live `RegionService`, created at SERVER_STARTING over `<run dir>/regions.json` (the Portal's path).
- `RegionsFeature.isAdmin(player)` — **the admin check**: Admin = vanilla operator, implemented as `playerList.isOp(player.nameAndId())`, i.e. membership of the real ops list (ops are written at level 4; checking the list itself can never disagree with `ops.json`). Use this for all admin gating/bypass in 13–15.
- `RegionService`: `regionAt(world, x, y, z)` deepest recursive match; `firstIntersecting(world, minX, maxX, minZ, maxZ, excluding)` full-intersection overlap scan; `search(query, memberName)` locate's title+member substring search; `add(region, parent)` / `remove(region)` structural mutations (both save). Field mutations (title, flags, y bounds, members) mutate the `Region` then call `service.save()`.
- `Region`: `title`, legacy `world` string, corners (`startX/startZ/endX/endZ`, un-normalised), `startY` (top, default 320) / `endY` (bottom, default −64), `members: LinkedHashSet<UUID>`, `flags: LinkedHashSet<String>`, `subRegions`, `parent`, plus `contains`, `intersectsColumn`, `isResident`, min/max accessors.
- `RegionWorlds.legacyName(dimension)` — **the world-string mapper** (one place only): Primary = vanilla trio → `world`/`world_nether`/`world_the_end`; Secondary anticipated as `mctraveler:secondary`, `mctraveler:secondary_nether`, `mctraveler:secondary_the_end` → `last`/`last_nether`/`last_the_end`. If ticket 04 lands on different Secondary ids, adjust the map in `RegionWorlds` and nothing else. Also `isSecondaryWorld(world)` and `locateInfo(world)`.
- Gametest kit: `MessageCapturingPlayer` (join/standAt/runCommand/makeAdmin/leave) captures the exact components a player is sent — reuse it for 13–15 flows. `GameTestJanitor` deletes `regions.json` at gametest boot (Loom reuses the run dir; structures land at the same coordinates every run).

**Not in core (still owed by 13–15):** `/rg add`/`/rg remove` + 99-cap + member-name suggestions, scoreboard lifecycle (including live title update on rename and clearing on delete) — 13; current-region tracking (move/teleport/portal) and the `canModifyRegion` (resident-or-PUBLIC) protection predicate — 14; environmental enforcement and the five newly-real flags — 15. Flags here are stored/toggled/listed only.

**Deviations / interpretations recorded here (spec deviations 2, 3, 5 already cover the headline fixes):**

- `/rg start`'s "Position not available yet, please move first" error no longer exists: the Portal needed a move packet before it knew a position; the server-side position always exists, so the lazy capture (and its error) is gone.
- Overlap detection (deviation 3) is parent-aware: the prospective parent chain of a new sub-region is not an overlap (a sub-region always intersects its parent); every other intersecting region refuses creation — including full containment and no-corner crossings the Portal missed. Consequence: a sub-region may now exactly cover its parent's footprint (the Portal's corner test happened to refuse that one shape).
- The Portal's "same server" error ("Regions may only be created on the same server. Use /rg start again.") is kept in the validation sequence but is unreachable — world strings already encode the World, so the same-world check always fires first (this was true in the Portal too).
- Malformed known `/rg` invocations answer `USAGE /rg <sub> <args>` (deviation 5, styled per deviation 16). Arity is checked before admin gating, so e.g. bare `/rg locate` answers USAGE even for non-admins.
- Malformed `regions.json` throws at load (failing server start) instead of the Portal's log-and-continue-with-zero-regions, whose next save would have wiped the file. Matches this repo's persistence rule: never overwrite what could not be read.
- Sub-region creation requires parent residency with **no** admin bypass — exactly the Portal's code (admins bypass rename/delete membership checks, not this one).
- Storage is byte-compatible with the Portal's `JSON.stringify(…, null, 2)` output (key order with the optional y bounds after `members`, `flags`/`sub-regions` only when non-empty); a load→save cycle over a legacy fixture is byte-identical (unit-tested).
