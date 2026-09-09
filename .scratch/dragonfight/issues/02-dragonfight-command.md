# 02 — `/dragonfight`

**What to build:** the command family (`/dragonfight`, `confirm`, `leave`,
`invite`, `join`, admin `reset`), the state file, the exit-portal completion
flow, gateway sealing, eviction + deletion, TTL sweep.

**Blocked by:** 01

**Status:** done

- [x] `DragonFightConfig` (`dragonfight.json`) and `DragonFightState`
      (`dragonfight-state.json`)
- [x] Requirements from vanilla stats with the progress message
- [x] Warning + 60 s confirm
- [x] Enter / re-enter / already-inside / leave
- [x] Invite / join
- [x] Exit portal: guest vs owner; completion; egg auto-give; scheduled deletion
      with eviction
- [x] Startup wipe, TTL sweep
- [x] `/dragonfight reset <player>` (admin)
- [x] Gametests for each branch, unit tests for state/config parsing
