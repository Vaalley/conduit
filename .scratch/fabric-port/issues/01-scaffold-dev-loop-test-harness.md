# 01 — Scaffold + dev loop + test harness

**What to build:** A bootable server-side-only Fabric mod project (Kotlin, Minecraft 26.2) whose standard Gradle build runs a headless in-server gametest, with a documented seconds-scale hot-reload dev loop. This is the tracer bullet through the entire toolchain.

**Blocked by:** None — can start immediately.

**Status:** done

See `../spec.md` (Implementation Decisions: Platform, Dev loop; Testing Decisions) and `docs/research/fabric-kotlin-platform.md` for versions and cited setup guidance.

- [x] Gradle project with the new no-remap Loom, Fabric Loader/API for MC 26.2, Java 25, Kotlin via fabric-language-kotlin; mod declares server environment only
- [x] A trivial gametest (e.g. server boots, mod initialized) runs headlessly as part of `./gradlew build` and fails the build when red
- [x] A trivial fabric-loader-junit unit test runs in the same build
- [x] Dev dedicated-server run config works with JetBrains Runtime enhanced class redefinition (+ mixin hotswap agent); the edit→hot-swap loop is documented in the repo
- [x] A production smoke task boots the built jar via the real server launcher and exits cleanly
- [x] Gradle configuration cache + Kotlin incremental compilation enabled; incremental build is seconds-scale

## Comments

**2026-07-28 — implemented (agent).** All acceptance criteria verified green.

Versions used (all resolved exactly as cited in `docs/research/fabric-kotlin-platform.md` — nothing 404ed):
- Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, fabric-language-kotlin 1.13.13+kotlin.2.4.10 (Kotlin 2.4.10), fabric-loader-junit 0.19.3
- Loom 1.17.17 (`net.fabricmc.fabric-loom`, no-remap: plain `implementation`, plain `jar`), Gradle 9.6.1 (wrapper; same as the dimensional-inventories reference), Kotlin Gradle plugin 2.4.10
- Build/toolchain JDK: Homebrew OpenJDK 25 (installed during this ticket; `JAVA_HOME` must point at a JDK 25). Dev-loop JBR: jbr-25.0.3b508.16 (osx-aarch64), verified booting `runServer` with `-XX:+AllowEnhancedClassRedefinition` plus the sponge-mixin 0.17.3 `-javaagent`.

Key choices:
- Gametests wired via Loom's `fabricApi.configureTests` (own `src/gametest` source set, mod id `mctraveler-test`, server tests only). `runGameTest` is part of `check`/`build`; red proven to fail the build (deliberate red run before greening the initializer).
- Unit tier: `ModMetadataTest` pins the server-environment-only contract by parsing our processed `fabric.mod.json` (path passed as a system property — a bare classpath lookup is ambiguous because every Fabric API module jar ships one); `LoaderJUnitHarnessTest` proves the fabric-loader-junit harness itself (SharedConstants + Bootstrap, registry lookup). Note: 26.2 mojmap renamed `ResourceLocation` to `Identifier`.
- Production smoke: `prodServer` (Loom `ServerProductionRunTask`, real Fabric server launcher, installer version from Loom's convention). The gametest jar rides along as a smoke mod: with `-Dmctraveler.smoke=true`, `SmokeHook` stops the server cleanly after `SERVER_STARTED` verifies the mod initialized. Run dir uses production defaults (online-mode untouched); only `eula.txt` is written.
- Hot-reload loop documented in `docs/dev-loop.md`; CLI wiring via `-Pmctraveler.devJbr=<jbr-home>` adds the JBR executable, the redefinition flag, and resolves the mixin agent jar from the runtime classpath.
- Configuration cache + build cache + parallel + VFS watch + Kotlin incremental on. Measured warm loop: full `build` (incl. headless gametest server) ~10-11 s, `test` ~6 s after an edit, no-op sub-second.
- Config-cache gotcha recorded: a shared top-level script `val` captured by task closures breaks serialization ("cannot serialize Gradle script object references"), so the two eula.txt literals are deliberately inlined per task.

Deferred / for later tickets:
- The gametest asserts only boot+init (the tracer). Real behaviour gametests start with ticket 02+.
- `enforcesSecureChat`, Worlds datapack trio, and the "both Worlds present" smoke assertion belong to ticket 04+ (smoke currently proves vanilla trio boot).
- IntelliJ-side hotswap (Reload Changed Classes) is documented but only the JVM/agent wiring is machine-verified; the interactive reload needs a debugger session.
