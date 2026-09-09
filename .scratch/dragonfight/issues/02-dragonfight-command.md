# 02 — `/dragonfight`

**What to build:** the command family (`/dragonfight`, `confirm`, `leave`,
`invite`, `join`, admin `reset`), the state file, the exit-portal completion
flow, gateway sealing, eviction + deletion, TTL sweep.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `DragonFightConfig` (`dragonfight.json`) and `DragonFightState`
      (`dragonfight-state.json`)
- [ ] Requirements from vanilla stats with the progress message
- [ ] Warning + 60 s confirm
- [ ] Enter / re-enter / already-inside / leave
- [ ] Invite / join
- [ ] Exit portal: guest vs owner; completion; egg auto-give; scheduled deletion
      with eviction
- [ ] Startup wipe, TTL sweep
- [ ] `/dragonfight reset <player>` (admin)
- [ ] Gametests for each branch, unit tests for state/config parsing
