# 14 — Region player-action protection

**What to build:** Regions actually protect against players: non-members can't dig, place, edit signs, use containers, use items, or harm entities in a region, each refusal carrying the Portal's exact error — with the Portal's nuanced entity and container rules, and region tracking that now also catches teleport and portal arrivals.

**Blocked by:** 12 (Region core), 13 (Membership + scoreboard).

**Status:** ready-for-agent

See `../spec.md` (User Stories 34, 38–39; deviation register 9) and the RegionFeature protection subsection of `docs/research/portal-feature-inventory.md` for exact rules. Enforcement is server-side event cancellation (no fake gamemode). Admins bypass management, never protection.

- [ ] Dig, place, and sign-edit attempts by non-members are cancelled at the target block's region with `This area is protected by <name>`
- [ ] Container rule uses the region captured at container-open time; PUBLIC and ENABLE_PUBLIC_CONTAINERS open access
- [ ] Item use blocked in a non-modifiable region; entity rules: attack always blocked, held-item interaction blocked unless ENABLE_PUBLIC_VILLAGER_TRADING, empty-hand interaction allowed, DISABLE_ANIMAL_PROTECTION bypasses
- [ ] Current-region tracking updates on movement, teleports, portals, and Travel; membership changes re-evaluate protection immediately
- [ ] Gametests: each action type allowed/refused across member, non-member, PUBLIC, and admin cases, including teleport-entry
