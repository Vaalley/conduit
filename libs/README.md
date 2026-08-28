# Vendored jars

## `lodeway-pluginapi-0.1.0.jar`

The Lodeway map plugin API (`app.lodeway.api.map`) that
`eu.mctraveler.lodeway` compiles against — see `docs/lodeway-map.md`.

It is vendored rather than resolved from a repository because the API is
**pre-release**: it is not published to Maven Central or to Lodeway's own
repository yet, and it lives on the `feature/layers-and-markers` branch of the
Lodeway repo. Vendoring is what lets this repo build reproducibly today; the
dependency becomes a normal `compileOnly` coordinate once the API ships.

Provenance: `mod/pluginapi` at Lodeway commit `a9046462388d`, trimmed to the
`app/lodeway/api/map` package (the jar Lodeway builds also carries its Dynmap
compatibility layer, which nothing here uses):

```sh
cd <lodeway>/mod && ./gradlew :pluginapi:jar
work=$(mktemp -d)
cd "$work" && unzip -q <lodeway>/mod/pluginapi/build/libs/pluginapi-0.1.0.jar 'app/lodeway/api/map/*'
jar --create --file <conduit>/libs/lodeway-pluginapi-0.1.0.jar -C "$work" app
```

The jar is `compileOnly` (and on the unit-test classpath). It is never shaded
into the runtime jar: on a server that runs Lodeway these classes come from the
Lodeway download itself, which is the only copy that may exist — the Lodeway
runtime and a plugin have to agree on the same `Layer` class across Lodeway's
own hot swaps, and a second copy on the classpath would break that.
