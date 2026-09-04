# Ranks: Newbie, Traveler, Donator

Three ranks, stored on the player record (`rank` field, `PlayerStore.rank`/`setRank`):

## Newbie

- Assigned to a genuinely brand-new account (no player record at all —
  `PlayerStore.hasRecord`) on their first ever join.
- Broadcast on that first join: `Welcome <player> from <Country> to
  MCTraveler!` (gray, name dark_aqua, country lime; country omitted when
  unknown, same rule as the ordinary join line).
- Dark_aqua name in chat and the tab list.
- Cannot create a region (`/rg start` refuses with "Newbies cannot create
  regions yet. Keep playing to become a Traveler!"). Admins bypass, as
  everywhere else region creation is gated.
- Promoted to Traveler after one hour of *play time* (`Stats.PLAY_TIME`, not
  wall clock — matches the Teleportation Crystal's own recharge clock), with
  the message:
  `You can now create regions to protect your stuff!` / `Get started using
  /region start` (`/region start` lime, underlined, click-to-run).

### Existing players

A player record that predates this feature has no `rank` field at all —
exactly the shape of every account before this shipped. Reading that as
Newbie would retroactively lock every existing player out of region
creation and re-run the welcome broadcast for them, so `RankFeature.rankOf`
treats "no rank, but a record exists" as Traveler instead — the mod's
long-standing default — and never persists that assumption (so the read
stays cheap and the account stays visibly "no rank recorded" until an admin
or a promotion actually sets one).

## Traveler

The rank every current player already effectively has: lime name, can
create regions up to the default cap.

## Donator (admin-granted only, `/rank set`)

- Gold name in chat and the tab list.
- Region cap raised from 5000 to 10000 blocks (`Rank.regionAreaCap`, the
  same 2D-footprint arithmetic `/rg start`/`end`/`extend` already used).
- Sign markdown: `%`-prefixed vanilla formatting codes (`%a` = `§a`, etc. —
  every code `ChatFormatting.getByCode` recognizes) are parsed into real
  styled text when a Donator edits a sign. A non-Donator's `%` codes are left
  as literal text — no parsing at all for them.

## `/rank set <player> <rank>`

Admin-only (`RegionsFeature.adminGate`), tab-completes the rank name, and
resolves the target the same way region membership does — the online
player, else the known-username cache (`RegionsFeature.uuidForUsername`) —
so an admin can grant Donator to someone who isn't online right now.

## Markdown (`eu.mctraveler.text.Markdown`)

A small, generic `%`-prefix legacy-formatting parser (`Style.applyFormat`'s
own semantics: colors reset decorations, `%r` resets everything). Used by
sign editing; gated to Donators at the call site, not inside `Markdown`
itself.

## Seams

- `Hooks.markdownLineFor(editor, raw)` — `DonatorSignMarkdownMixin` on
  `SignBlockEntity.updateSignText` (TAIL — vanilla never parses `§` out of
  sign text itself, so this substitutes the already-built `SignText`'s
  `Component`s after vanilla's own update+broadcast, rather than trying to
  inject styling earlier in the pipeline).
- `RankFeature.nameColor(player)` feeds `ChatFeature.chatBound` and
  `TabListFeature.displayNameWith`, replacing both call sites' hardcoded
  green. Wrapped in try/catch: this runs on vanilla's own tab-list refresh
  loop for every online player every 20 ticks, so an unreadable player
  record must never crash the whole tick — it falls back to Traveler's
  color and logs a warning instead.

## Not covered

- No persistence of a "grandfathered" rank write — an existing player with
  no rank field stays that way until something (promotion, `/rank set`)
  actually writes one.
- No help-panel entry for `/rank` (not requested).
- No custom death message. This existed briefly (`/deathmessage`, a
  `CombatTracker.getDeathMessage()` mixin) and was removed at the user's
  request; `PlayerStore` never grew a `deathMessage` field for it.
