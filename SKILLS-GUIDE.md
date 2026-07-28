# How to use the skills in this repo

This repo is set up with the 22 official skills from [mattpocock/skills](https://github.com/mattpocock/skills), installed to `.claude/skills/` for Claude Code. The one-time setup (`/setup-matt-pocock-skills`) has already been applied:

- **Issue tracker**: local markdown files under `.scratch/<feature-slug>/` — no GitHub/Jira/Linear needed
- **Triage labels**: the five defaults (`docs/agents/triage-labels.md`)
- **Domain docs**: single-context — `CONTEXT.md` + `docs/adr/` at the root, created lazily by the skills as decisions get made

You don't need to re-run setup unless you want to switch issue trackers.

## Models and billing

Everything runs on the Claude Code **subscription** — log in with `/login` using the Claude account. Never set `ANTHROPIC_API_KEY` for this repo (that would bill the API instead; `.claude/settings.json` forces subscription login).

- **Orchestrator** (your main session): Fable 5 — set via `"model": "claude-fable-5"` in `.claude/settings.json`, so just running `claude` here picks it up.
- **Subagents** (code review, research, parallel exploration): Opus — set via `CLAUDE_CODE_SUBAGENT_MODEL=claude-opus-5` in the same file.
- **Sonnet** only for genuinely mechanical subagent work (search, enumeration, formulaic edits). To pin a specific agent to Sonnet, create it under `.claude/agents/<name>.md` with `model: sonnet` in its frontmatter.

Fable + Opus subagents burn subscription quota quickly. If you hit rate limits mid-sprint, drop `CLAUDE_CODE_SUBAGENT_MODEL` to `claude-sonnet-5` for the mechanical phases and keep Opus for review.

## The main flow: idea → ship

Run each step in **one session**, in this order:

1. **`/grill-with-docs <your idea>`** — a rough one-liner is enough. It explores the code, then interviews you (often 5–20 questions) until you both reach a shared understanding and it lays out a plan. It records terminology in `CONTEXT.md` and decisions in `docs/adr/` as it goes.
2. **Fork in the road:**
   - Work fits in the remaining context window (the "smart zone" ends around ~140k tokens — check `/context`)? Say **`implement this`** and let it run. Done.
   - Bigger than one session? Continue:
3. **`/to-spec`** — compresses the whole grilling conversation into `.scratch/<feature>/spec.md`. The spec is the *destination*.
4. **`/to-tickets`** — breaks the spec into tickets at `.scratch/<feature>/issues/NN-<slug>.md`. Each ticket = one session's worth of work. Push back if it over- or under-slices ("do it in one slice instead").
5. **`/clear`**, then per ticket: **`implement @.scratch/<feature>/issues/01-….md`**. The implement skill runs verification (build, type check, tests) and then `/code-review` in fresh subagents, checking the work against both the **spec** and the repo's **coding standards**. `/clear` between every ticket.
6. After the last ticket, run a final **`/code-review`** pass against the spec to catch anything a ticket dropped.

Rules of thumb:

- Don't say "do every ticket" — one ticket per session, clear in between.
- If a grilling question needs a *runnable* answer rather than a conversational one, use `/prototype` (bridged with `/handoff`).
- Lost or unsure what to do next? **`/ask-matt <question>`** — a router over all the skills.

## Phase 1: the platform conversion

The port of the existing codebase is a classic multi-session effort — use the full flow, not direct implement:

1. Make the old codebase readable from here (clone it as a sibling directory, or drop a copy under `reference/` — gitignore it if you do).
2. `/grill-with-docs I want to port <old codebase at path> to this platform…` — let it map the old code and grill you on scope: what carries over, what gets dropped, what gets redesigned. This is where `CONTEXT.md` gets seeded with the domain language.
3. If the *route* itself is still foggy (unknowns about platform APIs, undecided architecture), use **`/wayfinder`** first — it maps the unknowns as decision tickets (`research` / `prototype` / `grilling`) and resolves them one at a time until the way is clear.
4. `/to-spec` → `/to-tickets` — expect a real spec with many tickets (one subsystem or vertical slice per ticket).
5. Implement ticket-by-ticket with `/clear` between each; final `/code-review` against the spec at the end.

## Phase 2: pulling in and adding features

Same loop, usually smaller. For each feature: `/grill-with-docs <feature idea>` → small: `implement this`; large: `/to-spec` → `/to-tickets` → ticket-by-ticket. By now `CONTEXT.md` and the ADRs exist, so grilling sessions get faster and reviews get stricter — keep using the glossary's vocabulary.

## Support skills, at a glance

| Skill | Use when |
| --- | --- |
| `/ask-matt` | Unsure which skill or flow fits |
| `/grill-me`, `/grilling` | Stress-test thinking without touching docs |
| `/prototype` | A design question needs a runnable answer |
| `/research` | Delegate reading (platform docs, API facts) to a background agent |
| `/tdd` | Build a feature or fix test-first |
| `/diagnosing-bugs` | Something is broken/slow and the cause isn't obvious |
| `/code-review` | Review changes since a commit/branch against spec + standards |
| `/triage` | Move `.scratch/` issues through the triage states |
| `/wayfinder` | Work too big/foggy to even spec yet |
| `/handoff` | Compact this session for another agent to continue |
| `/resolving-merge-conflicts` | Mid-merge/rebase conflict |
| `/improve-codebase-architecture` | Periodic architecture health pass |

## Where things live

- `.claude/skills/` — the installed skills (update with `npx skills update`)
- `.claude/settings.json` — model + login configuration
- `.scratch/<feature>/` — specs and tickets (the issue tracker)
- `CONTEXT.md`, `docs/adr/` — domain glossary and decisions (created lazily)
- `docs/agents/` — the skills' repo configuration
