plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.shadow)
}

base {
    archivesName.set("CrabUtilities-Velocity")
}

kotlin.sourceSets.main {
    kotlin.srcDir("${rootDir}/shared/src/main/kotlin")
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "luckperms"
        url = uri("https://repo.lucko.me/")
    }
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    compileOnly(libs.velocity.api)
    kapt(libs.velocity.api)
    testCompileOnly(libs.velocity.api)
    compileOnly(libs.configurate.yaml)
    compileOnly(libs.gson)
    compileOnly(libs.luckperms)
    compileOnly(libs.litebans)

    implementation(libs.jedis)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
}

tasks.shadowJar {
    configurations.set(listOf(project.configurations.runtimeClasspath.get()))
    archiveClassifier.set("")
    archiveVersion.set("")
    addMultiReleaseAttribute.set(false)
    relocate("redis.clients.jedis", "crabcraft.net.libs.jedis")
    relocate("org.apache.commons.pool2", "crabcraft.net.libs.pool2")
    relocate("org.json", "crabcraft.net.libs.json")
    relocate("com.zaxxer.hikari", "crabcraft.net.libs.hikari")
    // NB: org.postgresql is intentionally NOT relocated. Shadow can't
    // reliably rewrite the JDBC driver's ServiceLoader entry when the
    // package is moved, which makes HikariCP fail with "No suitable
    // driver for jdbc:postgresql:..." at runtime. Velocity gives each
    // plugin its own classloader so there's no conflict to shade around.

    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

tasks.jar {
    archiveClassifier.set("slim")
}

val regressionTests = mapOf(
    "loginStreakCacheSeedRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.LoginStreakCacheSeedRegressionTest",
    "nicknameRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.NicknameRegressionTest",
    "jadeClientProtocolPayloadRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.JadeClientProtocolPayloadRegressionTest",
    "playerLookupRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.messaging.PlayerLookupRegressionTest",
    "msgCommandTreeRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.messaging.MsgCommandTreeRegressionTest",
    "playerLocationTrackerRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.voicechat.PlayerLocationTrackerRegressionTest",
    "callManagerRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.voicechat.CallManagerRegressionTest",
    "webServerRequestBodyRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.api.WebServerRequestBodyRegressionTest",
    "publicChatBrokerRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.api.PublicChatBrokerRegressionTest",
    "chatConnectionLimiterRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.api.ChatConnectionLimiterRegressionTest",
    "webServerExecutorRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.api.WebServerExecutorRegressionTest",
    "awardAltExclusionRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.awards.AwardAltExclusionRegressionTest",
    "awardBiomesRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.awards.AwardBiomesRegressionTest",
    "awardDyeCraftingRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.awards.AwardDyeCraftingRegressionTest",
    "awardSuspiciousBrushRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.awards.AwardSuspiciousBrushRegressionTest",
    "awardSweetBerriesRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.awards.AwardSweetBerriesRegressionTest",
    "awardEatingRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.awards.AwardEatingRegressionTest",
    "awardNewDefinitionsRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.awards.AwardNewDefinitionsRegressionTest",
    "advancementLeaderboardRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.advancements.AdvancementLeaderboardRegressionTest",
    "loginStreakLeaderboardEligibilityRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.db.LoginStreakLeaderboardEligibilityRegressionTest",
    "updateDownloaderRegressionTest" to
        "crabcraft.net.crabUtilities.velocity.update.UpdateDownloaderRegressionTest",
)

regressionTests.forEach { (taskName, testClass) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
        mainClass.set(testClass)
    }
}

tasks.test {
    failOnNoDiscoveredTests.set(false)
}

tasks.check {
    dependsOn(regressionTests.keys)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Bundle the single source-of-truth award seed file into the plugin JAR
// so the plugin can populate an empty awards table without requiring
// 'bun run seed:awards' to have been run first.
tasks.processResources {
    from(file("${rootDir}/../../packages/db/seeds/awards.json")) {
        into("crabcraft")
    }
}

val generatedSrcDir = layout.buildDirectory.dir("generated/sources/buildinfo/kotlin")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    outputs.dir(generatedSrcDir)
    inputs.property("version", project.version)
    doLast {
        val file = generatedSrcDir.get()
            .file("crabcraft/net/crabUtilities/velocity/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("""package crabcraft.net.crabUtilities.velocity

object BuildInfo {
    const val VERSION = "${project.version}"
}
""")
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateBuildInfo)
}
