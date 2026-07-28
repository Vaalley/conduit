# 09 — Tab list

**What to build:** The unified tab list: every online player visible regardless of World, the Portal's exact header and footer (with the footer's TPS now the server's real TPS), and every entry's display name carrying latency as `name [Nms]`.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** ready-for-agent

See `../spec.md` (User Stories 6–8; deviation register 4) and the TabListFeature/TabListModule sections of `docs/research/portal-feature-inventory.md` for exact header/footer text and the display-name behaviour.

- [ ] Header: centered green "MCTraveler" line; footer: play address line plus `TPS: <value>` with real server TPS to 0.1 precision, styled exactly as today
- [ ] Tab entries show `<name> [<N>ms]` and refresh as latency updates
- [ ] Players in different Worlds appear in one list
- [ ] Gametests: header/footer content, display-name format, cross-World listing
