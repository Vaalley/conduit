# 05 — Sign effect policy and first configuration file

**What to build:** Add the first sign-effect policy file now at
`mctraveler/signs.json`, read through the existing `PersistenceService`
directory. It should tune the balance of effects that are open to everyone by
default, define the per-line component cap, and define the animation budget.
The click-action tag is the security exception: it remains admin-only and off
by default, rather than being an ordinary balance setting. Teach
`GameTestJanitor` to clean the new file up between test runs.

**Blocked by:** 02.

**Status:** needs-triage

See ../spec.md (User Stories 13 and 15, plus 18 for the animation budget;
Implementation Decisions "Who may use what", "Configuration", and "Cost cap";
Testing Decisions on `GameTestJanitor`).

- [ ] `mctraveler/signs.json` is loaded from the server's `mctraveler` directory
      through `PersistenceService` and has stable defaults
- [ ] Every ordinary effect class is available to everyone by default, with the
      file providing balance controls rather than a default admin-only split
- [ ] The click-action policy is separately represented as admin-only and
      disabled by default because it is a security boundary
- [ ] Configuration includes the per-line styled-component cap, defaulting to
      the documented 96-piece limit
- [ ] Configuration includes the animation budget needed by ticket 06
- [ ] Missing, malformed, and out-of-range configuration values follow a
      documented safe policy and do not silently disable protection
- [ ] Policy checks are available to the parser, sign update path, commands,
      and animator without duplicating their defaults
- [ ] `/signfx reload` reloads the file without requiring a server restart
- [ ] `GameTestJanitor` removes or resets `mctraveler/signs.json` so tests do
      not share policy state
- [ ] GameTests cover defaults, ordinary effects open to everyone, the
      click-action security exception, the component cap, reload, and malformed
      configuration
