# mctraveler-fabric

Target repo for porting the existing MCTraveler codebase to a new platform, then extending it with new features. See `SKILLS-GUIDE.md` for the development workflow.

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at the repo root plus `docs/adr/`. See `docs/agents/domain.md`.

## Models

- The main (orchestrator) session runs on **Fable 5** (`claude-fable-5`) — configured in `.claude/settings.json`.
- Subagents default to **Opus** (`claude-opus-5`) via `CLAUDE_CODE_SUBAGENT_MODEL` in `.claude/settings.json`.
- Sonnet (`claude-sonnet-5`) is acceptable for a subagent only when its task is simple and mechanical (file enumeration, straightforward search, formulaic edits). Anything requiring judgment — review, design, non-trivial implementation — stays on Opus.
- This repo is worked on exclusively through a Claude Code subscription. Never set `ANTHROPIC_API_KEY` here; log in with the Claude account instead.
