# 03 — Persistence store + name cache

**What to build:** The Persistence service: per-player JSON storage in the Portal's format (unknown/legacy fields like balance and geoLocation survive round-trips untouched), behind a small store interface, plus a real uuid→name cache updated at every login (fixing the Portal's op-only cache).

**Scope change (from the orchestrator, mid-implementation):** the store keeps no typed admin flag — admin status is vanilla operator status (ticket 16). The Portal's `isAdmin` field is treated as just another preserved legacy field; the typed surface is lastWorld + notepad pages, plus the name cache.

**Blocked by:** 01 (Scaffold).

**Status:** done

See `../spec.md` (Implementation Decisions: Persistence) and the persistence-layer section of `docs/research/portal-feature-inventory.md` for the exact schema.

- [x] Store interface with a flat-JSON implementation: per-player file keyed by uuid holding lastWorld and notepad pages (no typed admin flag, per the scope change above) — schema-compatible with the Portal's player files
- [x] Unknown fields in existing player files are preserved byte-for-byte through read/modify/write
- [x] Name cache records uuid→username at login and answers lookups for offline players
- [x] Unit tests: round-trip, legacy-field preservation, name-cache behaviour

## Comments

Key decisions, for later tickets (04 Worlds, 11 Notepad, 16 Admin, 18 Importer):

- **Interface surface** (`eu.mctraveler.persistence`): `PlayerStore` with `lastWorld(uuid): String?` / `setLastWorld(uuid, world)` and `notepadPages(uuid): List<String>?` / `setNotepadPages(uuid, pages)`; `NameCache` with `record(uuid, username)` / `usernameFor(uuid): String?`; both reachable via `MCTraveler.persistence` (`PersistenceService.players` / `.names`), created at `SERVER_STARTING`.
- **Schema mapping**: `lastWorld` is stored as the Portal's `lastServer` field with the Portal's values `"primary"`/`"secondary"`; mapping World ids to dimension ids is deliberately left to the Worlds service (04). `notepad` keeps its name and string-array shape. `notepadPages` returns null (not empty) for a player who never saved, so the Notepad feature (11) can seed the welcome page. `isAdmin` is untyped legacy data per the scope change; ticket 16 uses the real ops list.
- **File layout on disk**: `<server run dir>/mctraveler/players/<uuid>.json` (dashed lowercase uuid) and `<server run dir>/mctraveler/uuid-cache.json`, both in the Portal's exact file formats — the importer (18) copies Portal data into this layout as-is and seeds the cache via `record` (uuid keys must be normalized to dashed lowercase, `UUID.toString()` form).
- **No JSON library**: byte-for-byte preservation ruled out tree parsers — kotlinx-serialization normalizes number literals (`1234.50` → `1234.5`) and string escapes on re-serialization. `PortalJson` (internal) instead splits the top-level object into raw text slices and re-emits untouched fields verbatim; only mod-owned fields are re-encoded. Consequently no dependency was added and build.gradle.kts is untouched. Precise guarantee: each preserved field's key and value bytes are verbatim; top-level whitespace *between* fields is compacted (the Portal wrote compact `JSON.stringify` output, so real files are unaffected).
- **Safety over tolerance**: a file that fails to parse (truncated, malformed, duplicate keys) makes reads and writes throw `IllegalArgumentException` — the store never overwrites data it could not read.
- **Login wiring**: `ServerPlayConnectionEvents.JOIN` records uuid→name on every login (the fix for the Portal's op-only cache, deviation register #10). The event lambda itself is two lines of glue; a gametest (`PersistenceGameTest`) verifies the service comes up with the server rooted under the run directory, since fake gametest players don't traverse the real connection path.
- Review findings addressed: duplicate-key files rejected instead of silently collapsed; escape-preservation and never-overwrite-corrupt-file tests added; lifecycle KDoc corrected.
