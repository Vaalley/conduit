// The mod is split across two jars (docs/hot-reload.md):
//   :bootstrap — the only Fabric mod: mixins, the bridge API, and the RuntimeLoader
//               that side-loads and hot-reloads the runtime jar.
//   :runtime  — every feature; loaded by the bootstrap, never by Fabric itself.
// This root project only carries shared coordinates; all real config lives in the
// subprojects' build files.

version = property("mod_version") as String
group = property("maven_group") as String
