# 01 — Scaffold + dev loop + test harness

**What to build:** A bootable server-side-only Fabric mod project (Kotlin, Minecraft 26.2) whose standard Gradle build runs a headless in-server gametest, with a documented seconds-scale hot-reload dev loop. This is the tracer bullet through the entire toolchain.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

See `../spec.md` (Implementation Decisions: Platform, Dev loop; Testing Decisions) and `docs/research/fabric-kotlin-platform.md` for versions and cited setup guidance.

- [ ] Gradle project with the new no-remap Loom, Fabric Loader/API for MC 26.2, Java 25, Kotlin via fabric-language-kotlin; mod declares server environment only
- [ ] A trivial gametest (e.g. server boots, mod initialized) runs headlessly as part of `./gradlew build` and fails the build when red
- [ ] A trivial fabric-loader-junit unit test runs in the same build
- [ ] Dev dedicated-server run config works with JetBrains Runtime enhanced class redefinition (+ mixin hotswap agent); the edit→hot-swap loop is documented in the repo
- [ ] A production smoke task boots the built jar via the real server launcher and exits cleanly
- [ ] Gradle configuration cache + Kotlin incremental compilation enabled; incremental build is seconds-scale
