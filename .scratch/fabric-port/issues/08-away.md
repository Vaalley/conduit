# 08 — Away

**What to build:** The away system: `/away` marks you away instantly, five minutes of inactivity marks you away automatically, any interaction brings you back — each transition broadcast to everyone in the Portal's exact wording — with the 3-second return cooldown.

**Blocked by:** 01 (Scaffold), 02 (Text DSL).

**Status:** ready-for-agent

See `../spec.md` (User Stories 16–18) and the AwayFeature section of `docs/research/portal-feature-inventory.md`. All timing is server-tick-based so tests can fast-forward. The Portal's silent-at-exactly-3.0s cooldown rounding artifact is simplified to always showing the cooldown error (intent parity — note it in the deviation register during the parity audit).

- [ ] `/away` marks the sender away and broadcasts `<name> is now away` in the exact format
- [ ] Auto-away triggers after 5 minutes (in ticks) without movement, chat, commands, block changes, or item use
- [ ] Any interaction while away clears it and broadcasts `<name> is no longer away`; leaving cleans up state
- [ ] Within 3 seconds of returning, `/away` replies with the exact cooldown error (seconds to 0.1 precision)
- [ ] Gametests: manual away, tick-forwarded auto-away, each interaction type clearing away, cooldown
