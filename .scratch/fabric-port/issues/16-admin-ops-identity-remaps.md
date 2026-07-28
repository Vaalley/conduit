# 16 — Identity remaps

**What to build:** The two aliased identities: DemonicNoodle and AlsoJames log in as travelcraft2012 and iElmo (name and UUID both), with all game and mod state keyed to the remapped identity.

Scope note: this ticket originally also carried custom `/op` `/deop` commands and a mod-side admin flag — that was cut. Admin status is vanilla operator status, managed by vanilla `/op` `/deop`; admin-gated features check vanilla permission level.

**Blocked by:** 01 (Scaffold).

**Status:** ready-for-agent

See `../spec.md` (User Story 42; deviation register 8) and the TravelPatchFeature section of `docs/research/portal-feature-inventory.md` for the remap constants.

- [ ] The two GameProfile remaps apply at login (case-sensitive, name and UUID both), logged with the Portal's remap log line
- [ ] All state (playerdata, regions, tab list, name cache) keys to the remapped identity
- [ ] Gametests: remapped login identity for both entries; unaffected names pass through untouched
