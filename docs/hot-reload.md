# Hot reload: the bootstrap / runtime split

The mod ships as two jars:

| Jar | Gradle module | Where it goes | Reloadable |
|---|---|---|---|
| `mctraveler-<version>.jar` | `:bootstrap` | `mods/` | No — restart required |
| `mctraveler-runtime-<version>.jar` | `:runtime` | `<server dir>/mctraveler-runtime/` | **Yes — swapped live** |

The bootstrap is the only Fabric mod. It contains what *cannot* be reloaded —
the mixins (woven into Minecraft classes at startup) and the datapack resources
(recipes, the embassies dimension, the chat type) — plus the machinery that
side-loads everything else:

- **`RuntimeHost`** watches the runtime directory. When a jar there is added or
  replaced, it unloads the current runtime completely and loads the new one, on
  the server thread. When the swap finishes it broadcasts
  *"A new update has been applied."* to all players. No commands involved;
  dropping a new jar in the directory is the whole deploy.
- **`MixinHooks` / `Hooks`** is the one-way bridge the mixins call. While no
  runtime is loaded every hook answers with vanilla-neutral behaviour, so a
  half-second reload window (or a missing runtime jar) degrades to vanilla
  instead of crashing.
- **`EventRegistrar`** is the only way runtime code touches Fabric events.
  Fabric has no event unregistration, so every handler is wrapped in a
  severable guard whose class belongs to the bootstrap: on unload the guard
  drops the handler reference (the runtime's classloader becomes collectable)
  and answers later dispatches with neutral defaults (`true` for allow-style
  booleans, `PASS` for interaction results). Runtime code uses the
  `Event<T>.listen { }` helper in `Conduit.kt`; direct `Event.register` calls
  are forbidden in the runtime module.

## What a reload does

Unload (the old runtime is *fully* gone before the new one loads):

1. `SERVER_STOPPING` + `SERVER_STOPPED` are replayed to the old runtime's
   handlers — features flush persistence and stop their own threads (the HTTP
   API stops its listener here) exactly as on a real shutdown.
2. Its command roots are removed from the live Brigadier dispatcher and the
   command tree is resent to every player.
3. `Hooks.impl` is cleared, every event guard is severed, the runtime's
   `URLClassLoader` is closed and its temp jar copy deleted.

Load:

1. The jar is copied to a temp file (so the watched file stays overwritable),
   loaded in a fresh classloader child of the bootstrap's, and its
   `ConduitRuntime` service is started; it registers features and returns the
   mixin hooks.
2. `SERVER_STARTING` + `SERVER_STARTED` are replayed — features see a normal
   server start (persistence reopens, the HTTP API rebinds).
3. Command registrars run against the live dispatcher; the tree is resent.
4. `onHotActivate` actions run — the hook for features that track per-player
   state keyed by JOIN, because **connection JOIN/DISCONNECT events are
   deliberately not replayed** (they carry player-visible side effects such as
   join announcements). Chat marks online players as already announced; the
   tab list resends its header; the name cache records everyone online.
5. Players are told an update has been applied.

## Dev runs vs production

`runServer` and `runGameTest` put the runtime module on the classpath, so the
bootstrap's ServiceLoader probe finds it on its own classloader and loads it
directly — no watcher, no child classloader. Gametests can therefore keep
reaching into feature internals (`MCTraveler.persistence` etc.); iteration in
dev stays the JBR hotswap loop (docs/dev-loop.md).

`prodServer` stages the remapped runtime jar into
`run/prod-smoke/mctraveler-runtime/` and boots the real launcher, so the smoke
check proves the production side-loading path: the smoke hook asserts
`RuntimeHost.isRuntimeActive()`.

The watched directory defaults to `<server dir>/mctraveler-runtime/` and can be
overridden with `-Dmctraveler.runtimeDir=<path>`.

## Deploying to production

1. `./gradlew :runtime:build`
2. Copy `runtime/build/libs/mctraveler-runtime-<version>.jar` into the server's
   `mctraveler-runtime/` directory (replace the old jar; a partial copy is
   tolerated — the watcher waits for the file size to settle).
3. That's it. The server logs the swap and players see the announcement.

Changes to the *bootstrap* (mixins, bridge signatures, datapack resources)
still need a server restart — keep the bridge surface stable and additive where
possible. A runtime jar built against a newer bridge than the running bootstrap
fails to start and is logged; the server keeps running vanilla-neutral until a
matching jar (or bootstrap restart) arrives.
