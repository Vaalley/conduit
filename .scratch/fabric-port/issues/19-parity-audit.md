# 19 — Parity audit

**What to build:** The port's definition of done: a systematic sweep of the feature-parity inventory proving every Portal behaviour is either reproduced (with a passing gametest naming it) or consciously listed in the spec's deviation register — nothing dropped by accident.

**Blocked by:** 01–18 (everything).

**Status:** done

Work from the per-feature inventory in `docs/research/portal-feature-inventory.md` against `../spec.md`'s deviation register.

- [x] Every behaviour in the inventory's per-feature sections maps to a passing gametest/unit test or a deviation-register entry; gaps got tests written in this ticket
- [x] The deviation register is verified complete and updated with everything discovered during implementation
- [x] The full suite passes headlessly in one Gradle invocation; the production smoke boot passes with both Worlds present
- [x] An audit summary (behaviour count, test count, deviations) is appended to the spec under Further Notes

## Comments

The audit ran in the orchestrator session after seven consecutive API failures (three 500s, four 529 Overloaded) killed the audit subagent; its one completed contribution — the strengthened `SmokeHook` — was rescued from its worktree and landed. Work was committed incrementally for that reason.

**What was done**

1. **Production smoke now proves the topology** (`36a7ff6`). `SmokeHook` asserts the Worlds service came up with exactly Primary and Secondary and that all six dimensions are loaded, then logs each mapping. `./gradlew prodServer` passes on the real Fabric server launcher. This closes ticket 01's deferral ("the smoke currently proves vanilla trio boot") and is the only place the claim can be made — `GameTestServer` needs `GameTestServerDatapackDimensionsMixin` just to see datapack dimensions.
2. **Deviation register consolidated** (`123af7b`): entries 20–52 swept in from every ticket's `## Comments`, grouped by area. Entries 1–19 deliberately keep their numbers because ticket comments cite them (`deviation 9`, `deviation 12`, …).
3. **Two coverage gaps closed with tests** (`2a35aa1`):
   - MOTD styling is now asserted on the live server's status payload, not only in the unit tier. Writing it caught a wrong assumption of mine first: the address is *three* runs, because `MCTraveler` is bolded inside `play.MCTraveler.eu`, so the assertion joins the green runs.
   - The tab list's cross-World test now teleports into the real `mctraveler:secondary`. It had used the vanilla nether as a stand-in because it was written before ticket 04 shipped the topology (ticket 09 flagged this itself).
4. **Audit summary written into `spec.md`** under a `## Parity audit` heading: suite counts, per-feature coverage map, the inspection-only list, the deliberate gaps, harness debt, and the cutover checklist.

**Matrix result.** Every feature and module in inventory §2, the command/text framework in §3, and every store in §4 maps to either a named test or a register entry. The Portal's Feature/Module/hook framework and its four dead API-only modules have no port surface by ADR 0002 and are recorded as such rather than counted as gaps. Suite: 202 gametests + 145 unit tests; 52 deviations.

**Follow-ups deliberately not done here** (all in the spec's audit section): the five-harness/two-flattener test consolidation (touches every gametest file — not worth destabilising the suite at the close of the port); sheep `EatBlockGoal` and silverfish infesting (entity-less block changes); arrow-pressed buttons; tripwire in the trigger vocabulary; fluid flow (§7 lists it, story 35 does not); the untestable `/switch` failure branch; end-to-end server-icon coverage.

**Honest limits.** "Verified by inspection" is listed separately from tested in the spec, and is not counted as coverage. The port's one genuinely unrehearsed step is the first real migration run against production data — the importer is unit-tested against fixtures and gametested against a migrated save, but the live dataset (and its identity resolution) has never been seen.
