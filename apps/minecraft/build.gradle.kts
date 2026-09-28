import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("kapt") version "2.4.20" apply false
    id("com.gradleup.shadow") version "9.6.0" apply false
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
    id("xyz.jpenilla.run-paper") version "3.0.2" apply false
}

subprojects {
    group = "crabcraft.net"
    // Version comes from gradle.properties; CI can override it with -Pversion=<tag>.
    version = rootProject.version

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(25) }
        extensions.configure<SourceSetContainer> {
            named("main") { resources.srcDir(rootProject.file("shared/src/main/resources")) }
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    repositories { mavenCentral() }
}

tasks.register<Copy>("collectJars") {
    into(layout.projectDirectory.dir("jars"))
    from(project(":spigot").tasks.named("shadowJar"))
    from(project(":velocity").tasks.named("shadowJar"))
}

tasks.register("build") {
    dependsOn("collectJars", subprojects.map { "${it.path}:check" })
}

tasks.register<Delete>("clean") {
    delete(layout.projectDirectory.dir("jars"))
    dependsOn(subprojects.map { "${it.path}:clean" })
}
