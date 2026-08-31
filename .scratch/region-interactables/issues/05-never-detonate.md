# 05 — Never detonate, never teleport

**What to build:** Inside a region, an end crystal, a respawn anchor and a TNT
minecart cannot be made to explode by anyone or anything, and a dragon egg
cannot be made to teleport. See `../spec.md` "Never-detonate, never-teleport".

**Blocked by:** 01 (dragon egg / respawn anchor are `REQUIRES_MEMBERSHIP` for
the non-member right-click; this ticket covers the rest).

**Status:** blocked

- [ ] `RegionEnvironment` gains a predicate along the lines of
      `allowsSelfDetonation(level, pos|entity)` — false inside any region.
- [ ] End crystal: `:bootstrap` mixin so `EndCrystal` never runs its explosion
      while it stands in a region — player hit (already refused), non-player
      projectile, and blast-chain all suppressed. The crystal may still be
      removed by a member breaking it.
- [ ] Respawn anchor: `:bootstrap` mixin so `RespawnAnchorBlock.explode` is a
      no-op inside a region (covers a member's over-charge / overworld use).
- [ ] TNT minecart: mixin so `MinecartTNT` never primes or explodes inside a
      region; combined with the `MINECART` flag (ticket 03) its item cannot be
      moved by a non-member.
- [ ] Dragon egg: mixin (or the ticket-01 cancel plus a guard) so
      `DragonEggBlock.teleport` never runs inside a region.
- [ ] Gametests: shoot an arrow at an end crystal in a region — no explosion;
      charge a respawn anchor past safe in a region — no explosion; light a TNT
      minecart in a region — nothing; right-click a dragon egg — it stays.

## Comments
