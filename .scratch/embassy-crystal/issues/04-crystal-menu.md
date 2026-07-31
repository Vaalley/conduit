# 04 — Crystal menu, destinations, teleport requests

**What to build:** The chest-GUI flow: right-click with a crystal opens the
27-slot "Where would you like to go?" menu (exact layout), the five destinations
(Bed / Spawn / Player / Embassy / Wilderness), the "Select a player" head GUI, the
teleport-request flow with the hidden `/teleportation-crystal-accept` command, the
region-protection exemptions for crystal use and mod-owned menus, and menu/request
lifecycle cleanup.

**Blocked by:** 03 (crystal item + energy), 01 (embassies dimension, for the Embassy
destination and its test).

**Status:** ready-for-agent

See ../spec.md (User Stories 26–36; Implementation Decisions "GUI", "Commands";
deviations 4, 8, 13, 16) and the Nucleus source `teleportation-crystal.kt`
(`menuActions`, `createTeleportationCrystalInventory`, `createPlayersInventory`,
`useTeleportationCrystal`, `TeleportationCrystalListener`,
`cleanUpTeleportationCrystal`) in the reference clone at
/Users/jam/Development/MCTravelerNucleus.

- [ ] Right-click air or block with a crystal opens the menu per stories 26–27,
      anywhere — including regions the player cannot modify (deviation 13); the
      vanilla use of the shard is cancelled
- [ ] Menu layout and item presentation exactly per story 26; all clicks cancelled;
      only slots 11–15 act (Nucleus's slot window quirk: slot 16 is accepted but maps
      to no action)
- [ ] Destination flow per stories 28–32 (energy cost, INFO message with aqua
      lowercase name, Bed/Spawn/Embassy coordinates and failures exact)
- [ ] Player flow per stories 33–34; request expiry after 6000 ticks (deviation 4);
      `/teleportation-crystal-accept` per story 35, absent from tab completion
      (deviation 8)
- [ ] Region container mixins skip mod-owned menus (deviation 16); notepad session
      interplay unaffected
- [ ] Lifecycle per story 36 (close/disconnect clear state; SERVER_STOPPING closes
      open crystal GUIs)
- [ ] Gametests: full menu open/click paths for every destination, both refusal
      gates, the request round-trip (send, accept, timeout, offline target), packet
      assertions for the GUIs; the Embassy destination lands at (0.5, 1.0, 0.5) and
      records an origin

## Comments
