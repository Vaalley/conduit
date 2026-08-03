# 05 — Sign effect policy and first configuration file

**What to build:** Add the first sign-effect policy file at
`mctraveler/signs.json`, read through the existing `PersistenceService`
directory. It should define effect classes for everyone versus admins, the
per-line component cap, and the animation budget. The triage question must
remain explicit: decide whether this file is accepted now or whether policy
stays hard-coded until a second feature needs configuration. If the file lands,
teach `GameTestJanitor` to clean it up between test runs.

**Blocked by:** 02.

**Status:** needs-triage

See ../spec.md (User Stories 13 and 15, plus 18 for the animation budget;
Implementation Decisions "Who may use what", "Configuration", and "Cost cap";
Testing Decisions on `GameTestJanitor`).

- [ ] Triage records whether `mctraveler/signs.json` is accepted now or policy
      remains hard-coded until a second configurable feature
- [ ] If accepted, the file is loaded from the server's `mctraveler` directory
      through `PersistenceService` and has stable defaults
- [ ] Configuration distinguishes effect classes available to everyone from
      those restricted to vanilla admins
- [ ] Configuration includes the per-line styled-component cap, defaulting to
      the documented 96-piece limit
- [ ] Configuration includes the animation budget needed by ticket 06
- [ ] Missing, malformed, and out-of-range configuration values follow a
      documented safe policy and do not silently disable protection
- [ ] Policy checks are available to the parser, sign update path, commands,
      and animator without duplicating their defaults
- [ ] `/signfx reload` can reload the file when the file-based decision lands
- [ ] `GameTestJanitor` removes or resets `mctraveler/signs.json` so tests do
      not share policy state
- [ ] GameTests cover defaults, effect gating, the component cap, reload, and
      malformed configuration
