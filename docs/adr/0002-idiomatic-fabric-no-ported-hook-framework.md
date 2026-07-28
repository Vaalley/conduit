# Idiomatic Fabric instead of porting the Portal's hook framework

The Portal's Feature/Module/hook architecture existed to abstract packet interception — a problem that disappears when the code runs inside the server. We decided the Fabric port does **not** recreate that framework: features are plain Kotlin modules registering Fabric events and Brigadier commands directly, and the deep modules are the domain services (Worlds/Travel, Regions, Persistence). Fabric's event bus *is* the hook system; CommandsInjectionModule's Brigadier-tree surgery becomes ordinary command registration.

## Consequences

- The Portal's structure is not mirrored file-for-file; parity is defined by behaviour (the feature inventory), not by architecture.
- The Portal's hook/module framework tests are not ported — only behaviour tests are.
