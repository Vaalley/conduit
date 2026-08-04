# 16 — Fixing MCA Selector rather than working around it

**What to build:** A patched build of MCA Selector that relocates 26.2 chunks completely and
selects them deterministically, replacing the stock 2.8 jar the merge runs today.

Two defects have been found in the stock tool, and both are ours to fix now that no released
version has caught up. It is MIT licensed, so this is allowed; the patch is small, and the
same commits are worth offering upstream, which visibly takes fixes of exactly this kind.

**The selection races.** `Selection.merge` mutates a non-thread-safe fastutil
`Long2ObjectMap` from concurrent per-region-file jobs, so `--mode select` silently
under-selects — around 7% of runs lose an entire region file's worth of chunks. In
production that is player builds left behind with the merge reporting success. This is a
missing lock, not a design problem.

**The relocation is incomplete for 26.2.** Four kinds of coordinate arrive in Primary still
naming Secondary. The first three are 1.21.5's `InlineBlockPosFormatFix` renames the tool
never followed; the fourth is older still.

| what | 26.2 writes | the tool moves |
| --- | --- | --- |
| a leash tied to a fence | `leash`, an int array of three | `Leash`, a compound of `X`/`Y`/`Z` |
| an item frame's tile | `block_pos` | `TileX`/`TileY`/`TileZ` |
| a painting's tile | `block_pos` | `TileX`/`TileY`/`TileZ` |
| a villager's `home`, `job_site`, `meeting_point` | `{value:{dimension,pos}}` | reads `pos` off the memory itself |

**Both spellings must keep working.** This is the trap in the whole ticket: Secondary's
chunks are a *mix* of DataVersions, because vanilla upgrades a chunk only when it loads one,
so pre-cutover chunks nobody has visited still carry the old spelling while post-cutover ones
carry the new. The fixes are additive — handle the new form *as well as* the old — never
replacements.

Keeping the tool rather than reimplementing it is deliberate: its per-version relocation
chain is what copes with that mixture, and it is the part that would be most painful and most
dangerous to write ourselves.

**Where the build lives.** The patched jar is built locally and kept outside this repo, pinned
by path and checksum the way the stock artifact is pinned by URL and checksum today. That
costs reproducibility, so the patch itself — as a diff against the upstream tag — is kept
*in* this repo: a few kilobytes that make the fixes reviewable in our own history and reduce
recovering a lost jar to clone, apply, build.

**Supersedes ticket 15**, which routed around the selection race by computing the selection
ourselves. Fixing the race at source is smaller and keeps the tool's own version handling.
What ticket 15 would also have bought, and this does not, is a selected count derived
independently of the relocated count — the audit and the sampled diff are what cover that
now, and both are stronger evidence anyway.

**Blocked by:** None — tickets 02 and 03 are complete, and ticket 03's audit is the test.

**Status:** done — except the upstream offer, which needs a human

- [x] The selection is deterministic: repeated runs over the same save select the same chunks
      every time, demonstrated over enough runs to have caught a 7%-per-run defect
- [x] A leash, an item frame, a painting and a villager's memories all arrive in Primary
      naming their relocated positions
- [x] Chunks written in the *older* spelling still relocate correctly, so a Secondary chunk
      nobody has visited since before the Portal cutover is not left behind
- [x] Ticket 03's fixture of one-of-every-coordinate-bearing-thing passes the audit after a
      real relocation — that acceptance criterion was left unmet for this ticket to close
- [x] The named tests in `WorldMergeAuditTest` that pin each of the four fields now pass
      rather than asserting a refusal
- [x] The build resolves the patched jar by path and verifies its checksum, and fails with an
      instruction an operator can follow when it is missing
- [x] The patch is kept in this repo as a diff against the upstream tag it applies to
- [x] The runbook records how to rebuild the jar from that diff, and why it exists at all —
      written into ticket 12's Comments, because `docs/merge.md` is ticket 12's to create and
      does not exist yet
- [ ] The fixes are offered upstream, with attribution to this repo's finding — **not done,
      and not an agent's to do**: opening a pull request against somebody else's repository is
      a public action that needs a person. The patch is `gradle/mcaselector/2.8-mctraveler1.patch`
      and applies cleanly to the 2.8 tag; the three fixes are independent and worth offering as
      separate commits.

## Comments

### What the patch changes, and why every hunk is additive

`gradle/mcaselector/2.8-mctraveler1.patch`, against tag 2.8 (`11723cff`). Four source files.

**`selection/Selection.java` — the race.** `merge` is now `synchronized`, and so are
`saveToFile` and `saveToString`. The lock on `merge` is the fix; the lock on the two writers
is the *visibility* half of it, and it is not decoration. The CLI writes the selection from
whichever worker thread happens to finish last, which is not necessarily the thread that
merged last, so without acquiring the same monitor after every merge released it there is no
happens-before edge and the writer can legally see a stale map. Nothing locks two selections
at once — `merge` reads `other`, which is a selection the calling job just built for itself —
so there is no deadlock to have.

**`version/java_1_21/ChunkFilter_25w15a.java` — a null static, and this was the real cause of
the villager defect.** `RelocateEntities` dereferences `Relocate.instance` for *every* entity
it relocates, and that field was never assigned: `VersionHandler` only instantiates classes it
registers, and this `Relocate` carries no `@MCVersionImplementation`. So every entity threw
NPE partway through `applyOffsetToEntity`, the per-entity `catchAndLog` swallowed it, and the
relocation reported success. What survived was whatever ran before the throw — `Pos`, `Leash`,
the tile positions — and what did not was villager memories, falling blocks' tile entity data,
carried items, and the UUID re-roll. Fixed by constructing it (`static Relocate instance = new
Relocate()`). **Registering it instead would have been a disaster**: it extends a 1.17-era
`Relocate`, so annotating it would also make it the *terrain* implementation from DataVersion
4422 onward, ahead of `ChunkFilter_24w10a.Relocate`.

**`version/java_1_14/ChunkFilter_19w11a.java` — the villager memory wrapper.**
`applyOffsetToVillagerMemory` now offsets the `pos` on the memory *and* the `pos` inside its
`value`. Additive both ways, and placed at the base of the chain rather than at 26.2 on
purpose: every later `Relocate` reaches this one through `super`, so a chunk at any
DataVersion gets both shapes. `ExpirableValue` has wrapped memories for many versions, so the
defect was never 26.2-only.

**`version/java_1_18/ChunkFilter_21w37a.java` and `…/ChunkFilter_25w15a.java` — the inline
block positions.** `leash` (int array of three) is offset beside the existing `Leash` compound,
and `block_pos` beside the existing `TileX`/`TileY`/`TileZ`, in both entity relocators. Two
copies because `applyOffsetToEntity` is copy-pasted per version rather than chained; between
them they cover DataVersion 2834 upward, which is every version that can contain the 1.21.5
spellings at all. `Helper.applyOffsetToIntArrayPos` only acts on an array of exactly three, so
a `leash` that names another *mob* — a uuid, four ints — is correctly left alone. Verified.

**`build.gradle` — reproducibility.** `preserveFileTimestamps = false` and
`reproducibleFileOrder = true` on `shadowJar`. Without them the pinned checksum would be a
property of the moment the jar was built rather than of the source, and "rebuild it from the
patch" would produce a jar the build rejects. Two clean builds now produce identical bytes.

### The evidence

**The race.** `McaSelectorSelectionTest` builds three finished chunks across two region files
plus a frontier chunk, and asks for the selection repeatedly. Patched, at the tool's own
thread count: **800 selections, all identical** (400 runs × two tests). Stock 2.8, same test,
same fixture: **7 of 200 runs lost region file (1, 0) entirely** — never a chunk or two, always
the whole file, always exit 0. That control is what makes the 800 mean something. At the
measured rate, 800 clean runs by luck is about one chance in 10^14.

`McaSelector.select` no longer passes `--process-threads 1`. That stopgap was landed on
`secondary-merge` while this was in flight and is explicitly this ticket's to remove; leaving
it would have meant shipping a fix nothing exercises.

**The relocation.** `WorldMergeAuditTest` is the specification and now has 15 tests. The two
that asserted a refusal assert arrival instead, and both make the additive claim rather than
just the new-spelling one: the fixture carries `Leash` *and* `leash`, `TileX/Y/Z` *and*
`block_pos` on the same entities at once, and both land at the same merged coordinates. A
third test was added for a villager whose memories are written flat, without `ExpirableValue`'s
`value` wrapper, because that shape is still on disk in chunks nobody has loaded.

### Judgement calls

1. **The four fields were fixed; a fifth was found and deliberately left.** A bee's `hive_pos`
   and `flower_pos` are not relocated either — the tool moves entity positions from a list of
   entity types, and a bee is not on it. It is the same shape of defect, but fixing it is
   open-ended (there is no reason to think bees are the last), and this ticket's scope is the
   four the audit found. It is now what `one structural coordinate left behind fails the whole
   merge and writes nothing` leaves stale, so it is pinned by a test rather than only written
   down. **A rehearsal must find out whether Secondary has bees in relocated chunks**: if it
   does, the merge refuses and someone has to widen this patch first.

   *Closed by ticket 17, which took the instinct behind "there is no reason to think bees are
   the last" seriously and enumerated the rest from Minecraft 26.2's own entity classes rather
   than waiting to run into them. Bees were not close to last: `sleeping_pos` on anything that
   sleeps, `home_pos` on any restricted mob, a phantom's anchor, a vex's bound origin, an end
   crystal's beam target, a wandering trader's and every patrolling raider's target, and a
   glow item frame's and a leash knot's tile — the last two never handled in either spelling.
   The patch now keys the inlined positions by NBT name instead of by entity id, which is what
   stops the list needing to be complete. Ticket 17 also added a completion pass that finishes
   whatever the tool still misses and reports it, so a field nobody has found yet no longer
   refuses a merge in the downtime window. The stale coordinate that test leaves behind is now
   an end gateway's `exit_portal`.*
2. **Entity UUIDs are now re-rolled, and one test changed to say so.** `Helper.fixEntityUUID`
   randomises a relocated entity's UUID by design, so an imported chunk cannot collide with an
   entity the target world already holds. It had not been running, because the null static
   above aborted every entity before reaching it. `a velocity and a uuid are not mistaken for
   the places they would sit inside` asserted the cow's UUID was still `(1, 2, 3, 4)`; it now
   asserts what that test was actually about — the UUID is four ints, is not a place, and did
   not get the offset applied to it. **This is the one test whose claim changed for a reason
   other than the four fields**, and it is a behaviour restoration rather than a regression.
   The one real consequence is that a leash tied to another *mob* names a UUID that is now
   re-rolled, so mob-to-mob leashes do not survive a relocation — which was already true for
   every DataVersion below 4422 and is upstream's own long-standing trade.
3. **The stock jar is no longer resolvable at all.** The Ivy repository over GitHub releases,
   the `mcaSelector` configuration and the dependency are gone rather than kept as a fallback.
   A fallback here would be a way to silently run the defective tool, and the whole point of
   this ticket is that the defective tool cannot be allowed to run.
4. **The jar is pinned by path, not fetched.** It is 18 MB of somebody else's build output and
   there is nowhere trustworthy to host it. The cost is that a new machine has to build it
   once; the mitigation is that the build's failure message is the complete procedure, so the
   operator never has to find this file.
