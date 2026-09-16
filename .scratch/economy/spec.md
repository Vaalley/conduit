# Spec: Economy — how money enters and leaves player balances

Status: needs-info (living plan; do not implement until Valley says so)

Builds on the per-player balance introduced with item frame stores (GitHub issue
`https://github.com/Vaalley/conduit/issues/36`, PR #78): `Economy.deposit/withdraw`
over `PlayerStore.balance`, `/balance`, `/pay`, admin `/balance set|give`, and
`/store create|delete`. Those pieces move money between players; this spec is
about the *faucets* (how money is created) and *sinks* (how it is destroyed) that
give the currency value.

## Problem statement

Stores and `/pay` only redistribute money. Without a faucet nobody has anything
to spend on day one; without sinks money accumulates forever and prices drift.
Faucets must be predictable and un-farmable (no AFK/auto-farm exploits); sinks
should be optional conveniences, never gates on core gameplay.

## Decisions so far

- **Currency**: whole-unit integer balance, formatted `$40`. Persisted in the
  legacy `balance` field of the player JSON. (settled in #36)
- **Store payments** go straight into the owner's balance, online or offline.
  (settled in #36)
- **Passport stamp payouts** — AGREED. Every stamp a player earns pays a one-off
  bounty into their balance. Stamps are already idempotent achievements
  (`Stamps.grant` returns null when already held), so this cannot be farmed.
  Details in "Faucets" below; tier amounts are still open.

## Faucets

### 1. Passport stamp bounties (agreed)

- Hook point: `PassportFeature.announceStamps` / `grantStamp` — every path that
  grants a stamp already funnels through `Stamps.grant(...)` and then announces,
  so the deposit belongs next to the announcement.
- Payout per stamp, keyed by a tier on the `Stamp` definition (new field, e.g.
  `bounty: Long`, or a tier enum → amount table). Proposed tiers (OPEN — tune):

  | Tier   | Amount | Examples                                                          |
  |--------|--------|-------------------------------------------------------------------|
  | Common | $50    | first_steps, wormhole, first_blood, postcard_1, biome_10, digger  |
  | Uncommon | $150 | wanderer, pony_express, channel_swimmer, sightseer, crystal_5, excavator, biome_30, postcard_10, gone_fishing, cake, sneaky |
  | Rare   | $400   | nomad, marathon, three_worlds, trespasser, diplomat, crystal_50, roulette_regular, monster_hunter, sleepyhead, insomniac, big_spender, leg_day, event-only stamps (ashes_to_ashes, into_the_void, terminal_velocity, storm_chaser) |
  | Epic   | $1000  | globetrotter, biome_all, ambassador, mountain_mover, veteran      |

- Chat: extend the existing stamp announcement with the payout, e.g.
  `PASSPORT New stamp: Wanderer  (+$150)`, or a second `BALANCE` line. (OPEN)
- Retroactive grant for stamps players already hold before this ships? Options:
  (a) none, (b) one-off migration that pays out all held stamps on next login.
  Leaning (b) so early players are not penalised. (OPEN)
- Total available from all ~40 stamps at the proposed tiers ≈ $13k — a ceiling
  on "free" money per player, which is the point.

### 2. Play-time stipend (idea, not agreed)

- e.g. $10 per 15 min of *active* play; pause while `AwayFeature` marks the player
  away so AFK doesn't pay. Reuse the tick clock the crystal Energy regen uses.
- Pro: fair, predictable, gives day-one spending money. Con: steady inflation
  source; needs a cap or diminishing returns per day to stay tame.

### 3. Welcome bonus (idea)

- $100 on first join (rank Newbie). Trivial to implement via `firstJoin`.

### 4. Admin / event grants (exists)

- `/balance give` already covers contests, compensation, event prizes.

### 5. Server buy-back store (idea, cautious)

- Admin-owned frame(s) at spawn that *buy* bulk items at fixed low prices —
  a price floor. Fastest inflation source; only if trade alone feels dead.

### Rejected

- **Per-block / per-mob-kill payouts** — rewards AFK and auto farms; inflates
  hardest. Stamps already reward these milestones once.

## Sinks (ideas, none agreed)

- `/rtp` skip-cooldown fee.
- Extra crystal Energy refill.
- `/region extend` beyond the free 5000-block quota (paid quota bump instead of
  asking an admin).
- Store-creation fee (e.g. $50) to discourage frame spam.
- Cosmetics: sign colours for non-donators, `/cat` variants, tablist flair.
- Postcard sending fee (small, thematic).

## Open questions

- Final tier amounts and stamp → tier assignment (table above is a first draft).
- Retroactive payout for already-held stamps: (a) none vs (b) pay on next login.
- Where the payout shows in chat (inline with the stamp line vs separate).
- Whether any sink ships together with stamp bounties, or later.
- Should `/balance top` (leaderboard) exist?

## Out of scope for now

- Shops with server-set prices, auctions, taxes, interest.
- Multiple currencies.

## Log

- 2026-09-16 — Plan started after #36. Stamp bounties agreed in principle by
  Valley; everything else is a proposal.
