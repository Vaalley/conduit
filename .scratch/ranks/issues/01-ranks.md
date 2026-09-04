# 01 — Ranks: Newbie, Traveler, Donator

**What to build:** see `../spec.md`.

**Status:** done

- [x] `Rank` enum (label, chat color, region-area cap) and `PlayerStore`
      `rank`/`setRank`/`hasRecord`.
- [x] `RankFeature`: first-join welcome + Newbie assignment, existing-record
      grandfathering to Traveler, hour-of-playtime promotion + click-to-run
      message, `nameColor` (crash-safe) feeding chat/tab-list.
- [x] `RegionCommands`: Newbie blocked from `/rg start`; the area cap in
      `start`/`end`/`extend` is now per-rank instead of the flat 5000.
- [x] `RankCommands` — `/rank set <player> <rank>`, admin-only, tab-completes
      the rank, resolves an offline player by name.
- [x] `Markdown` (`%` → real formatting) + `DonatorSignMarkdownMixin`.
- [x] Gametests: `RankGameTest` (welcome/grandfathering/region
      gating/promotion/`/rank set`/sign markdown).
- [x] Custom death message (`/deathmessage`, `CustomDeathMessageMixin`) was
      built, then removed at the user's request — see `../spec.md`'s "Not
      covered".

## Notes

- `:bootstrap` change (one new mixin + one new `Hooks` method) — needs a
  full restart.
- Region-test and chat-test player factories (`MessageCapturingPlayer.join`,
  `TestPlayer.join`/`joinAs`) now default fresh test players to Traveler —
  a brand-new account is a Newbie by design, and those suites are about
  region/chat behaviour, not the rank ladder. `RankGameTest`'s own
  first-join test needs a genuinely un-ranked player, so it uses
  `TestPlayer.join` directly (not through a factory that would pre-rank it)
  and pins the *other* party to Traveler explicitly.
- `helper.succeedWhen`/`runAfterDelay` blocks must throw
  `GameTestAssertException` (`helper.assertTrue`/`assertValueEqual`/
  `assertionException`), not a bare `check()` — vanilla's `GameTestSequence`
  only retries on that type; anything else propagates as an uncaught
  exception and crashes the whole gametest server. Found the hard way: a
  bare `check()` inside `succeedWhen` crashed the entire batch the first
  time it genuinely needed a retry (a condition not yet true on tick 1).

## Comments

Verified: compile, `:bootstrap:runGameTest` (only the 6 known mcaselector
`world_merge_*` failures), `:runtime:test` (motd/http/chat/persistence),
prod smoke.
