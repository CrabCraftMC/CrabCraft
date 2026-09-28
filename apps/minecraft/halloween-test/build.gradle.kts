plugins {
    kotlin("jvm")
    id("xyz.jpenilla.run-paper")
    id("com.gradleup.shadow")
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir("../spigot/src/main/kotlin")
    kotlin.include(
        "crabcraft/net/halloweentest/**",
        "crabcraft/net/crabUtilities/CrabMessages.kt",
        "crabcraft/net/crabUtilities/halloween/**",
    )
    listOf("BingoTask", "BingoDetector", "AbstractBingoDetector", "BingoTracking", "BingoCardTwoListener").forEach {
        kotlin.include("crabcraft/net/crabUtilities/bingo/$it.kt")
    }
}

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.87-stable")
    implementation("redis.clients:jedis:5.1.0")
}

tasks.jar { archiveClassifier.set("slim") }

tasks.shadowJar {
    archiveFileName.set("CrabHalloweenTest.jar")
    relocate("redis.clients.jedis", "crabcraft.net.libs.jedis")
    relocate("org.apache.commons.pool2", "crabcraft.net.libs.pool2")
    relocate("org.json", "crabcraft.net.libs.json")
}

tasks.build { dependsOn(tasks.shadowJar) }

val prepareHalloweenTestServer =
    tasks.register<Copy>("prepareHalloweenTestServer") {
        from(layout.projectDirectory.file("server.properties"))
        into(layout.projectDirectory.dir("run"))
    }

tasks.runServer {
    dependsOn(prepareHalloweenTestServer)
    minecraftVersion("26.2")
    build(87)
    jvmArgs("-Xms512M", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
}

tasks.test { failOnNoDiscoveredTests = false }
