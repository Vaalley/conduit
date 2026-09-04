# PVP allowed in regions by default; `DISABLE_PVP` to turn it off

Previously a region's ordinary entity protection covered *any* entity
standing inside it, players included — a non-member could not damage
another player standing in someone else's region at all. Per request, PVP
is now allowed by default; a new flag, `DISABLE_PVP`, turns a region into a
safe zone.

## Rule

`RegionProtection.allowsPvp(attacker, victim)`:

- Self-damage (`attacker === victim`, e.g. a self-inflicted potion) is
  never gated by this — it was never "PVP".
- No region at the victim's position → allowed (nothing changed here).
- `DISABLE_PVP` not set → allowed (the new default).
- `DISABLE_PVP` set → refused for **everyone**, including a resident of
  that very region. The flag reads as "no fighting here," a safe zone —
  not a members-only exemption the way most other region behaviour is
  (`canModifyRegion`'s member bypass is deliberately not consulted here).

Two call sites both had to change, since the mod gates entity combat at two
separate seams:

- `RegionProtection.allowsEntityAttack` — the melee click itself
  (`AttackEntityCallback`), which refuses before any damage is even
  computed.
- `RegionProtection.allowsEntityDamage` — the actual damage application
  (`ServerLivingEntityEvents.ALLOW_DAMAGE`), which also covers ranged/
  indirect PVP (arrows, thrown potions, etc., where `DamageSource.entity`
  resolves to the attacking player).

Both now special-case `entity is ServerPlayer` before falling into the
general (still-animal/mob-shaped) `entityProtectionAround` path, and both
route through the same `allowsPvp`.

## Not verifiable in gametests

Whether a melee hit actually reduces the victim's health depends on
vanilla's own PVP mechanics (attack cooldown, and possibly the dedicated
server's own `pvp` setting) — outside anything this mod controls, and not
something the gametest harness's mock connections reliably exercise
either. The gametests instead assert the refusal itself (present or
absent), which is the actual seam this mod owns.
