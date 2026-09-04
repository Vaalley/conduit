# Real vanilla hearts in the tab list

Replaces the hand-drawn `❤` glyph bar (`TabListFeature.hearts`, the previous
approach) with the actual vanilla mechanism: a scoreboard objective on
`ObjectiveCriteria.HEALTH` with `RenderType.HEARTS`, shown in the `list`
display slot — the same thing the `/scoreboard objectives add <name> health`
+ `setdisplay list <name>` commands do by hand. The client renders the real
textured heart sprites (`PlayerTabOverlay.extractTablistHearts`), not text.

## Why this instead of the glyph bar

Asked directly: "is there no other way... we had the vanilla hearts... in
the tab list" on Paper 1.18. There is — this is it. The tradeoff versus a
resource-pack font trick (the other real option, e.g. the "TAB" plugin) is
that this needs no client-side assets or forced pack acceptance, at the
cost of the mod not controlling the score→hearts visual translation at all
(vanilla owns every part of it).

## What this mod owns vs. what vanilla owns

- **This mod**: creates `TabListFeature.HEALTH_OBJECTIVE` once
  (`ServerLifecycleEvents.SERVER_STARTED`, reconciled again on a hot
  reload) and keeps it the `list` slot's objective. That's the entire
  contribution — no per-tick sync code of our own.
- **Vanilla**: tracks every player's score automatically
  (`ServerPlayer.doTick()` → `updateScoreForCriteria(HEALTH, ...)`, using
  `ceil(health + absorption)` — confirmed by reading the 26.2 jar, since
  this build ships no decompiled sources) and broadcasts
  `ClientboundSetScorePacket`s identically to everyone whenever it
  changes. Renders the sprites client-side.

## Masking (SpectatorVisibility)

The old glyph bar was masked by substituting the whole display-name
Component per viewer. Health is no longer part of that text at all, so
masking moved to the packet vanilla's own auto-sync produces:
`SpectatorVisibility.maskScore` substitutes a full-health score for a
Spectator/Creative subject, to every viewer except an admin or the subject
themselves — mirroring `maskFor`'s GameType masking, over the same
connection-send seam (`SpectatorVisibilityMixin`, now dispatching on
`ClientboundSetScorePacket` too, not just `ClientboundPlayerInfoUpdatePacket`).

## A real gametest-harness limit, not a bug

`ServerPlayer.doTick()`'s health→score sync depends on the *connection's*
own tick (`ServerGamePacketListenerImpl.tick()` calls
`player.doTick()`), which a gametest mock connection — wired up directly
via `Connection`/`EmbeddedChannel` rather than accepted through the real
network listener — never receives. Confirmed by reading the jar (an
`Entity.tick()` on the mock player advances `tickCount` normally, but
`Stats.PLAY_TIME`, also only awarded from `doTick()`, never does) and by
observation (`scoreboard.getPlayerScoreInfo(...)` stayed `null` through 25
ticks). Directly setting the score by hand isn't a workaround either —
`HEALTH` is one of vanilla's own read-only criteria, so
`Scoreboard.getOrCreatePlayerScore(...).set(...)` throws exactly as
`/scoreboard players set` would.

So `TabListGameTest` tests only what this mod actually owns: the
objective's shape/slot (a direct, synchronous scoreboard check — no
packets involved) and `SpectatorVisibility.maskScore` (fed a hand-built
`ClientboundSetScorePacket`, exactly the shape vanilla's sync would send,
rather than waiting for one to arrive naturally).

## Fallout on `RegionScoreboardGameTest`

`SidebarView` (the shared gametest helper that rebuilds a player's sidebar
from captured packets) filtered scoreboard packets by *type* only, not by
objective name — harmless while `region` was the only objective any test
player's client ever heard about. Once every test player also gets
`ClientboundSetObjectivePacket`/`ClientboundSetScorePacket` traffic for
this new, unrelated `list`-slot objective on join, that packet leaked into
`SidebarView`'s bookkeeping (an extra `objectiveAdditions` count, a
same-owner-keyed but wrong-objective score entry showing up as a phantom
sidebar row). Fixed by scoping `SidebarView` to `objectiveName == "region"`.
