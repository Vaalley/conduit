# 04a — Placed decoration is indestructible in a region

**What to build:** An item frame, a painting, an armor stand inside a region
cannot be broken by anything but a member removing it — a creeper blast, a
skeleton's arrow, a stray snowball, a stranger's punch are all ignored.

**Blocked by:** 04.

**Status:** done

- [x] `RegionProtection.allowsEntityDamage` short-circuits for
      `isRegionDecoration` (`BlockAttachedEntity` — item frame, glow frame,
      painting, leash knot — or `ArmorStand`): the hit is allowed only when a
      player is responsible **and** that player can modify the region. No flag,
      no message, no `ENABLE_EXPLOSIONS` exception.
- [x] `allowsEntityInteract` refuses the same set for a non-member (rotating a
      frame, taking an armor stand's gear).
- [x] Members are unaffected — a member breaks their own frame through
      `allowsEntityAttack` (which this does not gate).
- [x] Gametests: `nothingButAMemberBreaksAnItemFrameInARegion` (an explosion
      and a mob-attack damage source, no member — frame and its item untouched),
      `aMemberBreaksTheirOwnItemFrame`.

## Notes

- Item frame damage never reaches `hurtOrSimulate` (the seam
  `RegionEntityDamageMixin` guards): `ServerExplosion.hurtEntities` calls
  `Entity.hurtServer` directly, and `BlockAttachedEntity.hurtServer` kills and
  drops in place — and `ItemFrame` has its *own* `hurtServer` that pops the
  framed item out first. New `RegionDecorationDamageMixin` on
  `@Mixin({BlockAttachedEntity, ItemFrame})` — HEAD of `hurtServer` on both —
  routes the decision through `Hooks.allowsEntityDamage`.
- Armor stands are `LivingEntity`, so their explosion damage already reaches
  `allowsEntityDamage` through `ServerLivingEntityEvents.ALLOW_DAMAGE`; only the
  logic change was needed there.
- `:bootstrap` change (new mixin) — needs a restart.

## Comments

Compile green; region unit tests green; `:bootstrap:runGameTest` 361 tests, the
two new frame tests pass, only the 6 world-merge gametests fail (mcaselector jar
absent here); prod smoke green.
