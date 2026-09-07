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

After a `git pull`, this is the whole deploy — copy and paste it as one block:

```sh
cd /root/mctraveler-fabric
git pull
./gradlew build
systemctl stop mctraveler
cp build/libs/mctraveler-0.1.0.jar /root/mctraveler-server/mods/mctraveler-0.1.0.jar
systemctl start mctraveler
```

`./gradlew build` runs the unit tests and the headless gametest suite first, so a red test never reaches the server.

One-time cleanup when moving off the old two-jar layout: delete the watched `/root/mctraveler-server/mctraveler-runtime/` directory — the single jar in `mods/` is now the entire mod.

## License

See [LICENSE](LICENSE).
