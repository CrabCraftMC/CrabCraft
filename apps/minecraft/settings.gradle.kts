pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "papermc"
            url = uri("https://repo.papermc.io/repository/maven-public/")
        }
    }
}

plugins {
    // Settings plugins cannot use version catalog aliases.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "CrabUtilities"

include("spigot")
include("velocity")
include("bingo-test")
