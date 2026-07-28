# 15 — Region environment protection + the five real flags

**What to build:** Regions defended against the world itself (new in the port): explosions, fire, piston reach-in, and mob griefing can't damage region blocks — and the five formerly inert flags become functional toggles.

**Blocked by:** 14 (Player protection).

**Status:** ready-for-agent

See `../spec.md` (User Stories 35–36; deviation register 7). Flag semantics are the spec's proposal from names + pre-proxy convention; if community lore disagrees at implementation time, adjust and note it in the deviation register.

- [ ] Explosions of any source do not destroy blocks inside a region unless it has ENABLE_EXPLOSIONS
- [ ] Fire does not spread into or burn region blocks unless ENABLE_FIRE_DAMAGE
- [ ] Pistons outside a region cannot push into or pull blocks out of it; mob griefing (e.g. endermen) cannot alter region blocks
- [ ] DISABLE_PLAYER_FALL_DAMAGE negates fall damage inside; DISABLE_PUBLIC_REDSTONE_TRIGGERS stops non-members using buttons/levers/pressure plates; DISABLE_GATES stops non-members using doors/gates/trapdoors
- [ ] Gametests: each hazard with and without its flag; each flag toggled live via `/rg flag`
