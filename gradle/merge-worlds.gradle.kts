// The one-time merge of Secondary into Primary (merge spec User Stories 1–7).
//
// The third sibling of gradle/migrate.gradle.kts and gradle/import-nucleus.gradle.kts,
// kept the same way so the mod's build file gains a single line:
//   ./gradlew mergeWorlds --args="--target <server run dir>"

val sourceSets = the<SourceSetContainer>()

tasks.register<JavaExec>("mergeWorlds") {
    group = "migration"
    description = "Plans where Secondary's landmass moves to inside Primary, and writes nothing."
    // The mod's own runtime classpath: the merge reuses the live region service,
    // player store and World layout rather than reimplementing any of them.
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "eu.mctraveler.importer.WorldMergeMain"
}
