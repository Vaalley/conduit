# 01 — /vanish

**What to build:** see `../spec.md`.

**Status:** done

- [x] `VanishFeature` (`runtime/.../vanish/VanishFeature.kt`): `/vanish` command
      (admin-gated), `enter`/`leave`, per-session `vanished` map, DISCONNECT +
      SERVER_STOPPED cleanup. Registered in `MCTraveler` after `ChatFeature`.
- [x] `enter`: store game mode, go Spectator, send remove-packets to non-admins,
      fake leave line to non-admins / `[V] ... went into vanish mode` to admins.
- [x] `leave`: restore game mode, re-add tab entry to non-admins, fake join line
      / `[V] ... left vanish mode`.
- [x] `ChatFeature`: `leaveLine` internal, `joinLineFor(player)` added; DISCONNECT
      sends the real leave line to admins only when the player was vanished.
- [x] `SpectatorVisibility.maskFor`: drops vanished players' entries from the
      per-connection `ClientboundPlayerInfoUpdatePacket` for non-admin viewers.
- [x] `Motd`: `roster()` and the advertised count skip vanished players.
- [x] `HttpApi.handleStatus`: count and names skip vanished players.
- [x] `VanishTrackingMixin` (`ChunkMap$TrackedEntity.updatePlayer` HEAD,
      `require = 0`) → `Hooks.isVanishedFromViewer` → `VanishFeature.isHiddenFrom`:
      a non-admin viewer is dropped from a vanished admin's tracker each tick.
- [x] Gametests (`VanishGameTest`): fake leave + admin status line, fake join on
      unvanish, excluded from the server-list count/sample.

## Notes

- `:bootstrap` change (new mixin + new `Hooks` method) — needs a full restart.
- Lodeway needs nothing extra: it publishes only regions; player dots come from
  vanilla entity tracking, which the tracking mixin + spectator already gate.
