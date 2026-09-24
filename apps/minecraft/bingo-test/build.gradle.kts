plugins {
    kotlin("jvm")
    id("xyz.jpenilla.run-paper")
    id("com.gradleup.shadow")
}

val testCardNumber = 6
val testPluginName = "CrabBingoCard${testCardNumber}Test"
val testArchiveName = "$testPluginName.jar"
val cardListenerClasses =
    listOf("BingoCardSixWorldListener", "BingoCardSixMobListener", "BingoCardSixMechanicsListener")

base { archivesName.set(testPluginName) }

kotlin.sourceSets.named("main") {
    kotlin.srcDir("../spigot/src/main/kotlin")
    kotlin.include("crabcraft/net/bingotest/**", "crabcraft/net/crabUtilities/CrabMessages.kt")
    listOf("BingoTask", "BingoDetector", "AbstractBingoDetector", "BingoTracking").plus(cardListenerClasses).forEach {
        kotlin.include("crabcraft/net/crabUtilities/bingo/$it.kt")
    }
}

repositories { maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" } }

dependencies { compileOnly("io.papermc.paper:paper-api:26.2.build.87-stable") }

tasks.jar { archiveClassifier.set("slim") }

tasks.shadowJar { archiveFileName.set(testArchiveName) }

tasks.build { dependsOn(tasks.shadowJar) }

val prepareBingoTestServer =
    tasks.register<Copy>("prepareBingoTestServer") {
        from(layout.projectDirectory.file("server.properties"))
        into(layout.projectDirectory.dir("run"))
    }

tasks.runServer {
    dependsOn(prepareBingoTestServer)
    minecraftVersion("26.2")
    build(87)
    jvmArgs("-Xms512M", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
}

tasks.register<JavaExec>("playerProgressRegressionTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("crabcraft.net.bingotest.PlayerProgressTest")
}

tasks.register("testJarContentsRegressionTest") {
    group = "verification"
    dependsOn(tasks.shadowJar)
    doLast {
        val archive = tasks.shadowJar.get().archiveFile.get().asFile
        check(archive.name == testArchiveName) { "Unexpected Card #$testCardNumber test JAR name: ${archive.name}" }
        val entries = mutableSetOf<String>()
        zipTree(archive).visit { if (!isDirectory) entries += relativePath.pathString }

        val listenerClassEntries = cardListenerClasses.map { "crabcraft/net/crabUtilities/bingo/$it.class" }
        val allowedProductionClasses =
            listOf(
                "crabcraft/net/crabUtilities/CrabMessages.class",
                "crabcraft/net/crabUtilities/bingo/BingoTask.class",
                "crabcraft/net/crabUtilities/bingo/BingoDetector.class",
                "crabcraft/net/crabUtilities/bingo/AbstractBingoDetector.class",
                "crabcraft/net/crabUtilities/bingo/BingoTracking.class",
            ) + listenerClassEntries
        val required =
            listOf(
                "plugin.yml",
                "crabcraft/net/bingotest/CrabBingoTestPlugin.class",
                "crabcraft/net/bingotest/BingoTestCommand.class",
                "crabcraft/net/bingotest/BingoTestJoinListener.class",
                "crabcraft/net/bingotest/BingoTestManager.class",
                "crabcraft/net/bingotest/PlayerProgress.class",
                "kotlin/jvm/internal/Intrinsics.class",
            ) + allowedProductionClasses
        val missing = required - entries
        check(missing.isEmpty()) {
            "Card #$testCardNumber test JAR is missing required entries: ${missing.joinToString()}"
        }

        fun matchesClass(entry: String, allowed: String) =
            entry == allowed || entry.startsWith(allowed.removeSuffix(".class") + "\$")
        val foreignListeners = entries.filter { entry ->
            val weeklyListener =
                entry.contains("HardBingoListener") ||
                    entry.contains("BingoCardTwoListener") ||
                    entry.contains("/BingoCard")
            weeklyListener && listenerClassEntries.none { matchesClass(entry, it) }
        }
        check(foreignListeners.isEmpty()) {
            "Card #$testCardNumber test JAR contains listeners from another card: ${foreignListeners.joinToString()}"
        }
        val forbidden = entries.filter { entry ->
            entry.startsWith("crabcraft/net/crabUtilities/") &&
                allowedProductionClasses.none { matchesClass(entry, it) }
        }
        check(forbidden.isEmpty()) {
            "Card #$testCardNumber test JAR contains unrelated production classes: ${forbidden.joinToString()}"
        }

        val pluginMetadata = zipTree(archive).matching { include("plugin.yml") }.singleFile.readText()
        val requiredMetadata =
            listOf(
                "name: $testPluginName",
                "main: crabcraft.net.bingotest.CrabBingoTestPlugin",
                "api-version: '26.2'",
                "description: Bingo #$testCardNumber task detector test harness.",
                "bingotest:",
                "permission: crabbingotest.use",
            )
        val missingMetadata = requiredMetadata.filterNot { it in pluginMetadata }
        check(missingMetadata.isEmpty()) {
            "Card #$testCardNumber test plugin.yml is missing metadata: ${missingMetadata.joinToString()}"
        }
    }
}

tasks.test {
    failOnNoDiscoveredTests = false
    dependsOn("playerProgressRegressionTest")
}

tasks.check { dependsOn("testJarContentsRegressionTest") }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
