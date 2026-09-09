# 03 — `/rtp end`

**What to build:** `/rtp end` into the real End for completed players, its own
ring and cooldown bucket.

**Blocked by:** 02 (the completed flag)

**Status:** ready-for-agent

- [ ] `rtp.json`: `endRadius`, `endMinDistance`, `endCooldownSeconds`
- [ ] `RtpCooldown` keyed by `(uuid, RtpKind)`; existing calls default to OVERWORLD
- [ ] `RtpPicker.pick` takes a ring + attempts; End void columns rejected
- [ ] `/rtp end` gate, countdown, messages
- [ ] Gametest: refused before completion; unit test for the End ring candidate
