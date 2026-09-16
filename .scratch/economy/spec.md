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
- **Starting balance on first join** — AGREED. A new player never starts at $0.
  Amount `$TBD`.
- **Store creation fee** — AGREED. `/store create` charges the creator a flat fee.
  Amount `$TBD`.
- **Anniversary payout** — AGREED. Paid on each anniversary of `firstJoin`, scaled
  by years. Amount `$TBD`.
- **Store slot upgrade** — AGREED. Pay once to grow a store's stock from 27 to 54
  slots. Amount `$TBD`.
- **Buy orders** — AGREED. Reverse stores: a frame that buys an item from players
  at a set price, funded from the owner's balance.
- **`/economy stats`** — AGREED. Admin anti-inflation dashboard built on the ledger.
- **Name cosmetics** — AGREED. Paid vanity for chat *and* tablist: a one-character
  tag before the name (emoji), a custom hex name colour, and (pricier) a
  two-colour gradient. Donators keep a unique marker. Amounts `$TBD`.
- **Amounts**: Valley will fill in every `$TBD` (and the stamp tier table) after
  reviewing this file; the numbers below are placeholders until then.

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

### 3. Starting balance on first join (agreed, amount TBD)

- Deposit `$TBD` the first time a player joins. Hook: the same first-join path
  that records `PlayerStore.firstJoin` (Newbie rank onboarding), guarded so it
  only fires when `firstJoin` was previously unset — never on returning players.
- Existing players who already have a `balance` field are untouched; players with
  a first-join timestamp but no balance field get nothing (they are not new).
  (OPEN — or grant it to everyone lacking a balance on next login?)
- Chat: `BALANCE Welcome! You start with $TBD` on join.

### 4. Anniversary payout (agreed, amount TBD)

- On the first login on or after each anniversary of `PlayerStore.firstJoin`, pay
  `$TBD × years` (or a per-year table — OPEN). Pairs with the `veteran` stamp
  ("One year on MCTraveler"), which fires on the same clock.
- Idempotency: persist `lastAnniversaryPaid: <year index>` in the player JSON so a
  missed year is paid once on the next login, never twice.
- Chat: `BALANCE Happy 2nd anniversary! +$X`.

### 5. Admin / event grants (exists)

- `/balance give` already covers contests, compensation, event prizes.

### 6. Server buy-back store (idea, cautious)

- Admin-owned frame(s) at spawn that *buy* bulk items at fixed low prices —
  a price floor. Fastest inflation source; only if trade alone feels dead.

### Rejected

- **Per-block / per-mob-kill payouts** — rewards AFK and auto farms; inflates
  hardest. Stamps already reward these milestones once.

## Sinks

### 1. Store creation fee (agreed, amount TBD)

- `/store create <price>` withdraws a flat `$TBD` from the creator *after* all
  validation passes (frame present, has item, not already a store, region allows)
  and before the frame is locked — insufficient funds → `ERROR You need $TBD to
  create a store (you have $X)` and nothing changes.
- Not refunded on `/store delete` (it is a sink, and refunds would make frame spam
  free again). Admins pay too unless we decide otherwise. (OPEN)
- Creation message gains the fee: `STORE Store created (-$TBD) — selling ...`.

### 2. Store slot upgrade (agreed, amount TBD)

- `/store upgrade` while looking at your own store: one-off `$TBD`, stock menu
  becomes 6 rows (54 slots) and `StoreLadder.MAX_STOCK` doubles for that store.
- `StoreRecord` gains `rows: Int` (3 default, 6 upgraded); persisted in
  `stores.json`, older records without the field read as 3.
- Not refunded on delete; upgrade is per store, not per player.

### 3. Name cosmetics (agreed, amounts TBD)

Applies everywhere the player's name is painted: chat and the tab list both go
through `RankFeature.nameColor(player)` (used by `ChatFeature` and
`TabListFeature.tabDisplayName`), so one seam becomes "name style" instead of
"rank colour". Rendered as `<tag> <name>` — e.g. `🐢 Vaalley` (no brackets;
OPEN, easy to flip).

- **Tag** — `/cosmetic tag <char>`: exactly one grapheme in front of the name,
  space-separated. Allow emoji and symbols; block anything that renders as
  nothing, whitespace, or formatting codes; keep a small denylist. `$TBD`, one-off
  (changing it later costs again, OPEN).
- **Solid colour** — `/cosmetic color <#rrggbb>`: any hex; Minecraft components
  support full RGB (`TextColor.fromRgb`). Reject colours too close to black /
  the chat background for readability (luminance floor, OPEN). `$TBD`.
- **Gradient** — `/cosmetic gradient <#from> <#to>`: linear per-character
  interpolation across the name; renders as one component per character. Costs
  more than solid. `$TBD`.
- **Donator marker** — donators keep something nobody can buy: a fixed unique
  glyph after/before the tag (e.g. `✦`) or a gold outline on the tag. The rank
  colour stops being the only differentiator, which Valley is fine with.
- **Persistence** — new typed fields on `PlayerStore`: `nameTag: String?`,
  `nameColor: String?` (hex), `nameGradient: Pair<String, String>?`. Purchases go
  through the ledger (`fee:cosmetic-tag` etc.).
- **Reset** — `/cosmetic reset` is free; admins can strip a tag/colour
  (`/cosmetic reset <player>`) for abuse.
- **Rendering budget** — tablist refresh rebuilds every display name each tick
  cycle; gradient components are ~16 parts per name, fine at this server's size
  but cache the built `Component` per player and invalidate on change.

### Ideas (not agreed)

- `/rtp` skip-cooldown fee.
- Extra crystal Energy refill.
- `/region extend` beyond the free 5000-block quota (paid quota bump instead of
  asking an admin).
- Other cosmetics: sign colours for non-donators, `/cat` variants.
- Postcard sending fee (small, thematic).

## Systems (agreed)

### 1. `/balance top` leaderboard

- Passport-style chest menu (reuse the `PassportMenu` layout conventions): top N
  players by balance, one head per player with name + `$X` in the title, ranked
  1..N; the viewer's own row highlighted / shown last if outside the top N.
- Data: scan `players/*.json` for `balance` — needs a cheap index or a cached
  sorted view refreshed on write (`Economy.deposit/withdraw` are the only writers),
  so the command never walks the disk on click. (OPEN — cache vs scan)
- Vanished players and admins: shown or hidden? (OPEN, leaning hidden for vanish)

### 2. Transaction log / `/balance history`

- Every balance mutation appends one line: timestamp, player, delta, resulting
  balance, reason (`store:<frameId>`, `pay:<uuid>`, `stamp:<id>`, `admin:<who>`,
  `fee:store-create`, `join-bonus`, ...). Written from a single choke point so
  nothing can move money without a trail.
- Storage: append-only JSONL per player under `mctraveler/ledger/<uuid>.jsonl`
  (matches flat-JSON persistence; rotation/trim policy OPEN, e.g. keep last 1000).
- `/balance history [player] [page]` — own history for everyone, other players
  admin-only; paged chat output newest-first, same style as other paged commands.
- Purpose: trace disputes and dupes once stores go live; also feeds any future
  `/store stats`.

### 3. Buy orders (reverse stores)

- `/store buy-order <price per item>` (name OPEN) while looking at a frame with
  the wanted item: creates a store with `kind = BUY`. Same locked-frame
  mechanics, same `stores.json`, one extra `kind` field (`SELL` default).
- Sellers right-click → 9-slot menu with the same quantity ladder; a slot is
  active when the seller holds ≥ qty of the item *and* the owner's balance covers
  `price × qty`; otherwise cobweb "Owner can't afford" / "You don't have enough".
  Clicking moves items from seller inventory into the store's stock and pays
  the seller from the owner's balance (offline owner fine — store write).
- Owner right-click → stock menu to *collect* bought items (and, OPEN, top up a
  cap on how much it will buy: `maxStock`, default `MAX_STOCK`).
- Ledger reason: `buyorder:<frameId>`. Creation fee applies like normal stores.

### 4. `/economy stats` (admin dashboard)

- Reads the ledger (Systems §2) and prints: total money supply (sum of all
  balances), money created vs destroyed this week by reason (stamps, join bonus,
  anniversary vs store fees, upgrades, …), top faucet/sink, player-to-player
  volume (`pay`, `store`, `buyorder`), and net inflation this week vs last.
- Chat table first; a Passport-style menu is a possible later polish.
- Requires the ledger to record `reason` categories consistently — define the
  reason enum once and share it with `/balance history`.

## Open questions

- Final tier amounts and stamp → tier assignment (table above is a first draft).
- Retroactive payout for already-held stamps: (a) none vs (b) pay on next login.
- Starting balance for existing players who have no `balance` field yet.
- Should admins be exempt from the store creation fee? Refund on delete? (leaning no/no)
- Where the payout shows in chat (inline with the stamp line vs separate).
- Whether any sink ships together with stamp bounties, or later.
- `/balance top`: cached index vs disk scan; hide vanished players?
- Ledger retention (trim to last N entries? never?).
- Name cosmetics: brackets around the tag or not; what the donator marker is;
  do changes cost again; luminance floor for colours.
- Anniversary payout: linear `$X × years` or a table? Cap?
- Buy orders: command name; whether owners can set a max buy quantity.

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
