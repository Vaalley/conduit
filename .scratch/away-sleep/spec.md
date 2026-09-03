# Away players and the sleep requirement (issue #52)

A player marked away (`/away`, or five idle minutes — `AwayFeature`) is excluded
from the count of players who have to be asleep for the server to skip the night.
Without this, one AFK player holds the whole server awake.

## Seam

`ServerLevel` hands `SleepStatus` a `List<ServerPlayer>` for every decision:

- `SleepStatus.update(players)` — recomputes `activePlayers` / `sleepingPlayers`.
- `SleepStatus.areEnoughDeepSleeping(percentage, players)` — the day-skip re-scan.

`SleepRequirementMixin` filters away players out of the list at the head of both,
so they leave every tally consistently. An away player is never actually in a bed
(getting in is an interaction that clears the away flag), so this only ever
removes them from the denominator — it never removes a real sleeper.

`vanilla already excludes spectators` in `update`; this stacks on top of that.

## Not changed

- The `playersSleepingPercentage` gamerule still applies as-is to the reduced
  count.
- No message wording changes ("N/M players sleeping" now reads off the reduced M).
- State is `AwayFeature`'s existing in-memory per-session map; nothing persists.
