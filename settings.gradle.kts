pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version extra["loom_version"].toString()
        id("org.jetbrains.kotlin.jvm") version extra["kotlin_version"].toString()
    }
}

rootProject.name = "mctraveler"
