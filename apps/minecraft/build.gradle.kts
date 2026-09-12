import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.paperweight) apply false
    alias(libs.plugins.run.paper) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "crabcraft.net"
    // version sourced from apps/minecraft/gradle.properties; override in CI with -Pversion=<tag>
    version = rootProject.version

    val targetJavaVersion = 25
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(targetJavaVersion)
    }
    extensions.configure<JavaPluginExtension> {
        val javaVersion = JavaVersion.toVersion(targetJavaVersion)
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
        if (JavaVersion.current() < javaVersion) {
            toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJavaVersion)
    }

    repositories {
        mavenCentral()
    }
}

tasks.register<Copy>("collectJars") {
    into(layout.projectDirectory.dir("jars"))
    from(project(":spigot").tasks.named("shadowJar"))
    from(project(":velocity").tasks.named("shadowJar"))
}

tasks.register("build") {
    dependsOn("collectJars")
    dependsOn(subprojects.map { it.tasks.named("check") })
}

tasks.register<Delete>("clean") {
    delete(layout.projectDirectory.dir("jars"))
    dependsOn(subprojects.map { it.tasks.named("clean") })
}
