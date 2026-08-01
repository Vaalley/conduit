import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

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
    archivesName = "mctraveler"
}

repositories {
    mavenCentral()
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$flkVersion")

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

// The gametest source set doubles as the production smoke-check mod (see prodServer).
val gametestJar = tasks.register<Jar>("gametestJar") {
    from(sourceSets["gametest"].output)
    archiveClassifier = "gametest"
}

// Production smoke: boots the built jar on the real Fabric server launcher; the smoke
// hook in the gametest jar stops the server cleanly once it is fully started.
tasks.register<net.fabricmc.loom.task.prod.ServerProductionRunTask>("prodServer") {
    group = "verification"
    description = "Boots the built mod jar on the real server launcher and exits after a smoke check."
    dependsOn(gametestJar)
    mods.from(gametestJar)
    jvmArgs.add("-Dmctraveler.smoke=true")
    runDir = layout.projectDirectory.dir("run/prod-smoke")
    timeout = Duration.ofMinutes(15)
    // Everything but the EULA stays at production defaults so the smoke environment
    // matches prod. (The literal is repeated in acceptDevServerEula below on purpose:
    // a shared script-level constant breaks configuration-cache serialization.)
    doFirst {
        val dir = runDir.get().asFile
        dir.mkdirs()
        // Writing eula.txt agrees to the Minecraft EULA: https://aka.ms/MinecraftEULA
        dir.resolve("eula.txt").writeText("eula=true\n")
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

apply(from = "gradle/migrate.gradle.kts") // the one-time Portal migration tool (docs/migration.md)
apply(from = "gradle/import-nucleus.gradle.kts") // the Nucleus embassy import (docs/nucleus-import.md)
apply(from = "gradle/merge-worlds.gradle.kts") // the one-time merge of Secondary into Primary

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
