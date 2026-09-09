# 03 — `/rtp end`

**What to build:** `/rtp end` into the real End for completed players, its own
ring and cooldown bucket.

**Blocked by:** 02 (the completed flag)

**Status:** done

- [x] `rtp.json`: `endRadius`, `endMinDistance`, `endCooldownSeconds`
- [x] `RtpCooldown` keyed by `(uuid, RtpKind)`; existing calls default to OVERWORLD
- [x] `RtpPicker.pick` takes a ring + attempts; End void columns rejected
- [x] `/rtp end` gate, countdown, messages
- [x] Gametest: refused before completion; unit test for the End ring candidate
