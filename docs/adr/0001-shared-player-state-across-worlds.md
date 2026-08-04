# Shared player state across Worlds

> **Superseded by ADR 0004 (one World: the Worlds are retired from the server).** Secondary has
> been merged into Primary, so there is one World and nothing to share state *across*. The
> decision below is kept as the record of why the port divided state the way it did, and of what
> the merge inherited. Note that the Per-World Bucket was retired from the running server rather
> than deleted: it survives as legacy data in player records, and the migration tools still read
> it.

The Portal ran each World as a separate backend server, so every piece of player state (inventory, XP, ender chest, advancements, stats, position) was per-World by accident of topology. Rebuilding as a single Fabric server, we decided player state is **shared across Worlds, except position**: each World keeps a Position Memory restored on Travel, and everything else is one pool. This is a deliberate deviation from strict feature parity — full per-World separation would have been the port's most complex subsystem, and the topology simplification was the point of the port.

## Consequences

- Items and XP can move freely between Worlds, which was previously impossible — a real gameplay/economy change, accepted knowingly.
- Retrofitting per-World separation later would be costly (state snapshotting/swapping on Travel); this choice is effectively load-bearing.
