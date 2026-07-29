# 08 — Away

**What to build:** The away system: `/away` marks you away instantly, five minutes of inactivity marks you away automatically, any interaction brings you back — each transition broadcast to everyone in the Portal's exact wording — with the 3-second return cooldown.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** done

See `../spec.md` (User Stories 16–18) and the AwayFeature section of `docs/research/portal-feature-inventory.md`. All timing is server-tick-based so tests can fast-forward. The Portal's silent-at-exactly-3.0s cooldown rounding artifact is simplified to always showing the cooldown error (intent parity — note it in the deviation register during the parity audit).

- [x] `/away` marks the sender away and broadcasts `<name> is now away` in the exact format
- [x] Auto-away triggers after 5 minutes (in ticks) without movement, chat, commands, block changes, or item use
- [x] Any interaction while away clears it and broadcasts `<name> is no longer away`; leaving cleans up state
- [x] Within 3 seconds of returning, `/away` replies with the exact cooldown error (seconds to 0.1 precision)
- [x] Gametests: manual away, tick-forwarded auto-away, each interaction type clearing away, cooldown

## Comments

Implemented in `eu.mctraveler.away.AwayFeature` (registered from `MCTraveler.onInitialize`); 13 gametests in `eu.mctraveler.gametest.AwayGameTest` drive it through a headless `TestPlayer` fixture (real `PlayerList.placeNewPlayer` login path, records every system message the player is shown).

Constants (all server ticks): timeout 6000 (5 min), checker cadence 100 (~5 s), return cooldown 60 (3 s).

**Deviations / interpretations (for the parity-audit register — spec.md not edited from this worktree):**

1. As pre-agreed in this ticket: within the 3 s return window `/away` *always* replies `ERROR You cannot use /away again for another <red seconds> seconds yet`; the Portal's silent-at-exactly-3.0 s rounding artifact is not reproduced.
2. Remaining seconds render as the Portal's JS template would have: whole values drop the decimal ("3", not "3.0"; otherwise "2.9"-style, 0.1 precision).

**Implementation notes:**

- Interactions: join/leave via `ServerPlayerEvents.JOIN/LEAVE` (fired inside `PlayerList.placeNewPlayer`/`remove`, so they also fire for headless test players — `ServerPlayConnectionEvents.DISCONNECT` does not, it hangs off netty channel teardown); chat via `ServerMessageEvents.CHAT_MESSAGE`; block break via `PlayerBlockBreakEvents.AFTER`; block place / item use via `UseBlockCallback`/`UseItemCallback`; commands via a HEAD mixin on `Commands.performCommand` (`eu.mctraveler.mixin.CommandsMixin` — Fabric API has no command-execution event; first mixin in the repo, with `mctraveler.mixins.json` wired into `fabric.mod.json`).
- The mixin ordering (interaction before the command executes) is what makes `/away` while away first return you and then hit the cooldown error, matching the Portal's hook ordering.
- Movement is client-authoritative and has no server event; it is detected as a position/look delta per player at END_SERVER_TICK.
- Tick fast-forwarding in tests: `AwayFeature.fastForward(player, ticks)` ages one player's `lastInteractionTick`/`cooldownStartTick` instead of warping a global clock — gametests in a batch run concurrently in one server, so a global warp would cross-contaminate tests.
- State is in-memory only, cleaned on leave and cleared on SERVER_STOPPED; no visual marker beyond the broadcasts (parity).
