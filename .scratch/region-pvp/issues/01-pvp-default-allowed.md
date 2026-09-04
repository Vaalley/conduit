# 01 — PVP allowed by default; DISABLE_PVP to turn it off

**What to build:** see `../spec.md`.

**Status:** done

- [x] `DISABLE_PVP` added to `RegionCommands.VALID_FLAGS`.
- [x] `RegionProtection.allowsPvp` — new default (allowed), `DISABLE_PVP`
      protects everyone including residents.
- [x] Wired into both `allowsEntityAttack` (the melee click) and
      `allowsEntityDamage` (the actual damage — covers ranged/indirect PVP
      too), each special-casing a `ServerPlayer` target ahead of the
      existing animal/mob-shaped `entityProtectionAround` path.
- [x] `aNonMemberCannotAttackAnimalsOrPlayers` (`RegionProtectionGameTest`)
      split: the animal half kept as `aNonMemberCannotAttackAnimals`; the
      player half replaced by `pvpIsAllowedInARegionByDefault` and
      `disablePvpFlagProtectsEveryPlayerInTheRegion` (which also checks a
      resident is not exempt).
- [x] `RegionAdminCommandGameTest`'s two flag-list-literal tests updated to
      include `DISABLE_PVP`.

## Comments

Verified: compile, `:bootstrap:runGameTest` (only the 6 known mcaselector
`world_merge_*` failures), `:runtime:test`, prod smoke.
