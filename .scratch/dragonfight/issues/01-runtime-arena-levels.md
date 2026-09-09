# 01 — Runtime arena levels

**What to build:** create, unload and delete an End-typed `ServerLevel` at
runtime, with its world border and dragon fight, plus the two portal mixins.

**Blocked by:** nothing.

**Status:** ready-for-agent

- [ ] `MinecraftServerAccessor` mixin (`levels` map, `executor`)
- [ ] `ArenaLevels.create(server, owner)` / `ArenaLevels.delete(server, level)` /
      startup folder sweep
- [ ] `EndPortalBlock.getPortalDestination` and `EndGatewayBlock.getPortalDestination`
      HEAD injections → `Hooks.arenaExitPortal` / `Hooks.arenaGatewayBlocked`
- [ ] Gametest: an arena level exists in `server.levelKeys()`, has a non-null
      `getDragonFight()`, border size `2 * borderRadius`; after delete the key is
      gone and the folder is gone
