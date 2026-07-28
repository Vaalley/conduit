# Shared player state across Worlds

The Portal ran each World as a separate backend server, so every piece of player state (inventory, XP, ender chest, advancements, stats, position) was per-World by accident of topology. Rebuilding as a single Fabric server, we decided player state is **shared across Worlds, except position**: each World keeps a Position Memory restored on Travel, and everything else is one pool. This is a deliberate deviation from strict feature parity — full per-World separation would have been the port's most complex subsystem, and the topology simplification was the point of the port.

## Consequences

- Items and XP can move freely between Worlds, which was previously impossible — a real gameplay/economy change, accepted knowingly.
- Retrofitting per-World separation later would be costly (state snapshotting/swapping on Travel); this choice is effectively load-bearing.
