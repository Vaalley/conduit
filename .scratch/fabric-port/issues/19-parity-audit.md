# 19 — Parity audit

**What to build:** The port's definition of done: a systematic sweep of the feature-parity inventory proving every Portal behaviour is either reproduced (with a passing gametest naming it) or consciously listed in the spec's deviation register — nothing dropped by accident.

**Blocked by:** 01–18 (everything).

**Status:** ready-for-agent

Work from the per-feature inventory in `docs/research/portal-feature-inventory.md` against `../spec.md`'s deviation register.

Known audit leads carried forward from ticket reviews: (1) MOTD gametests assert description text but not styling at the server seam, and no gametest sees a real name+uuid in the sample (mock players default `allowsListing=false`) — add coverage or justify; (2) no test serves an actual `server-icon.png` end-to-end.

- [ ] Every behaviour in the inventory's per-feature sections maps to a passing gametest/unit test or a deviation-register entry; gaps get tests written in this ticket
- [ ] The deviation register is verified complete and updated with anything discovered during implementation (e.g. the away-cooldown simplification)
- [ ] The full suite passes headlessly in one Gradle invocation; the production smoke boot passes with both Worlds present
- [ ] An audit summary (behaviour count, test count, deviations) is appended to the spec under Further Notes
