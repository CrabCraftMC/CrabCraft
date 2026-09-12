plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.paperweight)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.shadow)
}

base {
    archivesName.set("CrabUtilities")
}

kotlin.sourceSets.main {
    kotlin.srcDir("${rootDir}/shared/src/main/kotlin")
}

repositories {
    maven {
        name = "spigotmc-repo"
        url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }
    maven {
        name = "essentialsx"
        url = uri("https://repo.essentialsx.net/releases/")
    }
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "henkelmax.public"
        url = uri("https://maven.maxhenkel.de/repository/public")
    }
    maven {
        name = "placeholderapi"
        url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
    maven {
        name = "bluecolored"
        url = uri("https://repo.bluecolored.de/releases")
    }
    maven {
        name = "codemc"
        url = uri("https://repo.codemc.io/repository/maven-releases/")
    }
    maven {
        name = "nexo"
        url = uri("https://repo.nexomc.com/releases")
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    paperweight.devBundle(libs.paper.dev.bundle)
    compileOnly(libs.essentialsx) {
        // EssentialsX 2.20.1 transitively pulls spigot-api:1.20.1 which conflicts
        // with paper-api:26.2 on the org.spigotmc:spigot-api capability.
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
    compileOnly(libs.adventure.legacy)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.voicechat)
    testImplementation(libs.voicechat)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.bluemap)
    compileOnly(libs.packetevents)
    testCompileOnly(libs.packetevents)
    compileOnly(libs.nexo)
    implementation(libs.jedis)
}

tasks.shadowJar {
    configurations.set(listOf(project.configurations.runtimeClasspath.get()))
    archiveClassifier.set("")
    archiveVersion.set("")
    addMultiReleaseAttribute.set(false)
    exclude("META-INF/versions/9/org/yaml/snakeyaml/internal/Logger*.class")
    relocate("redis.clients.jedis", "crabcraft.net.libs.jedis")
    relocate("org.apache.commons.pool2", "crabcraft.net.libs.pool2")
    relocate("org.json", "crabcraft.net.libs.json")
}

tasks.jar {
    archiveClassifier.set("slim")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion(libs.versions.minecraft.get())
}

val regressionTests = mapOf(
    "voiceRelayRegressionTest" to "crabcraft.net.crabUtilities.voicechat.VoiceRelayRegressionTest",
    "rosterLifecycleRegressionTest" to "crabcraft.net.crabUtilities.voicechat.RosterLifecycleRegressionTest",
    "loginStreakCacheRegressionTest" to "crabcraft.net.crabUtilities.LoginStreakCacheRegressionTest",
    "statsPushPathRegressionTest" to "crabcraft.net.crabUtilities.StatsPushPathRegressionTest",
    "statsPushXpLevelRegressionTest" to "crabcraft.net.crabUtilities.StatsPushXpLevelRegressionTest",
    "xpLevelReaderRegressionTest" to "crabcraft.net.crabUtilities.awards.XpLevelReaderRegressionTest",
    "eatingAwardTrackerRegressionTest" to "crabcraft.net.crabUtilities.awards.EatingAwardTrackerRegressionTest",
    "eatingHistoryImportRegressionTest" to "crabcraft.net.crabUtilities.awards.EatingHistoryImportRegressionTest",
    "globalChatLinkRegressionTest" to "crabcraft.net.crabUtilities.chat.GlobalChatLinkRegressionTest",
    "chatMiniMessageRegressionTest" to "crabcraft.net.crabUtilities.chat.ChatMiniMessageRegressionTest",
    "publicChatPublisherRegressionTest" to "crabcraft.net.crabUtilities.chat.PublicChatPublisherRegressionTest",
    "chatBridgeRegressionTest" to "crabcraft.net.crabUtilities.chat.bridge.ChatBridgeRegressionTest",
    "reloadCommandRegressionTest" to "crabcraft.net.crabUtilities.ReloadCommandRegressionTest",
    "crabMessagesRegressionTest" to "crabcraft.net.crabUtilities.CrabMessagesRegressionTest",
    "xaeroLifecycleRegressionTest" to "crabcraft.net.crabUtilities.xaero.XaeroLifecycleRegressionTest",
    "nicknameMessagesRegressionTest" to "crabcraft.net.crabUtilities.sleep.NicknameMessagesRegressionTest",
    "nicknameRegressionTest" to "crabcraft.net.crabUtilities.NicknameRegressionTest",
    "nicknameSoftDependencyRegressionTest" to "crabcraft.net.crabUtilities.NicknameSoftDependencyRegressionTest",
    "playerVisibilityRegressionTest" to "crabcraft.net.crabUtilities.PlayerVisibilityRegressionTest",
    "lofiVoicechatRegressionTest" to "crabcraft.net.crabUtilities.voicechat.LofiVoicechatRegressionTest",
    "simpleVoiceAnimationsProtocolRegressionTest" to "crabcraft.net.crabUtilities.voicechat.SimpleVoiceAnimationsProtocolRegressionTest",
    "suspiciousBrushTrackerRegressionTest" to "crabcraft.net.crabUtilities.awards.SuspiciousBrushTrackerRegressionTest",
    "experienceClumpsRegressionTest" to "crabcraft.net.crabUtilities.xpclumps.ExperienceClumpListenerRegressionTest",
    "slimeMapRegressionTest" to "crabcraft.net.crabUtilities.slime.SlimeMapRegressionTest",
    "viewDistanceManagerRegressionTest" to "crabcraft.net.crabUtilities.viewdistance.ViewDistanceManagerRegressionTest",
    "sharedVillagerDiscountsRegressionTest" to "crabcraft.net.crabUtilities.villagers.SharedVillagerDiscountListenerRegressionTest",
    "jadeProtocolRegressionTest" to "crabcraft.net.crabUtilities.jade.protocol.payload.ReceiveDataPayloadRegressionTest",
    "jadeResponseIsolationRegressionTest" to "crabcraft.net.crabUtilities.jade.protocol.JadeResponseIsolationRegressionTest",
    "jadeItemStorageEncodingRegressionTest" to "crabcraft.net.crabUtilities.jade.protocol.provider.JadeItemStorageEncodingRegressionTest",
    "jadeClientProtocolPayloadRegressionTest" to "crabcraft.net.crabUtilities.jade.JadeClientProtocolPayloadRegressionTest",
    "jadeLifecycleRegressionTest" to "crabcraft.net.crabUtilities.jade.JadeLifecycleRegressionTest",
    "jadeInventoryPolicyRegressionTest" to "crabcraft.net.crabUtilities.jade.protocol.JadeInventoryPolicyRegressionTest",
    "jadeMessengerWarningLimiterRegressionTest" to "crabcraft.net.crabUtilities.jade.protocol.JadeMessengerWarningLimiterRegressionTest",
    "jadeLootTableCompatibilityRegressionTest" to "crabcraft.net.crabUtilities.jade.protocol.util.LootTableMineableCollectorRegressionTest",
    "customPortalDestinationRegressionTest" to "crabcraft.net.crabUtilities.netherportals.CustomPortalDestinationRegressionTest",
    "mediaFeatureSecurityRegressionTest" to "crabcraft.net.crabUtilities.media.util.MediaFeatureSecurityRegressionTest",
    "mediaCompatibilityRegressionTest" to "crabcraft.net.crabUtilities.media.MediaCompatibilityRegressionTest",
    "mediaClearRegressionTest" to "crabcraft.net.crabUtilities.media.item.MediaClearRegressionTest",
    "mediaSecurityRegressionTest" to "crabcraft.net.crabUtilities.media.audio.MediaSecurityRegressionTest",
    "literalTitleRegressionTest" to "crabcraft.net.crabUtilities.media.language.LiteralTitleRegressionTest",
    "hopperSecurityRegressionTest" to "crabcraft.net.crabUtilities.media.event.HopperSecurityRegressionTest",
    "binaryProvisionerRegressionTest" to "crabcraft.net.crabUtilities.media.audio.BinaryProvisionerRegressionTest",
    "updateDownloaderRegressionTest" to "crabcraft.net.crabUtilities.update.UpdateDownloaderRegressionTest",
    "moduleConfigManagerRegressionTest" to "crabcraft.net.crabUtilities.config.ModuleConfigManagerRegressionTest",
    "accurateBlockPlacementRegressionTest" to "crabcraft.net.crabUtilities.accurateplacement.AccurateBlockPlacementRegressionTest",
    "appleSkinLifecycleRegressionTest" to "crabcraft.net.crabUtilities.appleskin.AppleSkinLifecycleRegressionTest",
    "bingoTaskRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoTaskRegressionTest",
    "hardBingoListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.HardBingoListenerRegressionTest",
    "bingoCardTwoListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardTwoListenerRegressionTest",
    "bingoCardThreeCoreListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardThreeCoreListenerRegressionTest",
    "bingoCardThreeChallengeListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardThreeChallengeListenerRegressionTest",
    "bingoCardFourCombatListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardFourCombatListenerRegressionTest",
    "bingoCardFourMechanicsListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardFourMechanicsListenerRegressionTest",
    "bingoCardFourMobListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardFourMobListenerRegressionTest",
    "bingoCardFourWorldListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardFourWorldListenerRegressionTest",
    "bingoCardFiveChallengeListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardFiveChallengeListenerRegressionTest",
    "bingoCardFiveMechanicsListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardFiveMechanicsListenerRegressionTest",
    "bingoCardFiveMobListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardFiveMobListenerRegressionTest",
    "bingoCardFiveWorldListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardFiveWorldListenerRegressionTest",
    "bingoCardSixMechanicsListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardSixMechanicsListenerRegressionTest",
    "bingoCardSixMobListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardSixMobListenerRegressionTest",
    "bingoCardSixWorldListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardSixWorldListenerRegressionTest",
    "spectatorBackRegressionTest" to "crabcraft.net.crabUtilities.spectator.SpectatorBackRegressionTest",
    "endPortalBlockingRegressionTest" to "crabcraft.net.crabUtilities.endportals.EndPortalBlockerListenerRegressionTest",
    "restrictedAreaRegressionTest" to "crabcraft.net.crabUtilities.restrictedarea.RestrictedAreaRegressionTest",
    "verificationReminderRegressionTest" to "crabcraft.net.crabUtilities.restrictedarea.VerificationReminderRegressionTest",
)
val runtimeOnlyRegressionTests = setOf(
    "voiceRelayRegressionTest",
    "nicknameSoftDependencyRegressionTest",
)

regressionTests.forEach { (taskName, testClass) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        if (taskName !in runtimeOnlyRegressionTests) {
            classpath += sourceSets.main.get().compileClasspath
        }
        mainClass.set(testClass)
    }
}

tasks.register("pluginJarContentsRegressionTest") {
    group = "verification"
    dependsOn(tasks.shadowJar)

    doLast {
        val expected = sortedSetOf<String>()
        expected.add("kotlin/jvm/internal/Intrinsics.class")
        sourceSets.main.get().output.classesDirs.files.forEach { root ->
            if (!root.isDirectory) return@forEach
            fileTree(root).matching { include("**/*.class") }.visit {
                if (!isDirectory) expected.add(relativePath.pathString)
            }
        }

        val packaged = hashSetOf<String>()
        val archive = tasks.shadowJar.get().archiveFile.get().asFile
        zipTree(archive).visit {
            if (!isDirectory && relativePath.pathString.endsWith(".class")) {
                packaged.add(relativePath.pathString)
            }
        }

        val missing = expected.filterNot { it in packaged }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Production plugin JAR is missing compiled classes: " + missing.joinToString(", "))
        }
    }
}

tasks.test {
    failOnNoDiscoveredTests.set(false)
}

tasks.check {
    dependsOn(regressionTests.keys)
    dependsOn(tasks.named("pluginJarContentsRegressionTest"))
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    from(rootProject.file("../../LICENSE")) {
        into("META-INF")
    }
    from(rootProject.file("../../THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
    }
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// One-off verified-history import. Preview by default; run against a stopped server.
tasks.register<JavaExec>("eatingHistory") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass.set("crabcraft.net.crabUtilities.awards.EatingHistoryImport")
    doFirst {
        args(providers.gradleProperty("world").get(), providers.gradleProperty("history").get())
        if (project.hasProperty("applyHistory")) args("--apply")
        else if (project.hasProperty("exportHistory")) args("--export")
    }
}
