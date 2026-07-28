# 16 — Admin /op /deop + identity remaps

**What to build:** Admin management in lockstep with real server operators, and the two aliased identities: `/op` and `/deop` set both the mod's admin flag and vanilla operator status with the Portal's exact messages and gating (no username backdoor), while DemonicNoodle and AlsoJames still log in as travelcraft2012 and iElmo.

**Blocked by:** 01 (Scaffold), 02 (Text DSL), 03 (Persistence store).

**Status:** ready-for-agent

See `../spec.md` (User Stories 41–42; deviation register 8) and the AdminFeature/TravelPatchFeature sections of `docs/research/portal-feature-inventory.md` for exact messages, the remap constants, and the removed backdoor.

- [ ] `/op <player>` (admin-only) sets the admin flag, adds a level-4 operator entry, updates the name cache, and sends the exact success messages to both parties; the non-admin error is exact
- [ ] `/deop <player>` (admin-only) reverses both, with the exact messages (target's is yellow, not SUCCESS-prefixed)
- [ ] No username grants /op ability — only the persisted admin flag
- [ ] The two GameProfile remaps apply at login (case-sensitive, name and UUID both), logged, with all state keyed to the remapped identity
- [ ] Gametests: gating, dual-effect of op/deop, message texts, remapped login identity
