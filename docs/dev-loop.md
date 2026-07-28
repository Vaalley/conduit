# Dev loop

How to iterate on the mod in seconds, not minutes. Versions and cited sources live in
`docs/research/fabric-kotlin-platform.md`.

## Prerequisites

- **JDK 25** — required by Minecraft 26.2 and the Gradle toolchain. Any JDK 25 works for
  building (e.g. `brew install openjdk@25`); point `JAVA_HOME` at it.
- **JetBrains Runtime (JBR) 25** — optional but recommended for the hot-reload loop
  (enhanced class redefinition). Download a `jbr-25.x-osx-aarch64` build from
  [JetBrainsRuntime releases](https://github.com/JetBrains/JetBrainsRuntime/releases),
  or use the JBR bundled with IntelliJ IDEA 2025.3+.

## The commands

| Command | What it does |
|---|---|
| `./gradlew build` | Full build: compiles, runs the unit tier (fabric-loader-junit), then the headless in-server gametest suite (`runGameTest`). A red gametest fails the build. |
| `./gradlew test` | Unit tier only — fastest feedback for pure logic. |
| `./gradlew runGameTest` | Headless gametest server only. |
| `./gradlew runServer` | Interactive dev dedicated server on `localhost` (run dir `run/`; the EULA is auto-accepted by the `acceptDevServerEula` task). Connect with a vanilla client. |
| `./gradlew prodServer` | Production smoke: boots the *built jar* via the real Fabric server launcher (run dir `run/prod-smoke/`), verifies the mod initialized on a real dedicated server, and stops it cleanly. |

## Hot reload (edit → running server, no restart)

The dev server hot-swaps code when it runs on **JetBrains Runtime** with enhanced class
redefinition, plus the **mixin hotswap agent**. Two ways to wire it:

### From the CLI

```sh
./gradlew runServer -Pmctraveler.devJbr=/path/to/jbr-25/Contents/Home
```

This makes the `runServer` task launch on the JBR with
`-XX:+AllowEnhancedClassRedefinition` and `-javaagent:<sponge-mixin jar>` (the agent jar
is resolved from the runtime classpath automatically). Put
`mctraveler.devJbr=...` in `~/.gradle/gradle.properties` to make it sticky.

Then attach a remote debugger (or run the Gradle task in debug mode from the IDE) and use
"Reload Changed Classes" after each edit.

### From IntelliJ (recommended)

1. Import the project; Loom generates a **Minecraft Server** run configuration.
2. In the run configuration, set the JRE to the JetBrains Runtime (25).
3. Add VM options:
   - `-XX:+AllowEnhancedClassRedefinition`
   - `-javaagent:<path to the sponge-mixin jar under External Libraries>`
4. **Debug** (not plain Run) the configuration.
5. After editing Kotlin code: Build → "Reload Changed Classes"
   (or enable "Update classes and resources" on frame deactivation).

What hot-swaps and what doesn't:

- **Method bodies** — always (plain hotswap tier), including mixin method bodies thanks to
  the mixin agent.
- **Adding/removing methods, fields, classes, lambdas** — works on JBR via enhanced class
  redefinition; plain OpenJDK refuses these.
- **New mixin injections/targets, fabric.mod.json or registration changes** — restart the
  server. Registrations run once at init, so a redefined method body takes effect but a
  *new* event registration does not.

## Why incremental builds stay seconds-scale

- The 26.1+ toolchain has **no remap step** (`jar` replaces `remapJar`) — the historically
  slow part of Fabric builds is gone.
- `gradle.properties` enables the **configuration cache**, **build cache**, **parallel
  execution**, **VFS watching**, and **Kotlin incremental compilation** (K2 daemon).
- Measured on this scaffold: clean first build is dominated by one-time Minecraft/Loom
  setup; a warm incremental `build` (edit one Kotlin file, full headless gametest run
  included) is ~11 s, `test` after an edit is ~6 s, and a no-op `test` is under a second.

Treat regressions of the warm loop as build bugs — profile with `./gradlew build --profile`.
