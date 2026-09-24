plugins {
    kotlin("jvm")
    kotlin("kapt")
    id("com.gradleup.shadow")
}

base { archivesName.set("CrabUtilities-Velocity") }

kotlin.sourceSets.named("main") { kotlin.srcDir("${rootDir}/shared/src/main/kotlin") }

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://repo.lucko.me/") { name = "luckperms" }
    maven("https://jitpack.io") { name = "jitpack" }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    testCompileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("org.spongepowered:configurate-yaml:4.1.2")
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("com.gitlab.ruany:LiteBansAPI:0.6.1")
    implementation("redis.clients:jedis:5.1.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
}

tasks.shadowJar {
    configurations = listOf(project.configurations.runtimeClasspath.get())
    archiveClassifier.set("")
    archiveVersion.set("")
    addMultiReleaseAttribute.set(false)
    relocate("redis.clients.jedis", "crabcraft.net.libs.jedis")
    relocate("org.apache.commons.pool2", "crabcraft.net.libs.pool2")
    relocate("org.json", "crabcraft.net.libs.json")
    relocate("com.zaxxer.hikari", "crabcraft.net.libs.hikari")
    // Keep PostgreSQL's package intact so its JDBC ServiceLoader entry remains valid.
    // Velocity gives each plugin its own classloader.
    mergeServiceFiles()
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
}

tasks.jar { archiveClassifier.set("slim") }

tasks.build { dependsOn(tasks.shadowJar) }

val regressionTests =
    mapOf(
        "awardEvaluatorRegressionTest" to "crabcraft.net.crabUtilities.velocity.awards.AwardEvaluatorRegressionTest",
        "nicknameRegressionTest" to "crabcraft.net.crabUtilities.velocity.NicknameRegressionTest",
        "vanishBridgeProtocolRegressionTest" to
            "crabcraft.net.crabUtilities.velocity.VanishBridgeProtocolRegressionTest",
        "jadeClientProtocolPayloadRegressionTest" to
            "crabcraft.net.crabUtilities.velocity.JadeClientProtocolPayloadRegressionTest",
        "playerLookupRegressionTest" to "crabcraft.net.crabUtilities.velocity.messaging.PlayerLookupRegressionTest",
        "msgCommandTreeRegressionTest" to "crabcraft.net.crabUtilities.velocity.messaging.MsgCommandTreeRegressionTest",
        "playerLocationTrackerRegressionTest" to
            "crabcraft.net.crabUtilities.velocity.voicechat.PlayerLocationTrackerRegressionTest",
        "callManagerRegressionTest" to "crabcraft.net.crabUtilities.velocity.voicechat.CallManagerRegressionTest",
        "webServerRequestBodyRegressionTest" to
            "crabcraft.net.crabUtilities.velocity.api.WebServerRequestBodyRegressionTest",
        "publicChatBrokerRegressionTest" to "crabcraft.net.crabUtilities.velocity.api.PublicChatBrokerRegressionTest",
        "chatConnectionLimiterRegressionTest" to
            "crabcraft.net.crabUtilities.velocity.api.ChatConnectionLimiterRegressionTest",
        "webServerExecutorRegressionTest" to "crabcraft.net.crabUtilities.velocity.api.WebServerExecutorRegressionTest",
        "awardAltExclusionRegressionTest" to
            "crabcraft.net.crabUtilities.velocity.awards.AwardAltExclusionRegressionTest",
        "awardEatingRegressionTest" to "crabcraft.net.crabUtilities.velocity.awards.AwardEatingRegressionTest",
        "advancementLeaderboardRegressionTest" to
            "crabcraft.net.crabUtilities.velocity.advancements.AdvancementLeaderboardRegressionTest",
        "loginStreakLeaderboardEligibilityRegressionTest" to
            "crabcraft.net.crabUtilities.velocity.db.LoginStreakLeaderboardEligibilityRegressionTest",
    )

regressionTests.forEach { (taskName, testClass) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        mainClass.set(testClass)
    }
}

tasks.test {
    failOnNoDiscoveredTests = false
    dependsOn(regressionTests.keys)
}

// Bundle the shared award seed so an empty database can be populated by the plugin.
tasks.processResources {
    from(rootProject.file("../../packages/db/seeds/awards.json")) { into("crabcraft") }
}

val generatedSrcDir = layout.buildDirectory.dir("generated/sources/buildinfo/kotlin")
val pluginVersion = project.version.toString()
val generateBuildInfo =
    tasks.register("generateBuildInfo") {
        outputs.dir(generatedSrcDir)
        inputs.property("version", pluginVersion)
        doLast {
            val output = generatedSrcDir.get().file("crabcraft/net/crabUtilities/velocity/BuildInfo.kt").asFile
            output.parentFile.mkdirs()
            output.writeText(
                """
            package crabcraft.net.crabUtilities.velocity

            object BuildInfo {
                const val VERSION = "$pluginVersion"
            }
        """
                    .trimIndent() + "\n"
            )
        }
    }

kotlin.sourceSets.named("main") { kotlin.srcDir(generateBuildInfo) }
