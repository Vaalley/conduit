# 18 — Importer (one-time migration)

**What to build:** The rehearsable cutover tool: a Gradle-invocable one-shot importer that turns the Portal deployment (two backend server directories + the Portal's data files) into a ready single-server save — worlds, players, ops, regions, notepads, and routing all carried over.

**Blocked by:** 03 (Persistence store), 04 (Worlds), 05 (Respawn), 12 (Region core), 16 (Admin + remaps).

**Status:** ready-for-agent

See `../spec.md` (User Stories 43–44, Implementation Decisions: Importer) and the persistence-layer + identity sections of `docs/research/portal-feature-inventory.md` for schemas, the offline-UUID scheme, and the remap table.

- [ ] Both backend worlds import as the Primary and Secondary trios (nether/end included); vanilla's own upgrade handles the version jump on first boot
- [ ] Playerdata re-keys offline UUIDs to Mojang UUIDs, honoring the two identity remaps
- [ ] Each player's live state comes from their last World's playerdata; the other World's position, rotation, dimension, and respawn tags seed that World's Per-World Bucket
- [ ] Portal player files import (notepad, lastServer becomes lastWorld) with unknown legacy fields — including the Portal's isAdmin — preserved untouched; the uuid cache seeds the name cache
- [ ] Backend ops entries re-key to Mojang UUIDs and land in the real ops list (vanilla operator status is the only admin mechanism)
- [ ] Regions import with world-name strings mapped to the new dimension identities
- [ ] Re-running against an already-migrated save is refused safely (idempotent cutover rehearsal)
- [ ] Unit tests against fixture files for every transform; a gametest boots the migrated save and spot-checks a player and a region
