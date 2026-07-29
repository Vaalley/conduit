// The one-time Portal → Fabric migration tool (spec User Stories 43–44).
//
// Kept in its own script so the mod's build file gains a single line:
//   ./gradlew migrate --args="--portal <portal dir> --target <server run dir>"
// See docs/migration.md for the operator runbook.

val sourceSets = the<SourceSetContainer>()

tasks.register<JavaExec>("migrate") {
    group = "migration"
    description = "Migrates a Portal deployment into a ready single-server save (see docs/migration.md)."
    // The mod's own runtime classpath: the migration reuses the live store,
    // region codec and remap table rather than reimplementing any of them.
    // (The launcher comes from the project's Java toolchain by convention.)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "eu.mctraveler.importer.ImporterMain"
}
