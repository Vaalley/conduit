# Spec: Economy — how money enters and leaves player balances

Status: needs-info (living plan; amounts settled by Valley; do not implement
until Valley says so). Lines marked **PROPOSED** are Devin's suggested answers
awaiting Valley's veto.

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
  Tiers Common $10 / Uncommon $20 / Rare $50 / Epic $100 (≈ $1,580 total).
  Payout shown inline in the stamp announcement. Already-held stamps are paid
  out once on next login (retroactive).
- **Starting balance on first join** — AGREED. `$100`, no chat message. Also
  granted once to existing players whose record has no `balance` field.
- **Store creation fee** — AGREED. `$20`, admins pay too, never refunded.
- **Anniversary payout** — AGREED. `$200 × √years` (diminishing growth: $200,
  $283, $346, $400, …), paid on the first login after each anniversary of
  `firstJoin`.
- **Store slot upgrade** — AGREED. `$15` once per store, 27 → 54 slots.
- **Buy orders** — AGREED. Reverse stores funded from the owner's balance, with
  an optional maximum quantity wanted.
- **`/economy stats`** — AGREED. Admin anti-inflation dashboard built on the ledger.
- **`/balance top`** — AGREED. Cached sorted view refreshed on write; shows all
  players regardless of online/vanish state.
- **Name cosmetics** — AGREED. Chat *and* tablist: one-character tag (`$100`),
  solid hex colour (`$100`), two-colour gradient (`$200`). No brackets around the
  tag. Changing again costs again; `/cosmetic reset` is free. Any valid hex is
  accepted (no readability floor). Donators keep a unique marker.
- **Not doing**: play-time stipend, server buy-back store, `/rtp` fee, Energy
  refill, paid region quota, other cosmetics, postcard fee.

## Faucets

### 1. Passport stamp bounties (agreed)

- Hook point: `PassportFeature.announceStamps` / `grantStamp` — every path that
  grants a stamp already funnels through `Stamps.grant(...)` and then announces,
  so the deposit belongs next to the announcement.
- Payout per stamp, keyed by a tier on the `Stamp` definition (new field, e.g.
  `bounty: Long`, or a tier enum → amount table). Tiers:

  | Tier   | Amount | Examples                                                          |
  |--------|--------|-------------------------------------------------------------------|
  | Common | $10    | first_steps, wormhole, first_blood, postcard_1, biome_10, digger  |
  | Uncommon | $20 | wanderer, pony_express, channel_swimmer, sightseer, crystal_5, excavator, biome_30, postcard_10, gone_fishing, cake, sneaky |
  | Rare   | $50   | nomad, marathon, three_worlds, trespasser, diplomat, crystal_50, roulette_regular, monster_hunter, sleepyhead, insomniac, big_spender, leg_day, event-only stamps (ashes_to_ashes, into_the_void, terminal_velocity, storm_chaser) |
  | Epic   | $100  | globetrotter, biome_all, ambassador, mountain_mover, veteran      |

- Chat: extend the existing stamp announcement with the payout, e.g.
  `PASSPORT New stamp: Wanderer  (+$20)`.
- Retroactive: one-off migration pays out every already-held stamp on the
  player's next login. Idempotent via a per-player `stampBountiesPaid` marker
  (or per-stamp `paid` flag in the passport) so it never runs twice.
- Total available from all 38 stamps: 6×$10 + 11×$20 + 16×$50 + 5×$100 ≈ **$1,580**
  — the ceiling on "free" money per player (plus start + anniversaries), which
  is the point.

### 2. Starting balance (agreed, $100)

- Rule: on login, if the player's JSON has **no `balance` field at all**, deposit
  `$100`. That covers brand-new players and existing players from before the
  economy in one rule.
- Exploit-proof because the guard is field *absence*, never `balance == 0`: a
  player who spends down to $0 still has `"balance": 0` written and cannot
  re-trigger it; players cannot delete their own record. Legacy players who
  already carry a (decimal) `balance` keep it and get nothing.
- No chat message.
- Ledger reason: `join-bonus`.

### 3. Anniversary payout (agreed, $200 × √years)

- On the first login on or after each anniversary of `PlayerStore.firstJoin`, pay
  `round($200 × √y)` for anniversary `y`: $200, $283, $346, $400, $447, …
  Pairs with the `veteran` stamp ("One year on MCTraveler"), which fires on the
  same clock.
- Idempotency: persist `lastAnniversaryPaid: <year index>` in the player JSON so a
  missed year is paid once on the next login, never twice.
- Chat: `BALANCE Happy 2nd anniversary! +$X`.

### 4. Admin / event grants (exists)

- `/balance give` already covers contests, compensation, event prizes.

### Rejected

- **Per-block / per-mob-kill payouts** — rewards AFK and auto farms; inflates
  hardest. Stamps already reward these milestones once.
- **Play-time stipend** — steady inflation source.
- **Server buy-back store** — fastest inflation source; trade must carry itself.

## Sinks

### 1. Store creation fee (agreed, $20)

- `/store create <price>` withdraws a flat `$20` from the creator *after* all
  validation passes (frame present, has item, not already a store, region allows)
  and before the frame is locked — insufficient funds → `ERROR You need $20 to
  create a store (you have $X)` and nothing changes.
- Not refunded on `/store delete` (it is a sink, and refunds would make frame spam
  free again). Admins pay too.
- Creation message gains the fee: `STORE Store created (-$20) — selling ...`.

### 2. Store slot upgrade (agreed, $15)

- `/store upgrade` while looking at your own store: one-off `$15`, stock menu
  becomes 6 rows (54 slots) and `StoreLadder.MAX_STOCK` doubles for that store.
- `StoreRecord` gains `rows: Int` (3 default, 6 upgraded); persisted in
  `stores.json`, older records without the field read as 3.
- Not refunded on delete; upgrade is per store, not per player.

### 3. Name cosmetics (agreed: tag $100, colour $100, gradient $200)

Applies everywhere the player's name is painted: chat and the tab list both go
through `RankFeature.nameColor(player)` (used by `ChatFeature` and
`TabListFeature.tabDisplayName`), so one seam becomes "name style" instead of
"rank colour". Rendered as `<tag> <name>` — e.g. `🐢 Vaalley`, no brackets.

- **Tag** — `/cosmetic tag <char>`: exactly one grapheme in front of the name,
  space-separated. Allow emoji and symbols; block anything that renders as
  nothing, whitespace, or formatting codes; keep a small denylist. `$100` each
  time it is set (changing it costs again).
- **Solid colour** — `/cosmetic color <#rrggbb>`: any hex; Minecraft components
  support full RGB (`TextColor.fromRgb`). Only malformed hex is rejected — no
  readability floor; players choose at their own risk. `$100` per change.
- **Gradient** — `/cosmetic gradient <#from> <#to>`: linear per-character
  interpolation across the name; renders as one component per character. Costs
  more than solid. `$200` per change. Setting a gradient replaces a solid colour
  and vice versa.
- **Donator marker** — donators keep something nobody can buy. **PROPOSED**: a
  gold `✦` placed between the tag and the name (`🐢 ✦ Vaalley`; `✦ Vaalley`
  without a tag), always in gold regardless of the chosen name colour.
- **Persistence** — new typed fields on `PlayerStore`: `nameTag: String?`,
  `nameColor: String?` (hex), `nameGradient: Pair<String, String>?`. Purchases go
  through the ledger (`fee:cosmetic-tag` etc.).
- **Reset** — `/cosmetic reset` is free (no refund); admins can strip a
  tag/colour (`/cosmetic reset <player>`) for abuse.
- **Rendering budget** — tablist refresh rebuilds every display name each tick
  cycle; gradient components are ~16 parts per name, fine at this server's size
  but cache the built `Component` per player and invalidate on change.

### Rejected

- `/rtp` skip-cooldown fee; extra crystal Energy refill; paid `/region extend`
  quota; other cosmetics (sign colours, `/cat` variants); postcard sending fee.

## Systems (agreed)

### 1. `/balance top` leaderboard

- Passport-style chest menu (reuse the `PassportMenu` layout conventions): top N
  players by balance, one head per player with name + `$X` in the title, ranked
  1..N; the viewer's own row highlighted / shown last if outside the top N.
- Data: scan `players/*.json` for `balance` — needs a cheap index or a cached
  sorted view refreshed on write (`Economy.deposit/withdraw` are the only writers),
  so the command never walks the disk on click. Decision: a cached sorted view,
  built once at server start from `players/*.json` and refreshed on every write.
- All players are shown, online or not, vanished or not.

### 2. Transaction log / `/balance history`

- Every balance mutation appends one line: timestamp, player, delta, resulting
  balance, reason (`store:<frameId>`, `pay:<uuid>`, `stamp:<id>`, `admin:<who>`,
  `fee:store-create`, `join-bonus`, ...). Written from a single choke point so
  nothing can move money without a trail.
- Storage: append-only JSONL per player under `mctraveler/ledger/<uuid>.jsonl`
  (matches flat-JSON persistence). **PROPOSED**: no trimming — entries are ~100
  bytes, a very active player makes a few thousand a year; `/economy stats` and
  dispute tracing both want full history.
- `/balance history [player] [page]` — own history for everyone, other players
  admin-only; paged chat output newest-first, same style as other paged commands.
- Purpose: trace disputes and dupes once stores go live; also feeds any future
  `/store stats`.

### 3. Buy orders (reverse stores)

- **PROPOSED** command: `/store buy <price per item> [max items wanted]` while
  looking at a frame with the wanted item (reads as "this store buys"; keeps the
  `/store` family flat). Creates a store with `kind = BUY`. Same locked-frame
  mechanics, same `stores.json`, extra fields `kind` (`SELL` default) and
  `maxStock` (default `MAX_STOCK`).
- Sellers right-click → 9-slot menu with the same quantity ladder; a slot is
  active when the seller holds ≥ qty of the item *and* the owner's balance covers
  `price × qty`; otherwise cobweb "Owner can't afford" / "You don't have enough".
  Clicking moves items from seller inventory into the store's stock and pays
  the seller from the owner's balance (offline owner fine — store write).
- A slot is also inactive once `stock + qty > maxStock` (cobweb "Not buying
  more").
- Owner right-click → stock menu to *collect* bought items.
- Ledger reason: `buyorder:<frameId>`. Creation fee applies like normal stores.

### 4. `/economy stats` (admin dashboard)

- Reads the ledger (Systems §2) and prints: total money supply (sum of all
  balances), money created vs destroyed this week by reason (stamps, join bonus,
  anniversary vs store fees, upgrades, …), top faucet/sink, player-to-player
  volume (`pay`, `store`, `buyorder`), and net inflation this week vs last.
- Chat table first; a Passport-style menu is a possible later polish.
- Requires the ledger to record `reason` categories consistently — define the
  reason enum once and share it with `/balance history`.

## Proposed answers awaiting veto

Everything else in this document is decided. Devin's proposals (marked
**PROPOSED** inline):

1. Donator marker: gold `✦` between tag and name.
2. Ledger retention: keep everything, no trimming.
3. Buy-order command: `/store buy <price> [max]`.
4. Rollout order (one PR each, on top of #78):
   1. Ledger + reason enum (everything else writes through it).
   2. Stamp bounties + retroactive payout + starting balance + anniversary.
   3. Store creation fee + slot upgrade + buy orders.
   4. `/balance top` + `/balance history` + `/economy stats`.
   5. Name cosmetics.

## Out of scope for now

- Shops with server-set prices, auctions, taxes, interest.
- Multiple currencies.

## Log

- 2026-09-16 — Plan started after #36. Stamp bounties agreed in principle by
  Valley; everything else is a proposal.
- 2026-09-16 — Store creation fee (sink) and first-join starting balance (faucet)
  agreed; all amounts deferred to Valley's review (`$TBD`).
- 2026-09-16 — `/balance top` leaderboard and per-player transaction log
  (`/balance history`) agreed. Further faucet/sink ideas were floated and are
  pending Valley's review before they enter this file.
- 2026-09-16 — Anniversary payout, store slot upgrade, buy orders and
  `/economy stats` agreed (from a second batch of ideas; the rest not adopted).
- 2026-09-16 — Name cosmetics (tag + hex colour + gradient, chat and tablist)
  agreed. "Repeatable stamps" discussed and set aside, not in the plan.
- 2026-09-16 — Valley set every amount and answered the open questions; the
  stipend, buy-back store and the remaining sink ideas are rejected. Anniversary
  formula fixed to `$200 × √years`. Four proposals left for veto (above).
