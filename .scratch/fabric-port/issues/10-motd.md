# 10 — MOTD

**What to build:** The server-list presence: the Portal's exact two-line MOTD (bolded MCTraveler in the play-address line, the "Celebrating 13 years of vanilla survival" line, exact colors and spacing), live player count and up-to-12-player sample, and a real server icon supported via the standard mechanism.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** done

See `../spec.md` (User Story 1) and the MotdFeature section of `docs/research/portal-feature-inventory.md` for exact text.

- [x] Status response carries both MOTD lines with exact text, colors, and bolding
- [x] Player count and sample (first 12 names) reflect live state
- [x] Standard server-icon file is served when present (the Portal's favicon was a broken placeholder — intent parity)
- [x] Test asserts the status payload content

## Comments

Implemented as `eu.mctraveler.motd.Motd` (Kotlin — all the feature logic, including the roster policy) plus the repo's first mixin, `eu.mctraveler.mixin.MinecraftServerMixin` (Java per ADR 0002): a one-line RETURN-inject shim on `MinecraftServer.buildServerStatus` — the one method vanilla uses to build the status/server-list response (at startup and on its ~5 s refresh) — handing the vanilla-built status and the server to `Motd.decorate`. Mixin infrastructure added: `mctraveler.mixins.json` + the `"mixins"` entry in `fabric.mod.json`.

`decorate` replaces exactly two things and passes everything else through untouched:

- **Description**: the Portal's two lines built with Paint — green `                  play.MCTraveler.eu` (18-space lead, `MCTraveler` bold), newline, gray `       Celebrating 13 years of vanilla survival` (7-space lead).
- **Sample**: the first 12 online players (name + uuid, join order), replacing vanilla's random shuffled window — the Portal's `slice(0, 12)` intent.

Pass-throughs (all vanilla's own, per Intent Parity):

- **max-players**: the real configured value, not the Portal's hardcoded 20. Operator note: set `max-players=20` in prod `server.properties` to advertise the same capacity as today.
- **Favicon**: the standard `server-icon.png` mechanism, preserved untouched — a real icon now works (the Portal's was a broken placeholder string).
- **enforcesSecureChat**: vanilla's `enforceSecureProfile()` value, untouched. Chat stays signed (spec deviation 13), so the default (`enforce-secure-profile=true`, online mode) already advertises honestly — nothing to override.
- **Version/protocol**: vanilla's real version replaces the Portal's `MCTraveler Proxy`/protocol-773 fiction (proxy-era necessity; a single real server advertises itself).

New intent-parity notes (recorded here per worktree rules, not in spec.md):

- The sample honors vanilla's per-player **allow-server-listings** opt-out (an opted-out player appears as the anonymous entry) and `hide-online-players` (empty sample). The Portal's hand-rolled status ignored both; honoring the player's own privacy setting is the honest reading, consistent with deviation 13's spirit.
- Version/protocol honesty above is likewise a deliberate departure from the Portal's literal payload.

Tests: unit tier `MotdTest` (fabric-loader-junit, no bootstrap needed) pins the exact description component (string + flat-list styles), first-12 truncation with name+uuid, sub-12 rosters, count/max pass-through, and version/favicon/secure-chat pass-through. Gametests `MotdGameTest` assert the running server's actual status payload: exact MOTD string and real max-players at boot, then live count + anonymized sample after a mock player joins (waiting out the ~5 s status-refresh cadence; the mock is removed on success). The first-12-vs-random distinction is only observable in the unit tier — a gametest can field one mock player, and vanilla's sample of one equals ours.
