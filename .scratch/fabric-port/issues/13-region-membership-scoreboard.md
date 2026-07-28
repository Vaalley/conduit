# 13 — Region membership + scoreboard sidebar

**What to build:** Region community features: `/rg add` and `/rg remove` with the Portal's caps, guards, suggestions and messages, and the live sidebar scoreboard that appears when standing in a region.

**Blocked by:** 12 (Region core).

**Status:** ready-for-agent

See `../spec.md` (User Stories 31, 33) and the RegionFeature scoreboard/membership subsections of `docs/research/portal-feature-inventory.md` for exact layout and messages. Member names resolve via the name cache (ticket 03).

- [ ] `/rg add`: stand-in-region requirement, 99-member cap, resident/admin/parent-resident permission, duplicate error, success message — all exact
- [ ] `/rg remove`: tab-completion suggests current region members by typed prefix; not-a-member and only-member guards; success message — all exact
- [ ] Sidebar shows on region entry (unless NO_SCOREBOARD) and hides on exit: green bold title truncated to 20, bold Residents count, strikethrough separator, member list (self white, others gray, hidden score numbers)
- [ ] Scoreboard updates live for everyone inside on add/remove/rename; moving between adjacent regions swaps cleanly
- [ ] Gametests: membership flows and errors, scoreboard content and lifecycle
