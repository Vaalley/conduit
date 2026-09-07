# MCTraveler - Conduit

Conduit is a server-side Fabric mod for a community Minecraft survival server.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3
- Fabric API 0.156.0+26.2
- Fabric Language Kotlin 1.13.13

## Build

```sh
./gradlew build
```

## Run a dev server

```sh
./gradlew runServer
```

By running the dev server you agree to the
[Minecraft EULA](https://aka.ms/MinecraftEULA).

## Deploying to Production

To deploy, run this on the server:

```sh
cd /root/conduit && ./scripts/deploy.sh
```

The default deployment target is `/srv/mctraveler/mods/` (on production, this links to `/srv/mctraveler/server/mods/`). Override the server directory with `MCTRAVELER_SERVER_DIR` and the systemd service with `MCTRAVELER_SERVICE` if needed. The build requires JDK 25 and the pinned patched MCA Selector jar described in `gradle/merge-worlds.gradle.kts` (built with JDK 21).

The script pulls, runs the unit tests and headless gametests before stopping the server, removes old versioned jars from `mods/` so a version bump cannot leave two installed, then swaps the jar and restarts the service.

One-time cleanup when moving off the old two-jar layout: delete the watched `/srv/mctraveler/server/mctraveler-runtime/` directory — the single jar in `mods/` is now the entire mod.

## License

See [LICENSE](LICENSE).
