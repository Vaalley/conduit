# The Lodeway map layer

Lodeway (`lodeway.app`) is a hosted web map for Minecraft servers. When it is
installed alongside this mod, `eu.mctraveler.lodeway` publishes every region as
a layer on it: one toggle called **Regions**, one outlined area per region and
per sub-region, a label a visitor reads on hover and a plain-text popup when
they click.

Nothing about it is required. On a server without Lodeway the feature does not
run, and on a server with an older Lodeway download that predates its plugin
API it does not run either — see *The guard* below.

## What is drawn

| Region | Map |
|---|---|
| title | the area's label |
| `minX`/`maxX`, `minZ`/`maxZ` | the four corners, the far edge taken one block past `max` |
| `startY`/`endY`, when not the 320/−64 defaults | the area's height |
| world | the dimension's full id (`minecraft:overworld`, `mctraveler:embassies`) |
| members, flags, parent, sub-region count | the popup |
| the `EMBASSY` flag | a second colour, and the popup's first line |

Marker ids are the region's position in the tree — `0`, `0.1`, `3.2` — so a
rename or a resize updates the marker that was already there. A deletion
renumbers the regions after it, which the diff resolves on the same publish.

## When it is redrawn

`RegionService.onChange` fires after every save of `regions.json`, and every
region mutation saves: create, delete, rename, y bounds, flags, membership,
and the embassy commands built on top of them. There is no polling and no
scheduled sweep.

Each publish is an upsert of every live region followed by the removal of the
ids that are no longer in the tree, rather than a clear and a rebuild: Lodeway
reads the model on its own thread, and a rebuild would sometimes be read
halfway through as an empty layer.

## The guard

The API lives in `app.lodeway.api.map`, and it comes from **Lodeway's own
download** — never from this mod, which compiles against it `compileOnly` and
ships no copy of it (`libs/README.md` says why the jar is vendored, and how it
was built).

`LodewayFeature` is therefore the only class in this feature that a
Lodeway-less server loads. It names the API by string, probes for it with
`Class.forName`, and only then touches `LodewayRegions` — the one class that
mentions an `app.lodeway.api.map` type. A missing API is a silent no-op, not a
`NoClassDefFoundError`.

## Hot reload

Lodeway's `ready` callback fires again after each of *Lodeway's* runtime swaps,
and the layer is rebuilt from the region tree when it does.

This mod's own hot reload (docs/hot-reload.md) is the other direction, and it
needs the deprecated `Lodeway.forget` at `SERVER_STOPPING`: Lodeway keeps ready
callbacks in a process-wide static, and its usual unregister hook is a
Bukkit-family plugin-disable shim that no Fabric server runs. Without that call
the callback would outlive the runtime and pin its classloader, which is the
one thing every registration in `:runtime` is shaped to avoid.

## Testing

`LodewayRegionsTest` runs the publish against Lodeway's in-process model, which
the API jar carries: geometry, popup text and the upsert/remove diff, with no
map, no server and no network. The wiring above it is not unit-testable by
construction — the test class only runs because the API is on the classpath,
which is the condition the guard exists to detect the absence of.
