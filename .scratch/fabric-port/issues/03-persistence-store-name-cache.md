# 03 — Persistence store + name cache

**What to build:** The Persistence service: per-player JSON storage in the Portal's format (unknown/legacy fields like balance and geoLocation survive round-trips untouched), behind a small store interface, plus a real uuid→name cache updated at every login (fixing the Portal's op-only cache).

**Blocked by:** 01 (Scaffold).

**Status:** ready-for-agent

See `../spec.md` (Implementation Decisions: Persistence) and the persistence-layer section of `docs/research/portal-feature-inventory.md` for the exact schema.

- [ ] Store interface with a flat-JSON implementation: per-player file keyed by uuid holding lastWorld, notepad pages, admin flag — schema-compatible with the Portal's player files
- [ ] Unknown fields in existing player files are preserved byte-for-byte through read/modify/write
- [ ] Name cache records uuid→username at login and answers lookups for offline players
- [ ] Unit tests: round-trip, legacy-field preservation, name-cache behaviour
