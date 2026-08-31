# 03 — BOAT and MINECART flags

**What to build:** Two opt-in flags that protect boats and minecarts from
non-members. Default (flag off): a non-member may place, ride and break them
freely. See `../spec.md` "Vehicles".

**Blocked by:** 01 (region-modifying-item list), 04 (entity attack/interact
rework — or land the entity hooks here if 04 slips).

**Status:** blocked

- [ ] `BOAT` and `MINECART` added to `RegionCommands.VALID_FLAGS` in canonical
      order; admin-toggled like every other flag; accepted-but-inert until this
      ticket wires them.
- [ ] Flag off → `BoatItem` / `MinecartItem` placement, entity attack (break)
      and ride interaction all allowed for non-members in the region.
- [ ] Flag on → all three refused for non-members: placement joins ticket 01's
      region-modifying-item check (gated on the flag at the target region),
      `allowsEntityAttack` refuses the vehicle, `allowsEntityInteract` refuses
      riding.
- [ ] Chest boat / chest minecart: the container GUI is view-only for a
      non-member (open allowed, `RegionContainerClickMixin` refuses clicks) with
      or without the flag; ride/place/break follow the flag.
- [ ] Hopper / furnace / TNT minecart follow `MINECART` for ride/place/break;
      hopper minecart container is view-only.
- [ ] `CONTEXT.md` gets a short glossary entry for each flag; the
      `docs/research/portal-feature-inventory.md` flag list note is extended.
- [ ] Gametests: default-open place/ride/break; flag-on refusals; chest-vehicle
      view-only under both.

## Comments
