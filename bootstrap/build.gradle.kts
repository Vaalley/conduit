import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// The only Fabric mod of the pair (docs/hot-reload.md): mixins (woven at startup,
// never reloadable), the bridge API the runtime implements, and the RuntimeLoader
// that side-loads the :runtime jar and hot-swaps it when the watched jar changes.

plugins {
    id("net.fabricmc.fabric-loom")
    id("org.jetbrains.kotlin.jvm")
}

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String
val flkVersion = property("flk_version") as String

version = property("mod_version") as String
group = property("maven_group") as String

base {
    // The jar that goes into mods/ keeps the historical drop-in name.
    archivesName = "mctraveler"
}

repositories {
    mavenCentral()
}

// Dev runs (runServer, runGameTest) load the runtime from the classpath — same
// classloader as the gametests that reach into feature internals. Only
// production servers side-load the watched runtime jar (docs/hot-reload.md).
// This must be a plain configuration, not a loom-managed one (localRuntime,
// gametestImplementation): loom resolves its own configurations while this
// project is still being configured, and since :runtime depends back on
// :bootstrap's namedElements, an eager edge here deadlocks project evaluation.
// A plain configuration resolves at task execution, after both projects exist.
val sideloadedRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    }
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$flkVersion")

    sideloadedRuntime(project(":runtime"))

    testImplementation("net.fabricmc:fabric-loader-junit:$loaderVersion")

    // Mods placed on the production smoke server alongside our own jar.
    "productionRuntimeMods"("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    "productionRuntimeMods"("net.fabricmc:fabric-language-kotlin:$flkVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
}

// Headless server gametests: run as part of `./gradlew build` and fail it when red.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "mctraveler-test"
        enableGameTests = true
        enableClientGameTests = false // server-environment-only mod: no client tests
        eula = true // by setting this you agree to the Minecraft EULA: https://aka.ms/MinecraftEULA
    }
}

// Gametests exercise feature internals directly, so the gametest source set
// compiles and runs against the runtime module (via the lazily-resolved
// configuration — see sideloadedRuntime above).
sourceSets["gametest"].apply {
    compileClasspath += sideloadedRuntime
    runtimeClasspath += sideloadedRuntime
}

tasks.named<JavaExec>("runServer") {
    classpath(sideloadedRuntime)
}
tasks.named<JavaExec>("runGameTest") {
    classpath(sideloadedRuntime)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
    // Point tests at *our* processed fabric.mod.json — the bare classpath lookup is
    // ambiguous because every Fabric API module jar carries its own fabric.mod.json.
    systemProperty(
        "mctraveler.fabricModJson",
        sourceSets["main"].output.resourcesDir!!.resolve("fabric.mod.json").absolutePath,
    )
}

tasks.withType<ProcessResources>().configureEach {
    val props = mapOf(
        "version" to version.toString(),
        "minecraft_version" to minecraftVersion,
        "loader_version" to loaderVersion,
        "flk_version" to flkVersion,
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

// Iterating on one gametest class: the runner takes a resource-location selector
// (wildcards allowed) from a system property, and test ids are
// `mctraveler-test:<snake_case(ClassName_methodName)>`. For example:
//   ./gradlew runGameTest -Pmctraveler.gametestFilter='mctraveler-test:embassy_plot_game_test_*'
// Without the property the whole suite runs, so `./gradlew build` is unchanged.
tasks.named<JavaExec>("runGameTest") {
    providers.gradleProperty("mctraveler.gametestFilter").orNull?.let {
        systemProperty("fabric-api.gametest.filter", it)
    }
}

// The production smoke hook is its own tiny mod (src/smoke): it cannot live in
// the gametest jar because Fabric instantiates gametest entrypoints even on a
// production server, and gametest classes reference runtime classes that only
// exist inside the bootstrap's side-loaded classloader there.
val smokeSourceSet = sourceSets.create("smoke") {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}

val smokeJar = tasks.register<Jar>("smokeJar") {
    from(smokeSourceSet.output)
    archiveClassifier = "smoke"
}

// Production smoke: boots the built bootstrap jar on the real Fabric server launcher
// with the runtime jar staged into the watched directory, so the smoke run
// exercises the same side-loading path production uses; the smoke-hook mod
// stops the server cleanly once it is fully started.
tasks.register<net.fabricmc.loom.task.prod.ServerProductionRunTask>("prodServer") {
    group = "verification"
    description = "Boots the built mod jar on the real server launcher and exits after a smoke check."
    dependsOn(smokeJar)
    mods.from(smokeJar)
    jvmArgs.add("-Dmctraveler.smoke=true")
    runDir = layout.projectDirectory.dir("run/prod-smoke")
    timeout = Duration.ofMinutes(15)
    // The staged runtime jar the RuntimeLoader picks up (same layout as
    // production). No remap step exists on this loom/Minecraft generation —
    // `jar` is the distributable.
    val runtimeJar = project(":runtime").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(runtimeJar)
    // Everything but the EULA stays at production defaults so the smoke environment
    // matches prod. (The literal is repeated in acceptDevServerEula below on purpose:
    // a shared script-level constant breaks configuration-cache serialization.)
    doFirst {
        val dir = runDir.get().asFile
        dir.mkdirs()
        // Writing eula.txt agrees to the Minecraft EULA: https://aka.ms/MinecraftEULA
        dir.resolve("eula.txt").writeText("eula=true\n")
        val watched = dir.resolve("mctraveler-runtime")
        watched.mkdirs()
        watched.listFiles { f -> f.extension == "jar" }?.forEach { it.delete() }
        runtimeJar.get().asFile.copyTo(watched.resolve("mctraveler-runtime.jar"), overwrite = true)
    }
}

// Dev dedicated server: write the EULA once so `./gradlew runServer` boots straight away.
val acceptDevServerEula = tasks.register("acceptDevServerEula") {
    description = "Writes run/eula.txt (by running the dev server you agree to the Minecraft EULA)."
    val eulaFile = layout.projectDirectory.file("run/eula.txt").asFile
    outputs.file(eulaFile)
    doLast {
        eulaFile.parentFile.mkdirs()
        // Writing eula.txt agrees to the Minecraft EULA: https://aka.ms/MinecraftEULA
        eulaFile.writeText("eula=true\n")
    }
}

tasks.named("runServer") {
    dependsOn(acceptDevServerEula)
}

// Hot-reload dev loop (docs/dev-loop.md): run with
//   ./gradlew runServer -Pmctraveler.devJbr=<JetBrains Runtime home>
// to boot the dev server on JBR with enhanced class redefinition + the mixin hotswap agent.
val devJbr = providers.gradleProperty("mctraveler.devJbr")
if (devJbr.isPresent) {
    tasks.named<JavaExec>("runServer") {
        executable("${devJbr.get()}/bin/java")
        jvmArgs("-XX:+AllowEnhancedClassRedefinition")
        val mixinAgentJar = configurations.named("runtimeClasspath").get()
            .filter { it.name.startsWith("sponge-mixin") }
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-javaagent:${mixinAgentJar.singleFile.absolutePath}")
        })
    }
}
