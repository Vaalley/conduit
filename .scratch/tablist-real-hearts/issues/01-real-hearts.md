# 01 — Real vanilla hearts in the tab list

**What to build:** see `../spec.md`.

**Status:** done

- [x] `TabListFeature.HEALTH_OBJECTIVE` — real server scoreboard objective,
      `ObjectiveCriteria.HEALTH` + `RenderType.HEARTS`, `list` display slot.
      Created at `SERVER_STARTED`, reasserted on a hot reload.
- [x] `TabListFeature.tabDisplayName`/`displayNameWith` no longer build a
      heart-glyph Component — just name + padding + `[Nms]`. `hearts()` and
      `displayNameWithFullHearts` removed.
- [x] `SpectatorVisibility.maskScore` — masks the health score the same way
      `maskFor` already masks the GameType tell; `maskFor` itself dropped
      its now-obsolete hearts-masking branch.
- [x] `SpectatorVisibilityMixin` dispatches on `ClientboundSetScorePacket`
      too, not just `ClientboundPlayerInfoUpdatePacket`.
- [x] `MixinHooks`/`MixinHooksImpl` — new `maskHealthScore` bridge method.
- [x] `TabListGameTest`: objective-shape test (direct scoreboard read);
      masking test (hand-built packet, since the real auto-sync can't run
      in this harness — see spec); the old hearts-in-text tests removed or
      trimmed to what they still assert (gamemode masking, name/ping).
- [x] `SidebarView` (shared gametest helper) scoped to `objectiveName ==
      "region"`, fixing collateral failures in `RegionScoreboardGameTest`
      once every test player also received the new objective's own packets.

## Comments

Verified: compile, `:bootstrap:runGameTest` (only the 6 known mcaselector
`world_merge_*` failures), `:runtime:test`, prod smoke.
