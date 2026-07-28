# Issue tracker: Local Markdown

Issues and specs (you may know a spec as a PRD) for this repo live as markdown files in `.scratch/`.

## GitHub Project mirror (tracking view only)

The local files are canonical. A read-only-ish mirror lives on the GitHub Project "Conduit" at https://github.com/users/Vaalley/projects/3 (draft items, one per ticket, titled `NN — <title>`). When a ticket's state changes locally, sync its board Status: unblocked → `Ready`, claimed → `In progress`, resolved/done → `Done`. IDs for `gh project item-edit`: project `PVT_kwHOA6M0Es4BeufK`, status field `PVTSSF_lAHOA6M0Es4BeufKzhZGiwo`, options Backlog `f75ad846`, Ready `e18bf179`, In progress `47fc9ee4`, In review `aba860b9`, Done `98236657`. Requires `gh` auth with the `project` scope (account Blazzike has write access). Items also carry the Iteration field (`PVTIF_lAHOA6M0Es4BeufKzhZGjIQ`, 14-day cycles) — when creating or rolling items, assign the current iteration (list iteration ids via the GraphQL `ProjectV2IterationField` configuration).

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01` — never a single combined tickets file
- Triage state is recorded as a `Status:` line near the top of each issue file (see `triage-labels.md` for the role strings)
- Comments and conversation history append to the bottom of the file under a `## Comments` heading

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` — the Notes / Decisions-so-far / Fog body.
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`); a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.
