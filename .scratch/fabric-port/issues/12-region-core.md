# 12 — Region core: model, storage, create/rename/delete, admin queries

**What to build:** The Region service's foundation: the data model and legacy-format storage, corrected geometry, and the region lifecycle commands — `/rg` help, `/rg start`/`/rg end` creation with every Portal validation, `/rg rename`, `/rg delete`, plus the admin commands `/rg flag` (toggle + list), `/rg bounds`, and `/rg locate`.

**Blocked by:** 01 (Scaffold), 02 (Text DSL), 03 (Persistence store).

**Status:** ready-for-agent

See `../spec.md` (User Stories 28–32, 37; deviation register 2, 3, 5) and the RegionFeature section of `docs/research/portal-feature-inventory.md` — it lists every validation, message, and flag name exactly. Admin flag comes from the store (ticket 03). Flag *enforcement* beyond storage is tickets 13–15.

- [ ] Region model (title, corners, world, members, flags, sub-regions, parent) persists in the legacy JSON format, including the omit-default-y conventions, read/write compatible with migrated data
- [ ] Geometry: recursive deepest-match containment; full-intersection overlap detection (fixes corner-only); new regions span y −64..320 (fixes 255/15)
- [ ] `/rg end` enforces the Portal's validation sequence and messages: started-first, same World, area 10–5000 (admin override above), overlap refusal, sub-region rules including embassy and admin-flag refusals and parent membership
- [ ] `/rg rename` (name regex, exact errors), `/rg delete` (embassy refusal, parent detach), `/rg` help panel — all with exact messages
- [ ] `/rg flag <flag>` toggles with the Portal's valid-flag list, EMBASSY untoggleable, exact messages; `/rg flag` lists enabled/disabled in green/red; `/rg bounds` get/set with the y-range and 16-block-span validations; `/rg locate` substring search over titles and member names with the Portal's output formats — all admin-gated with the exact non-admin error
- [ ] Unit tests: geometry (containment, overlap, sub-regions, bounds), storage round-trip; gametests: every command flow and error message
