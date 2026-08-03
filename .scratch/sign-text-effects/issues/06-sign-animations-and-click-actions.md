# 06 — Animated effects and click actions

**What to build:** Add the later-tier animated effects, off by default. The
animator needs a tick loop, tracked-chunk gating, a configured budget, and
frames that never persist: saving a sign re-renders its base frame from stored
source. Add admin-only `<cmd:...>`, `show_dialog`, and `custom` click tags for
waxed signs, with an explicit security boundary around server-side actions.
Vanilla `open_url` is not a server-side sign action and is not part of this
ticket's dispatch surface.

**Blocked by:** 03, 05.

**Status:** needs-triage

See ../spec.md (User Stories 17–18 and 13; Implementation Decisions "Animation
never persists", "Configuration", and "Click actions are a separate,
admin-only tag").

- [ ] Animation is disabled by default and cannot activate outside the
      configured effect policy and budget
- [ ] The animator has a server tick loop that updates only eligible animated
      signs and respects the configured tick interval or equivalent budget
- [ ] Work is skipped when the sign's chunk is not tracked by any nearby player
- [ ] Animated frames are sent as block-entity updates to tracked players
      without treating transient frames as persisted sign state
- [ ] Saving a sign writes the resting frame rendered from stored source, never
      the current animation frame
- [ ] The animator enforces the configured cap on animated signs per world and
      does not exceed the per-line component cap
- [ ] `<rainbow:animate>` and the selected first-tier movement effects have
      specified frame behavior, with unsupported effects refused clearly
- [ ] `<cmd:...>` is admin-only and produces a `run_command` click action only
      on waxed signs
- [ ] `show_dialog` and `custom` click tags are admin-only, preserve their
      vanilla dispatch restrictions, and are available only on waxed signs
- [ ] No `open_url` server-side action is fabricated or dispatched by the
      feature
- [ ] Click-action parsing and execution are framed as a security boundary:
      non-admin authors cannot write server commands, dialogs, or custom actions
- [ ] Region protection and vanilla sign editability still apply before any
      click action or animation state changes
- [ ] GameTests cover the default-off policy, tracked-player gating, frame
      updates, save-time base rendering, budget refusal, and waxed-sign
      admin-only click actions
