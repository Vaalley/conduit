# Fabric + Kotlin platform research (July 2026)

Research for porting the MCTraveler TypeScript proxy (protocol 1.21.x) to a server-side-only
Fabric mod written in Kotlin. All findings verified against primary sources on 2026-07-28.
Requirements evaluated: fast builds, hot reload, automated tests, multiple overworld-style
dimensions on one dedicated server with cross-world travel and (optionally) per-world player state.

---

## 1. Current versions and compatibility (as of 2026-07-28)

### The big landscape change: Minecraft is no longer obfuscated

Minecraft moved off `1.21.x` version numbers in early 2026. The stable line per Mojang's own
version manifest ([piston-meta](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json))
is now: `… 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2` — with **26.2 (released 2026-06-16) as
the latest stable release** and `26.3-snapshot-6` (2026-07-28) as the newest snapshot. Fabric's
own meta API ([meta.fabricmc.net/v2/versions/game](https://meta.fabricmc.net/v2/versions/game))
lists `26.2` as the latest stable game version Fabric supports.

Per the Fabric announcement ["Fabric for Minecraft 26.1"](https://fabricmc.net/2026/03/14/261.html):

- **26.1 is the first unobfuscated Minecraft release.** Fabric stopped maintaining Yarn and
  Intermediary after 1.21.11; mods for 26.1+ compile directly against Mojang's official names
  ("Yarn is no longer officially supported by Fabric"). The Fabric meta yarn endpoint confirms
  the newest Yarn build is for `1.21.11` ([meta.fabricmc.net/v2/versions/yarn](https://meta.fabricmc.net/v2/versions/yarn));
  a query for `26.2` returns empty. See also the
  [1.21.11 announcement](https://fabricmc.net/2025/12/05/12111.html) and
  [Migrating Mappings](https://docs.fabricmc.net/develop/porting/mappings/).
- **No remapping in the toolchain anymore** for 26.1+: the new Gradle plugin id is
  `net.fabricmc.fabric-loom`, `modImplementation` becomes plain `implementation`, and `remapJar`
  becomes `jar`. This removes an entire (slow) build step.
- "No mods from 1.21.11 or before will work without, at minimum, recompilation."

### Version matrix (verified 2026-07-28)

| Component | Version | Source |
|---|---|---|
| Minecraft (latest stable) | **26.2** (26.3 in snapshots) | [piston-meta manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json), [Fabric meta](https://meta.fabricmc.net/v2/versions/game) |
| Java runtime required by MC 26.2 | **Java 25** (`javaVersion.majorVersion: 25`) | [Mojang 26.2 version JSON](https://piston-meta.mojang.com/v1/packages/3457237902814cca3f5c6f20b0c5db1b1f341512/26.2.json) |
| Fabric Loader | **0.19.3** (stable) | [meta.fabricmc.net/v2/versions/loader](https://meta.fabricmc.net/v2/versions/loader) |
| Fabric API | **0.156.0+26.2** for MC 26.2 (0.156.1+26.3 for snapshots) | [maven.fabricmc.net fabric-api metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml) |
| Fabric Loom | **1.17.x** stable line (1.17.17 latest; 1.18 alphas in progress); 26.1+ requires Loom >= 1.15 | [maven.fabricmc.net loom metadata](https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml), [26.1 announcement](https://fabricmc.net/2026/03/14/261.html) |
| Gradle | **9.4.0+** required for the 26.1+ toolchain | [26.1 announcement](https://fabricmc.net/2026/03/14/261.html) |
| Gradle JVM / dev JDK | **Java 25** minimum | [26.1 announcement](https://fabricmc.net/2026/03/14/261.html); Fabric's own CI uses `java-version: 25` ([automatic-testing docs](https://docs.fabricmc.net/develop/automatic-testing)) |
| IntelliJ IDEA | **2025.3+** required (mixin compilation) | [26.1 announcement](https://fabricmc.net/2026/03/14/261.html) |
| fabric-language-kotlin | **1.13.13+kotlin.2.4.10** (released 2026-07-15) | [GitHub releases](https://github.com/FabricMC/fabric-language-kotlin/releases) |
| Kotlin (bundled by FLK) | **2.4.10** (+ kotlin-reflect 2.4.10) | [FLK README](https://github.com/FabricMC/fabric-language-kotlin) |
| kotlinx bundled by FLK | coroutines-core 1.11.0, serialization-core/json/cbor 1.11.0, atomicfu 0.33.0, kotlinx-datetime 0.8.0, kotlinx-io 0.9.1 | [FLK README](https://github.com/FabricMC/fabric-language-kotlin) |
| Mappings | **Mojang official mappings only** for 26.1+ (Yarn ended at 1.21.11) | [1.21.11 announcement](https://fabricmc.net/2025/12/05/12111.html) |

Real-world confirmation of this exact stack: [Thomilist/dimensional-inventories](https://github.com/Thomilist/dimensional-inventories)
(server-side Fabric mod, updated 2026-07) targets `minecraft_version=26.2`, `loader_version=0.19.3`,
`loom_version=1.17-SNAPSHOT`, `fabric_api_version=0.154.2+26.2`, `target_java_version=25`, plugin id
`net.fabricmc.fabric-loom` ([gradle.properties](https://github.com/Thomilist/dimensional-inventories/blob/develop/gradle.properties),
[build.gradle](https://github.com/Thomilist/dimensional-inventories/blob/develop/build.gradle)).

### Which Minecraft version should the port target?

- The existing proxy speaks **protocol 1.21.x**. The last 1.21-line release is **1.21.11**; it is
  also the last Yarn/Intermediary version. Targeting it means using the *old* toolchain (remapping,
  yarn or mojmap-via-loom) that Fabric has already left behind.
- **Recommendation: target 26.2** (current stable, unobfuscated, simpler+faster toolchain,
  actively supported by Fabric API / FLK / Fantasy). Vanilla 26.2 clients connect to a
  server-side-only mod with no client install (see section 2). Only pick 1.21.11 if there is a
  hard requirement to keep 1.21.x clients, and treat it as a dead-end branch — Fabric's porting
  docs make clear all future work happens on the mojmap toolchain
  ([26.1 announcement](https://fabricmc.net/2026/03/14/261.html)).

---

## 2. Server-side-only mod setup

### Sides model

Fabric distinguishes *physical* client/server from *logical* client/server. A dedicated server
contains only the logical server; a server-side-only mod touches only logical-server code, so
**unmodified vanilla clients can join a server running server-side-only mods** — which is exactly
the property needed to replace the proxy topology.
Source: [Fabric wiki, "Side"](https://wiki.fabricmc.net/tutorial:side).

The same wiki page lists the classic pitfalls: don't assume only one logical server exists
(integrated-server case), don't assume `isClient` is always false, and don't reference
client-only classes (missing on the physical server → `NoClassDefFoundError`). For a mod that
declares `"environment": "server"` these mostly disappear because the mod never loads on a
physical client at all.

### fabric.mod.json for a server-only Kotlin mod

Per the [fabric.mod.json reference](https://docs.fabricmc.net/develop/loader/fabric-mod-json):

- `"environment": "server"` — mod runs only "on the physical server side. If set, your mod will
  not be loaded on clients, this includes singleplayer and LAN."
- Entrypoints: `main` (`ModInitializer`) runs on both physical sides; `server` runs "after
  `main`, and only on the physical server side" and implements `DedicatedServerModInitializer`.
- Kotlin entrypoints use the `kotlin` adapter provided by fabric-language-kotlin
  ([FLK README](https://github.com/FabricMC/fabric-language-kotlin)):

```json
{
  "schemaVersion": 1,
  "id": "mctraveler",
  "environment": "server",
  "entrypoints": {
    "main": [ { "adapter": "kotlin", "value": "net.mctraveler.MCTraveler" } ]
  },
  "depends": {
    "fabricloader": ">=0.19.3",
    "fabric-api": "*",
    "fabric-language-kotlin": ">=1.13.13+kotlin.2.4.10",
    "minecraft": "~26.2"
  }
}
```

The FLK README supports `object`, `class`, companion-object, and top-level-function entrypoints;
an `object MCTraveler : ModInitializer` singleton is the canonical form. It also warns:
`schemaVersion` must be `1` or entrypoints won't load, and `$Companion` suffixes may need
escaping in `processResources` ([FLK README](https://github.com/FabricMC/fabric-language-kotlin)).

### Project layout

- Use the official template generator ([creating-a-project docs](https://docs.fabricmc.net/develop/getting-started/creating-a-project),
  generator at fabricmc.net/develop/template/) — it has explicit options for **Kotlin** and
  **Kotlin buildscripts**.
- Do **not** use Loom's `splitEnvironmentSourceSets()` (client/main split) — that exists for mods
  that ship client code; a server-only mod keeps a single `main` source set (plus test source
  sets, section 4).
- Dependency declarations on the 26.1+ toolchain use plain `implementation` (no `modImplementation`)
  and the `jar` task (no `remapJar`) ([26.1 announcement](https://fabricmc.net/2026/03/14/261.html)).
  Note: FLK's README still shows `modImplementation` (it predates/straddles the toolchain change);
  on the new `net.fabricmc.fabric-loom` plugin, use the plain configuration.

### What breaks when server-only

- Any accidental reference to client classes compiles in dev (the dev classpath can include the
  client) but crashes on the dedicated server — keep CI running the server gametests (section 4)
  to catch this.
- `environment: server` mods are simply absent on clients, so *everything the player sees must be
  achieved through vanilla protocol mechanisms* (teleports, dimensions synced by the vanilla
  login/respawn packets, scoreboards, resource-pack prompts, etc.). Custom dimensions are synced
  to vanilla clients by the vanilla registry-sync on join — this is precisely what
  [Fantasy](https://github.com/NucleoidMC/fantasy)-based server-side minigame servers
  (Nucleoid) rely on.

---

## 3. Multiple overworlds on one dedicated server

### The three options

**(a) Datapack/JSON-defined static dimensions — recommended for MCTraveler.**
Vanilla loads dimensions defined in datapack JSON (`data/<ns>/dimension/*.json` referencing a
dimension type and generator) at server start; no code is required to *create* them. The Fabric
wiki's world-gen section explains the concepts (DimensionType = bed behavior/height/sky,
ChunkGenerator = terrain, Biome) and its "Adding Dimensions" entry defers to the vanilla datapack
tutorial ([Fabric wiki: Dimension Concepts](https://wiki.fabricmc.net/tutorial:dimensionconcepts);
the old code-based `tutorial:dimension` page has been deleted from the wiki).
Real-world precedent: dimensional-inventories was "originally developed to add a separate
creative world to a survival server **using a custom dimension datapack** (example datapack
attached to releases)" ([README](https://github.com/Thomilist/dimensional-inventories)).
An extra overworld is a dimension JSON with `"type": "minecraft:overworld"` and the overworld
noise generator/biome source. Fixed, known-at-boot world list; state lives in the normal world
save; zero libraries.

**(b) NucleoidMC Fantasy — recommended if worlds must be created/destroyed at runtime.**
"Fantasy is a library that allows for dimensions to be created and destroyed at runtime on the
server", supporting temporary worlds and persistent worlds across restarts
([README](https://github.com/NucleoidMC/fantasy)). Actively maintained: **0.8.2+26.2** on
[maven.nucleoid.xyz](https://maven.nucleoid.xyz/xyz/nucleoid/fantasy/) (plus 0.7.0+1.21.11 for the
old line). API (post-mojmap naming, per README):

```java
Fantasy fantasy = Fantasy.get(server);
RuntimeLevelConfig config = new RuntimeLevelConfig()
    .setDimensionType(DimensionTypes.OVERWORLD)
    .setGenerator(server.getOverworld().getChunkManager().getChunkGenerator())
    .setSeed(1234L)
    .setDifficulty(Difficulty.HARD)
    .setGameRule(GameRules.DO_DAYLIGHT_CYCLE, false);
RuntimeLevelHandle handle = fantasy.getOrOpenPersistentLevel(id, config); // or openTemporaryLevel
ServerLevel level = handle.asLevel();
```

Caveat from the README: persistent levels are **not** auto-restored — the mod must call
`getOrOpenPersistentLevel` again on every boot. Trade-off: an extra third-party dependency
(nucleoid maven), mixin-based internals that track Minecraft updates.

**(c) Direct `ServerLevel` registration by hand** — injecting levels into
`MinecraftServer`'s level map yourself. This is exactly the fragile surface Fantasy wraps
(its source patches `MinecraftServerMixin`, `ChunkMapMixin`, `ServerChunkCacheMixin`,
`ServerLevelMixin`, registry removal, per-level data —
[source tree](https://github.com/NucleoidMC/fantasy/tree/main/src/main/java/xyz/nucleoid/fantasy)).
Not recommended; if runtime worlds are needed, use Fantasy rather than re-deriving those mixins.

**Recommendation:** MCTraveler replaces a *fixed* two-server topology, so (a) static datapack
dimensions shipped inside the mod jar is the simplest, most vanilla-compatible fit; add Fantasy
only if/when dynamic world lifecycle becomes a feature.

### Making an extra dimension behave exactly like the overworld

- **Dimension type:** reference `minecraft:overworld` (bed/respawn behavior, height, skylight,
  coordinate scale all come from the dimension type — [Fabric wiki: Dimension Concepts](https://wiki.fabricmc.net/tutorial:dimensionconcepts)).
  Fantasy does the same via `.setDimensionType(DimensionTypes.OVERWORLD)`
  ([README](https://github.com/NucleoidMC/fantasy)).
- **Generation/seed:** reuse the overworld chunk generator (Fantasy README shows
  `server.getOverworld().getChunkManager().getChunkGenerator()`) or define the standard noise
  generator in the datapack JSON. Per-level *seed* control is a first-class Fantasy API
  (`RuntimeLevelConfig.setSeed`); for datapack dimensions the world seed applies — verify the
  current 26.2 dimension JSON schema during implementation if distinct seeds per static world are
  required (if so, Fantasy or a tiny generator shim is the lever).
- **Time / weather / game rules are shared in vanilla.** Evidence: Fantasy ships
  `RuntimeLevelData`, `DelegatingGameRules`, `RuntimeClockManager` and a `TimeCommandMixin`
  precisely to give runtime levels their *own* game rules/clock
  ([Fantasy source](https://github.com/NucleoidMC/fantasy/tree/main/src/main/java/xyz/nucleoid/fantasy)),
  and its README states "World-wide values such as difficulty and game rules can be configured
  per-level" as a Fantasy feature. For MCTraveler's "several overworlds" this shared-clock
  behavior is probably desirable (consistent day/night across worlds); sleeping/weather semantics
  across simultaneous worlds should get a dedicated gametest during implementation.
- **Portals do not work into custom dimensions**: "Custom dimensions do not support travel via
  portals, so to actually get to one, a teleport of some sort is required"
  ([dimensional-inventories README](https://github.com/Thomilist/dimensional-inventories)) — fine
  here, since MCTraveler's travel UX is custom anyway.

### Teleporting players between dimensions (server-side)

- Fabric API's old `FabricDimensions.teleport()` helper **no longer exists** on the 26.2 branch —
  `fabric-dimensions-v1` now only contains `DimensionEvents` (dimension-attribute events added in
  26.1) ([module source, 26.2 branch](https://github.com/FabricMC/fabric/tree/26.2/fabric-dimensions-v1);
  [26.1 announcement](https://fabricmc.net/2026/03/14/261.html) lists the new dimension events).
  Cross-dimension teleportation is done with the **vanilla** `ServerPlayer`/`Entity` teleport API
  (mojmap `TeleportTransition`-style teleports; also reachable as `/execute in <dim> run tp`).
- To *react* to world changes, Fabric API provides
  `ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL` ("Called after a player has been
  moved to different level") and `AFTER_ENTITY_CHANGE_LEVEL`
  ([source, 26.2 branch](https://github.com/FabricMC/fabric/blob/26.2/fabric-entity-events-v1/src/main/java/net/fabricmc/fabric/api/entity/event/v1/ServerEntityLevelChangeEvents.java)).
  (This is the mojmap rename of the former `ServerEntityWorldChangeEvents`.)

### Per-dimension player data separation (Multiverse-inventories equivalent)

There is **no first-party Fabric API** for per-world player state. The established Fabric
approach, embodied by [Thomilist/dimensional-inventories](https://github.com/Thomilist/dimensional-inventories)
(actively maintained, on MC 26.2 as of July 2026):

- Worlds are grouped into **dimension pools**; within a pool the player state is shared, across
  pools it is swapped. Per its README the swapped state covers "the regular inventory, armour,
  offhand, ender chest, experience, score, food, saturation, exhaustion and health" plus
  gamemode, with per-pool toggles for advancement progress and statistics.
- On pool boundary crossing, state is serialized to a per-pool file, cleared, and the destination
  pool's file is loaded. Non-player entities crossing pools are deleted.
- Implementation shape (from its [source](https://github.com/Thomilist/dimensional-inventories)):
  a handful of accessor/behavior mixins (`ServerPlayerAccessor`, `FoodDataAccessor`,
  `DisableAdvancementProgressMixin`, …) — i.e., mostly plain serialization plus small hooks.

**Position is not covered** by dimensional-inventories. For MCTraveler's "per-world position",
implement it in the mod: on `AFTER_PLAYER_CHANGE_LEVEL` (or before initiating the teleport),
persist the origin position keyed by (player, world-group) and teleport returning players to
their stored position. Given MCTraveler already owns the travel flow, writing the state-swap
in-mod (using dimensional-inventories as a reference implementation, or depending on it outright)
is the realistic path.

---

## 4. Testing

Primary source for this whole section: [Fabric docs, "Automated Testing"](https://docs.fabricmc.net/develop/automatic-testing)
([markdown source](https://github.com/FabricMC/fabric-docs/blob/main/develop/automatic-testing.md)).

### Unit tests: fabric-loader-junit

- Rationale, per the docs: "Since Minecraft modding relies on runtime byte-code modification
  tools such as Mixin, simply adding and using JUnit normally would not work. That's why Fabric
  provides Fabric Loader JUnit, a JUnit plugin that enables unit testing in Minecraft" — i.e.
  Minecraft classes are on the test classpath **with mixins applied**, no client/server boot.
- Setup: `testImplementation "net.fabricmc:fabric-loader-junit:<loader_version>"` +
  `test { useJUnitPlatform() }`. The artifact is versioned in lockstep with Loader —
  **0.19.3** is current ([maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-loader-junit/maven-metadata.xml)).
- Tests live in `src/test/`; registry-dependent classes (`ItemStack` etc.) need a one-time
  bootstrap in `@BeforeAll` (`SharedConstants` detect + `Bootstrap` init per the docs example).
- Limits: no `MinecraftServer`, no worlds, no ticking — for logic-level tests only (codecs,
  routing/graph logic, config parsing, protocol-ish state machines). This will fit a large share
  of the ported proxy logic.

### Game tests: headless server tests (the important layer here)

- Minecraft's own gametest framework runs **server-side** structure-based tests; Fabric wires it
  through Loom ([automatic-testing docs](https://docs.fabricmc.net/develop/automatic-testing)):

```gradle
fabricApi {
  configureTests {
    createSourceSet = true      // tests in src/gametest with own fabric.mod.json
    modId = "mctraveler-test"
    enableGameTests = true      // server-side vanilla gametest run config
    enableClientGameTests = false  // server-only mod: skip client tests
    eula = true
  }
}
```

  Full option list: [Loom Fabric API DSL docs](https://docs.fabricmc.net/develop/loom/fabric-api#tests).
- Tests are `@GameTest` methods on a class registered under the `fabric-gametest` entrypoint of
  the gametest source set's own `fabric.mod.json`; helpers assert on blocks/entities in a test
  structure (docs example uses `GameTestHelper` + `CustomTestMethodInvoker`).
- **CI:** "Server game tests will be run automatically with the `build` Gradle task" — i.e.
  `./gradlew build` boots the headless test server and fails the build on test failure; no extra
  CI plumbing beyond uploading `build/reports` on failure
  ([automatic-testing docs](https://docs.fabricmc.net/develop/automatic-testing)).

### Booting a *real* dedicated server in tests

Loom's production run tasks do exactly this: `net.fabricmc.loom.task.prod.ServerProductionRunTask`
"uses the same server launcher that you download from the Fabric website, guaranteeing that the
environment is as close to production as possible", with configurable `mods`, `jvmArgs`,
`installerVersion`, `loaderVersion`, `minecraftVersion` (it can even run a *different* MC version)
([Production Run Tasks docs](https://docs.fabricmc.net/develop/loom/production-run-tasks);
task classes in [fabric-loom `task/prod`](https://github.com/FabricMC/fabric-loom/tree/dev/1.17/src/main/java/net/fabricmc/loom/task/prod)).
Combine with the gametest system property to run the gametest suite against a true production
server in CI.

### How real mods structure the pyramid

[dimensional-inventories](https://github.com/Thomilist/dimensional-inventories) (server-side,
26.2) is a working reference: `src/main` + separate `gametest` and `gametestClient` source sets,
separate interactive vs gametest run dirs, gametest-only mixins (including a
`TestEnvironmentDefinitionMixin` for the vanilla test-environment registry) — see its
[build.gradle](https://github.com/Thomilist/dimensional-inventories/blob/develop/build.gradle).

**Recommended pyramid for MCTraveler:** fabric-loader-junit for ported pure logic; server
gametests (run on every `./gradlew build`) for dimension setup, travel, and state-swap behavior;
one `ServerProductionRunTask`-based smoke job in CI against the real launcher.

---

## 5. Fast iteration

### Dev server run configuration

Loom generates IDE run configurations and Gradle tasks for both sides: `./gradlew runClient` /
`./gradlew runServer`, with IDE debug variants ("Minecraft Server (:runServer)" etc.) —
[Launching the Game (IntelliJ)](https://docs.fabricmc.net/develop/getting-started/intellij-idea/launching-the-game).
For a server-only mod the inner loop is: debug-run `runServer`, connect a vanilla client to
`localhost`.

### Hotswap in 2026: JetBrains Runtime + enhanced class redefinition

Per the same Fabric docs page:

- Plain JVM hotswap (debugger "Reload Changed Classes") is limited: no adding/removing methods or
  fields, no signature changes — method bodies only.
- **JetBrains Runtime** lifts most of that: run the game on JBR and add the VM argument
  `-XX:+AllowEnhancedClassRedefinition` to the run configuration
  ([docs](https://docs.fabricmc.net/develop/getting-started/intellij-idea/launching-the-game)).
  JBR builds at **JDK 25** exist (e.g. `jbr-release-25.0.3b508.16`,
  [JetBrainsRuntime releases](https://github.com/JetBrains/JetBrainsRuntime/releases)) — this
  matters because MC 26.2 requires Java 25 (section 1), and it means the JBR hotswap path works
  on the current stack. Wire it in by pointing the run configuration's JRE (or a Loom
  `javaLauncher` toolchain) at the JBR install.
- **Mixins do hot-swap, with a caveat:** add
  `-javaagent:"<path to the sponge-mixin jar from External Libraries>"` to the run config VM
  options; then you can "modify the contents of your mixin methods during debugging and have the
  changes take effect without restarting the game"
  ([docs](https://docs.fabricmc.net/develop/getting-started/intellij-idea/launching-the-game)).
  That promise covers **method bodies**; adding new injections/targets still needs a restart.
- Kotlin caveat (project-level): hotswap operates on the compiled classes, so it works for Kotlin
  code too, but relies on IDE recompilation of the changed `.kt` files before "Reload Changed
  Classes"; structural Kotlin changes (new default parameters, new lambdas creating synthetic
  classes) fall into the "enhanced" tier that needs JBR.

### Build times: what Kotlin adds and how to mitigate

- **The 26.1+ toolchain itself is the biggest win:** remapping was removed entirely
  (`jar` replaces `remapJar`, no intermediary), per the
  [26.1 announcement](https://fabricmc.net/2026/03/14/261.html). Historical slow steps in Fabric
  builds (remap jar, remap classpath) are gone.
- Kotlin Gradle plugin, per [kotlinlang.org "Compilation and caches"](https://kotlinlang.org/docs/gradle-compilation-and-caches.html):
  incremental compilation is "enabled by default for Kotlin/JVM"; the plugin uses the Gradle
  **build cache** and supports the Gradle **configuration cache**; compilation runs in a
  persistent Kotlin daemon. "From Kotlin 2.0.0, the K2 compiler is used by default" — FLK's
  bundled Kotlin 2.4.10 is well into the K2 era.
- Practical mitigations: enable `org.gradle.configuration-cache=true` and
  `org.gradle.caching=true`, keep the Gradle daemon warm, and keep the mod a single module.
  (dimensional-inventories ships `org.gradle.parallel=true` + `org.gradle.vfs.watch=true` in its
  [gradle.properties](https://github.com/Thomilist/dimensional-inventories/blob/develop/gradle.properties).)
- Concrete clean/incremental numbers for a small Fabric Kotlin mod are not published by any
  primary source; expectation to validate in week 1: clean build dominated by Loom's one-time
  Minecraft artifact setup, then seconds-scale incremental `compileKotlin` + `jar`. Measure with
  `--profile` and treat regressions as build bugs.

---

## 6. Kotlin specifics for Fabric

- **Language provider:** fabric-language-kotlin is the official Fabric language module; the mod
  depends on it at runtime (players/server install it like a library mod) and declares the
  `kotlin` adapter per entrypoint. It bundles stdlib, reflect, coroutines, serialization,
  datetime, atomicfu, kotlinx-io — do not shade your own copies; depend on FLK's
  ([FLK README](https://github.com/FabricMC/fabric-language-kotlin)).
- **Mixins are written in Java, in practice.** Empirical evidence from the flagship server-side
  Kotlin Fabric mod [QuiltServerTools/Ledger](https://github.com/QuiltServerTools/Ledger)
  (updated 2026-06): 117 Kotlin files, **105 Java mixins, 0 Kotlin mixins** — mixin classes live
  in `src/main/java/.../mixin/` while all mod logic is Kotlin. The official template generator's
  Kotlin option likewise keeps a Java source set. Plan: Kotlin everywhere except
  `src/main/java/<pkg>/mixin/`.
- **Prefer Fabric API events over mixins** wherever possible (e.g.
  `ServerEntityLevelChangeEvents`, lifecycle events) — fewer Java files, no bytecode-target
  churn on Minecraft updates ([Fabric events docs](https://docs.fabricmc.net/develop/events)).
- **Coroutines on the server thread:** the server is single-threaded per tick; the idiomatic
  pattern (from Ledger's
  [`McDispatcher.kt`](https://github.com/QuiltServerTools/Ledger/blob/master/src/main/kotlin/com/github/quiltservertools/ledger/utility/McDispatcher.kt))
  is a `CoroutineDispatcher` that dispatches into `server::execute` (the vanilla thread-safe task
  queue), so suspending work (DB, IO) runs off-thread and resumes on the server thread. FLK
  bundles kotlinx-coroutines 1.11.0 for exactly this.
- **No annotation-processing friction:** Fabric itself uses no annotation processors on mod code;
  Mixin's AP applies to the Java mixin source set only. kotlinx-serialization (compiler-plugin
  based) works normally and is bundled by FLK — a good fit for MCTraveler config/state files.
- **Entrypoint idiom:** `object MCTraveler : DedicatedServerModInitializer` (or `ModInitializer`)
  with the `kotlin` adapter; FLK also supports function and field references
  ([FLK README](https://github.com/FabricMC/fabric-language-kotlin)).
- **Exemplar mods to crib from:** [Ledger](https://github.com/QuiltServerTools/Ledger)
  (server-side-only, Kotlin, mixin/Kotlin split, coroutine dispatcher, SQL off-thread) and
  [SilkMC/silk](https://github.com/SilkMC/silk) ("Silk is a Minecraft API for Kotlin -
  targetting Fabric…", updated 2026-06) — a Kotlin DSL layer (commands, coroutines) worth
  evaluating before hand-rolling equivalents.

---

## Verify-during-implementation list

1. Sleep/weather/time semantics across multiple simultaneous overworlds (shared clock is vanilla
   behavior per Fantasy's workaround mixins — decide desired semantics, cover with a gametest).
2. Whether static datapack dimensions can carry distinct seeds on 26.2, if distinct seeds are a
   requirement (Fantasy's `setSeed` is the fallback).
3. Exact vanilla teleport entrypoint names on 26.2 mojmap (`TeleportTransition`-style API) once
   coding starts — the old Fabric helper is confirmed gone.
4. Real clean/incremental build timings on this machine with configuration cache on.
5. Client-version compatibility decision: 26.2-only clients vs keeping a 1.21.x bridge (e.g. via
   a protocol-compat layer) — out of scope of this doc.
