# /vanish (issue #47)

An admin (`RegionsFeature.isAdmin` — a vanilla operator) runs `/vanish` and drops
off the server as far as everyone without operator status is concerned, while
they float around in Spectator watching. `/vanish` again brings them back as if
they had just reconnected.

## To a non-admin

- The Portal leave line goes out (`[-] <name> left.`) — exactly what a real
  disconnect produces.
- The tab-list entry disappears (`ClientboundPlayerInfoRemovePacket`, and
  `SpectatorVisibility.maskFor` strips the entry from every later
  `ClientboundPlayerInfoUpdatePacket`).
- The body disappears and stays gone: `VanishTrackingMixin` on
  `ChunkMap$TrackedEntity.updatePlayer` drops the non-admin viewer every tick,
  so the entity is never (re-)sent.
- The server-list count and sample stop including them (`Motd`), and the
  `/status` HTTP endpoint the Observer bot polls stops listing/counting them
  (`HttpApi`).
- Lodeway: the map plugin only renders regions and reads live positions from
  vanilla tracking, which spectator + the tracking mixin already suppress — no
  eu.mctraveler code publishes player positions, so there is nothing extra to do.
- Unvanish sends a fake join line (`[+] <name> joined from <Country>`, country
  and all) and re-adds the tab entry; the body re-tracks on the next tick.

## To another admin

Never fooled: they keep the tab entry and the body, and instead of the fake
leave/join they get `[V] <name> went into vanish mode` / `left vanish mode`.

## State

Per session, in `VanishFeature.vanished: Map<UUID, GameType>` (the value is the
game mode to restore). A disconnect clears it — non-admins already saw the
player "leave", so `ChatFeature`'s DISCONNECT handler sends the real leave line
to admins only. A fresh login comes back visible.

## Not covered

- No persistence across a restart (SERVER_STOPPED clears the map).
- `/vanish` is admin-only (`RegionsFeature.adminGate`).
