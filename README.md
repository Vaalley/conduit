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

To deploy updates to the dedicated server:

```sh
cd /root/mctraveler-fabric
git pull
./gradlew build
cp build/libs/mctraveler-0.1.0.jar /root/mctraveler-server/mods/mctraveler-0.1.0.jar
systemctl restart mctraveler
```

## License

See [LICENSE](LICENSE).
