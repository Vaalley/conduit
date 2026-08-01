// The one-time Nucleus → Fabric embassy import (spec User Stories 38–39).
//
// The sibling of gradle/migrate.gradle.kts, kept the same way so the mod's build
// file gains a single line:
//   ./gradlew importNucleus --args="--old <nucleus server dir> --target <server run dir>"
// See docs/nucleus-import.md for the operator runbook.

val sourceSets = the<SourceSetContainer>()

tasks.register<JavaExec>("importNucleus") {
    group = "migration"
    description = "Imports the Nucleus embassies, regions and crystal energy (see docs/nucleus-import.md)."
    // The mod's own runtime classpath: the import reuses the live region service,
    // player store and crystal energy model rather than reimplementing any of them.
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "eu.mctraveler.importer.EmbassyImportMain"
}
