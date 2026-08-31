# 04 — Mob interaction rules

**What to build:** A non-member may cull unnamed hostile mobs from a region but
must leave everything else alone, with horses still rideable and chested donkeys
view-only. See `../spec.md` "Mobs".

**Blocked by:** nothing (touches `RegionProtection` entity hooks only).

**Status:** unblocked

- [ ] `allowsEntityAttack`: a non-member may attack an entity in a region iff it
      is `Enemy` and `customName == null`. Named hostiles and every non-`Enemy`
      entity stay refused. Item frames / armor stands unchanged (already refused
      via their own branch).
- [ ] `allowsEntityInteract`: a non-member riding a rideable equine
      (`AbstractHorse` without a chest) with an empty hand → allowed. A chested
      donkey/mule → crouch-right-click opens the inventory GUI (view-only,
      `RegionContainerClickMixin` refuses clicks); riding and non-crouch
      interaction refused. Other passive mobs: empty-hand interaction that does
      nothing stays allowed; held-item / breeding / leashing refused (existing
      `ENABLE_PUBLIC_VILLAGER_TRADING` still opens held-item interaction).
- [ ] `DISABLE_ANIMAL_PROTECTION` still bypasses the passive-mob rules.
- [ ] Gametests: kill an unnamed zombie / skeleton in a foreign region; be
      refused on a named zombie, a cow, a villager attack; ride a horse; be
      refused riding a chested donkey but open its inventory to view; a resident
      is unaffected.

## Comments
