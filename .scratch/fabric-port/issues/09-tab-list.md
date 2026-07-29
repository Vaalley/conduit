# 09 — Tab list

**What to build:** The unified tab list: every online player visible regardless of World, the Portal's exact header and footer (with the footer's TPS now the server's real TPS), and every entry's display name carrying latency as `name [Nms]`.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** done

See `../spec.md` (User Stories 6–8; deviation register 4) and the TabListFeature/TabListModule sections of `docs/research/portal-feature-inventory.md` for exact header/footer text and the display-name behaviour.

- [x] Header: centered green "MCTraveler" line; footer: play address line plus `TPS: <value>` with real server TPS to 0.1 precision, styled exactly as today
- [x] Tab entries show `<name> [<N>ms]` and refresh as latency updates
- [x] Players in different Worlds appear in one list
- [x] Gametests: header/footer content, display-name format, cross-World listing

## Comments

- Implemented as `eu.mctraveler.tablist.TabListFeature` (plain Kotlin module per ADR 0002) plus one Java mixin, `ServerPlayerMixin`, overriding `ServerPlayer.getTabListDisplayName` — the method vanilla reads when building every player-info packet, so the `<green name> <darkGray [Nms]>` display name rides the initial ADD_PLAYER broadcast as well as refreshes. Header/footer are sent on join and rebroadcast every 20 ticks together with an `UPDATE_DISPLAY_NAME` refresh, keeping the footer's TPS and the bracketed latency live.
- Real TPS (deviation 4) derives from `MinecraftServer.getAverageTickTimeNanos()`: `min(20, 10^9 / avgTickNanos)`, rendered to one decimal (`Locale.ROOT`); zero samples reads as 20.0. Pure math unit-tested in `TabListFeatureTest`; everything player-visible asserted in `TabListGameTest` by draining the mock players' embedded-channel outbound queues (`PacketCapture`) — the literal packets a client would receive.
- The display-name refresh is an unconditional periodic broadcast rather than change-driven (the Portal reacted to backend latency-update packets, a mechanism §2.15 declares moot); player-visible result is the same — the bracket tracks `connection.latency()` within a second.
- The cross-World gametest uses the vanilla nether as the second World stand-in: at this branch's fork point the Secondary trio (ticket 04) doesn't exist yet. It verifies the actual guarantee (players in another dimension stay listed and keep receiving refreshes in one unified list); worth re-exercising against real Worlds in the parity audit (ticket 19).
- No new intent-parity deviations beyond the register's existing entry 4 (real TPS).
