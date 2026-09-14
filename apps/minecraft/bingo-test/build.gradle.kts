plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

val testCardNumber = 6
val testPluginName = "CrabBingoCard${testCardNumber}Test"
val testArchiveName = "${testPluginName}.jar"
val cardListenerClasses = listOf(
    "BingoCardSixWorldListener",
    "BingoCardSixMobListener",
    "BingoCardSixMechanicsListener",
)

base {
    archivesName.set(testPluginName)
}

kotlin.sourceSets.main {
    kotlin {
        srcDir("../spigot/src/main/kotlin")
        include("crabcraft/net/bingotest/**")
        include("crabcraft/net/crabUtilities/CrabMessages.kt")
        include("crabcraft/net/crabUtilities/bingo/BingoTask.kt")
        include("crabcraft/net/crabUtilities/bingo/BingoDetector.kt")
        cardListenerClasses.forEach { listenerClass ->
            include("crabcraft/net/crabUtilities/bingo/${listenerClass}.kt")
        }
    }
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    compileOnly(libs.paper.api)
}

tasks.shadowJar {
    archiveFileName.set(testArchiveName)
    addMultiReleaseAttribute.set(false)
}

tasks.jar {
    archiveClassifier.set("slim")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

val prepareBingoTestServer = tasks.register<Copy>("prepareBingoTestServer") {
    from(layout.projectDirectory.file("server.properties"))
    into(layout.projectDirectory.dir("run"))
}

tasks.runServer {
    dependsOn(prepareBingoTestServer)
    minecraftVersion(libs.versions.minecraft.get())
    build(libs.versions.paper.build.get().toInt())
    jvmArgs(
        "-Xms512M",
        "-Xmx2G",
        "-Dcom.mojang.eula.agree=true"
    )
}

val playerProgressRegressionTest = tasks.register<JavaExec>("playerProgressRegressionTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("crabcraft.net.bingotest.PlayerProgressTest")
}

val testJarContentsRegressionTest = tasks.register("testJarContentsRegressionTest") {
    group = "verification"
    dependsOn(tasks.shadowJar)

    doLast {
        val archive = tasks.shadowJar.get().archiveFile.get().asFile
        if (archive.name != testArchiveName) {
            throw GradleException(
                "Unexpected Card #${testCardNumber} test JAR name: " + archive.name)
        }

        val entries = mutableSetOf<String>()
        zipTree(archive).visit {
            if (!isDirectory) entries.add(relativePath.pathString)
        }

        val listenerClassEntries = cardListenerClasses.map { listenerClass ->
            "crabcraft/net/crabUtilities/bingo/${listenerClass}.class"
        }
        val required = listOf(
            "plugin.yml",
            "kotlin/jvm/internal/Intrinsics.class",
            "crabcraft/net/bingotest/CrabBingoTestPlugin.class",
            "crabcraft/net/bingotest/BingoTestCommand.class",
            "crabcraft/net/bingotest/BingoTestJoinListener.class",
            "crabcraft/net/bingotest/BingoTestManager.class",
            "crabcraft/net/bingotest/PlayerProgress.class",
            "crabcraft/net/crabUtilities/CrabMessages.class",
            "crabcraft/net/crabUtilities/bingo/BingoTask.class",
            "crabcraft/net/crabUtilities/bingo/BingoDetector.class",
        ) + listenerClassEntries
        val compiled = mutableSetOf<String>()
        sourceSets.main.get().output.classesDirs.files.forEach { root ->
            if (!root.isDirectory) return@forEach
            fileTree(root).matching { include("**/*.class") }.visit {
                if (!isDirectory) compiled.add(relativePath.pathString)
            }
        }
        val missing = (required + compiled).filter { it !in entries }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Card #${testCardNumber} test JAR is missing required entries: "
                    + missing.joinToString(", "))
        }

        val foreignListeners = entries.filter { entry ->
            val isWeeklyListener = entry.contains("HardBingoListener")
                || entry.contains("BingoCardTwoListener")
                || entry.contains("/BingoCard")
            val isAllowed = listenerClassEntries.any { allowed ->
                entry == allowed || entry.startsWith(allowed.replace(".class", "$"))
            }
            isWeeklyListener && !isAllowed
        }
        if (foreignListeners.isNotEmpty()) {
            throw GradleException(
                "Card #${testCardNumber} test JAR contains listeners from another card: "
                    + foreignListeners.joinToString(", "))
        }

        val allowedProductionClasses = listOf(
            "crabcraft/net/crabUtilities/CrabMessages.class",
            "crabcraft/net/crabUtilities/bingo/BingoTask.class",
            "crabcraft/net/crabUtilities/bingo/BingoDetector.class",
        ) + listenerClassEntries
        val forbidden = entries.filter { entry ->
            entry.startsWith("crabcraft/net/crabUtilities/")
                && !allowedProductionClasses.any { allowed ->
                    entry == allowed || entry.startsWith(allowed.replace(".class", "$"))
                }
        }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "Card #${testCardNumber} test JAR contains unrelated production classes: "
                    + forbidden.joinToString(", "))
        }

        val pluginMetadataFile = zipTree(archive).matching {
            include("plugin.yml")
        }.singleFile
        val pluginMetadata = pluginMetadataFile.readText(Charsets.UTF_8)
        val requiredMetadata = listOf(
            "name: ${testPluginName}",
            "main: crabcraft.net.bingotest.CrabBingoTestPlugin",
            "api-version: '${libs.versions.minecraft.get()}'",
            "description: Bingo #${testCardNumber} task detector test harness.",
            "bingotest:",
            "permission: crabbingotest.use",
        )
        val missingMetadata = requiredMetadata.filter {
            !pluginMetadata.contains(it)
        }
        if (missingMetadata.isNotEmpty()) {
            throw GradleException(
                "Card #${testCardNumber} test plugin.yml is missing metadata: "
                    + missingMetadata.joinToString(", "))
        }
    }
}

tasks.test {
    failOnNoDiscoveredTests.set(false)
}

tasks.check {
    dependsOn(playerProgressRegressionTest)
    dependsOn(testJarContentsRegressionTest)
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}
