# 04 — Mob interaction rules

**What to build:** A non-member may cull unnamed hostile mobs from a region but
must leave everything else alone, with horses still rideable and chested donkeys
view-only. See `../spec.md` "Mobs".

**Blocked by:** nothing (touches `RegionProtection` entity hooks only).

**Status:** done

- [x] `allowsEntityAttack` / `allowsEntityDamage`: a non-member may attack an
      entity in a region iff `isCullableHostile` — `Enemy`, not an armor stand,
      and `!hasCustomName()`. Named hostiles and every non-`Enemy` entity stay
      refused. Item frames / armor stands unchanged.
- [x] `allowsEntityInteract`: a non-member may mount a rideable equine
      (`AbstractHorse`) with an empty hand and no crouch. A chested
      `AbstractChestedHorse` refuses everything except a crouch + empty hand,
      which opens the inventory for viewing (`rememberContainerBlock` on the
      entity's region so the session is captured even reaching from outside, and
      `RegionContainerClickMixin` refuses the slot clicks). Other mobs keep the
      old rule.
- [x] `DISABLE_ANIMAL_PROTECTION` still bypasses everything (unchanged
      `entityProtectionAround`).
- [x] Gametests: `aNonMemberMayCullAnUnnamedHostileButNotANamedOne`,
      `aNonMemberMayRideAHorseButNotAttackIt`,
      `aChestedDonkeyIsNotRiddenByANonMember`; the old
      `aNonMemberCannotAttackAnimalsHostilesOrPlayers` is narrowed to
      `…AnimalsOrPlayers` (the hostile is now cullable).

## Comments

Runtime-only, hot-swappable.

Gap for the ticket 06 sweep: the chested-donkey **crouch-view** path is only
asserted as "not refused" indirectly — a gametest that opens the horse
inventory menu and checks a slot click is refused was skipped (giving a test
donkey a working chest inventory is fiddly). The ride refusal — the protective
half — is pinned.

Compile green; region unit tests green; `:bootstrap:runGameTest` 359 tests, the
new entity tests pass, only the 6 world-merge gametests fail (mcaselector jar
absent here).
