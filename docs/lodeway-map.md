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
| members | the popup |

All regions use a white three-pixel outline and a translucent white
fill so their boundaries remain visible over varied terrain. Flags are server
policy and are not sent to Lodeway.

The popup is deliberately short: `Members:` and one member per line, ten of
them at most and `and N more` after that (a region may hold 99). It says nothing
the map is already drawing — no dimension, no coordinates, no size, no height,
no sub-region count. The label above it names the region and the shape below it
says where and how big; a card repeating those would spend its height on what
the visitor can already see.

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

`/vanish` also asserts invisibility through Lodeway's optional `Vanished` API.
That newer entry point is resolved by name so an older Lodeway installation
still starts normally; with a current Lodeway download, vanished players are
removed from both the map and its online-player list until they return or
disconnect.

## Hot reload

Lodeway's `ready` callback fires again after each of *Lodeway's* runtime swaps,
and the layer is rebuilt from the region tree when it does.

This mod calls the deprecated `Lodeway.forget` at `SERVER_STOPPING`: Lodeway
keeps ready callbacks in a process-wide static, and its usual unregister hook is
a Bukkit-family plugin-disable shim that no Fabric server runs. Without that
call the callback would outlive the server it was registered for.

## Testing

`LodewayRegionsTest` runs the publish against Lodeway's in-process model, which
the API jar carries: geometry, popup text and the upsert/remove diff, with no
map, no server and no network. The wiring above it is not unit-testable by
construction — the test class only runs because the API is on the classpath,
which is the condition the guard exists to detect the absence of.
