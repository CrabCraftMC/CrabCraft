plugins {
    kotlin("jvm")
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
    id("com.gradleup.shadow")
}

base { archivesName.set("CrabUtilities") }

kotlin.sourceSets.named("main") { kotlin.srcDir("${rootDir}/shared/src/main/kotlin") }

repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { name = "spigotmc-repo" }
    maven("https://repo.essentialsx.net/releases/") { name = "essentialsx" }
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://maven.maxhenkel.de/repository/public") { name = "henkelmax.public" }
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") { name = "placeholderapi" }
    maven("https://repo.bluecolored.de/releases") { name = "bluecolored" }
    maven("https://repo.codemc.io/repository/maven-releases/") { name = "codemc" }
    maven("https://repo.nexomc.com/releases") { name = "nexo" }
}

dependencies {
    paperweight.paperDevBundle("26.2.build.87-stable")
    compileOnly("net.essentialsx:EssentialsX:2.20.1") {
        // Its older Spigot API conflicts with the Paper development bundle.
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.17.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.17.0")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.13")
    testImplementation("de.maxhenkel.voicechat:voicechat-api:2.6.13")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("de.bluecolored:bluemap-api:2.7.7")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    testCompileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    compileOnly("com.nexomc:nexo:1.27.0")
    implementation("redis.clients:jedis:5.1.0")
}

tasks.shadowJar {
    configurations = listOf(project.configurations.runtimeClasspath.get())
    archiveClassifier.set("")
    archiveVersion.set("")
    addMultiReleaseAttribute.set(false)
    exclude("META-INF/versions/9/org/yaml/snakeyaml/internal/Logger*.class")
    relocate("redis.clients.jedis", "crabcraft.net.libs.jedis")
    relocate("org.apache.commons.pool2", "crabcraft.net.libs.pool2")
    relocate("org.json", "crabcraft.net.libs.json")
}

tasks.jar { archiveClassifier.set("slim") }

tasks.build { dependsOn(tasks.shadowJar) }

tasks.runServer { minecraftVersion("26.2") }

val regressionTests =
    mapOf(
        "verificationReminderRegressionTest" to
            "crabcraft.net.crabUtilities.restrictedarea.VerificationReminderRegressionTest",
        "abstractBingoDetectorRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.AbstractBingoDetectorRegressionTest",
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
        "xaeroLifecycleRegressionTest" to "crabcraft.net.crabUtilities.xaero.XaeroLifecycleRegressionTest",
        "nicknameMessagesRegressionTest" to "crabcraft.net.crabUtilities.sleep.NicknameMessagesRegressionTest",
        "nicknameRegressionTest" to "crabcraft.net.crabUtilities.NicknameRegressionTest",
        "nicknameSoftDependencyRegressionTest" to "crabcraft.net.crabUtilities.NicknameSoftDependencyRegressionTest",
        "playerVisibilityRegressionTest" to "crabcraft.net.crabUtilities.PlayerVisibilityRegressionTest",
        "lofiVoicechatRegressionTest" to "crabcraft.net.crabUtilities.voicechat.LofiVoicechatRegressionTest",
        "simpleVoiceAnimationsProtocolRegressionTest" to
            "crabcraft.net.crabUtilities.voicechat.SimpleVoiceAnimationsProtocolRegressionTest",
        "suspiciousBrushTrackerRegressionTest" to
            "crabcraft.net.crabUtilities.awards.SuspiciousBrushTrackerRegressionTest",
        "experienceClumpsRegressionTest" to
            "crabcraft.net.crabUtilities.xpclumps.ExperienceClumpListenerRegressionTest",
        "slimeMapRegressionTest" to "crabcraft.net.crabUtilities.slime.SlimeMapRegressionTest",
        "viewDistanceManagerRegressionTest" to
            "crabcraft.net.crabUtilities.viewdistance.ViewDistanceManagerRegressionTest",
        "sharedVillagerDiscountsRegressionTest" to
            "crabcraft.net.crabUtilities.villagers.SharedVillagerDiscountListenerRegressionTest",
        "jadeProtocolRegressionTest" to
            "crabcraft.net.crabUtilities.jade.protocol.payload.ReceiveDataPayloadRegressionTest",
        "jadeResponseIsolationRegressionTest" to
            "crabcraft.net.crabUtilities.jade.protocol.JadeResponseIsolationRegressionTest",
        "jadeItemStorageEncodingRegressionTest" to
            "crabcraft.net.crabUtilities.jade.protocol.provider.JadeItemStorageEncodingRegressionTest",
        "jadeClientProtocolPayloadRegressionTest" to
            "crabcraft.net.crabUtilities.jade.JadeClientProtocolPayloadRegressionTest",
        "jadeInventoryPolicyRegressionTest" to
            "crabcraft.net.crabUtilities.jade.protocol.JadeInventoryPolicyRegressionTest",
        "jadeMessengerWarningLimiterRegressionTest" to
            "crabcraft.net.crabUtilities.jade.protocol.JadeMessengerWarningLimiterRegressionTest",
        "customPortalDestinationRegressionTest" to
            "crabcraft.net.crabUtilities.netherportals.CustomPortalDestinationRegressionTest",
        "mediaFeatureSecurityRegressionTest" to
            "crabcraft.net.crabUtilities.media.util.MediaFeatureSecurityRegressionTest",
        "mediaCompatibilityRegressionTest" to "crabcraft.net.crabUtilities.media.MediaCompatibilityRegressionTest",
        "mediaClearRegressionTest" to "crabcraft.net.crabUtilities.media.item.MediaClearRegressionTest",
        "mediaSecurityRegressionTest" to "crabcraft.net.crabUtilities.media.audio.MediaSecurityRegressionTest",
        "literalTitleRegressionTest" to "crabcraft.net.crabUtilities.media.language.LiteralTitleRegressionTest",
        "hopperSecurityRegressionTest" to "crabcraft.net.crabUtilities.media.event.HopperSecurityRegressionTest",
        "binaryProvisionerRegressionTest" to "crabcraft.net.crabUtilities.media.audio.BinaryProvisionerRegressionTest",
        "updateDownloaderRegressionTest" to "crabcraft.net.crabUtilities.update.UpdateDownloaderRegressionTest",
        "moduleConfigManagerRegressionTest" to "crabcraft.net.crabUtilities.config.ModuleConfigManagerRegressionTest",
        "accurateBlockPlacementRegressionTest" to
            "crabcraft.net.crabUtilities.accurateplacement.AccurateBlockPlacementRegressionTest",
        "appleSkinLifecycleRegressionTest" to "crabcraft.net.crabUtilities.appleskin.AppleSkinLifecycleRegressionTest",
        "bingoTaskRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoTaskRegressionTest",
        "hardBingoListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.HardBingoListenerRegressionTest",
        "bingoCardTwoListenerRegressionTest" to "crabcraft.net.crabUtilities.bingo.BingoCardTwoListenerRegressionTest",
        "bingoCardThreeChallengeListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardThreeChallengeListenerRegressionTest",
        "bingoCardFourCombatListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardFourCombatListenerRegressionTest",
        "bingoCardFourMechanicsListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardFourMechanicsListenerRegressionTest",
        "bingoCardFourMobListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardFourMobListenerRegressionTest",
        "bingoCardFourWorldListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardFourWorldListenerRegressionTest",
        "bingoCardFiveChallengeListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardFiveChallengeListenerRegressionTest",
        "bingoCardFiveMechanicsListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardFiveMechanicsListenerRegressionTest",
        "bingoCardFiveMobListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardFiveMobListenerRegressionTest",
        "bingoCardFiveWorldListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardFiveWorldListenerRegressionTest",
        "bingoCardSixMechanicsListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardSixMechanicsListenerRegressionTest",
        "bingoCardSixMobListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardSixMobListenerRegressionTest",
        "bingoCardSixWorldListenerRegressionTest" to
            "crabcraft.net.crabUtilities.bingo.BingoCardSixWorldListenerRegressionTest",
        "spectatorBackRegressionTest" to "crabcraft.net.crabUtilities.spectator.SpectatorBackRegressionTest",
        "endPortalBlockingRegressionTest" to
            "crabcraft.net.crabUtilities.endportals.EndPortalBlockerListenerRegressionTest",
        "restrictedAreaRegressionTest" to "crabcraft.net.crabUtilities.restrictedarea.RestrictedAreaRegressionTest",
    )

val runtimeOnlyRegressionTests = setOf("voiceRelayRegressionTest", "nicknameSoftDependencyRegressionTest")

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
        val expected =
            sourceSets.main
                .get()
                .output
                .classesDirs
                .files
                .flatMap { root ->
                    if (root.isDirectory)
                        fileTree(root)
                            .matching { include("**/*.class") }
                            .files
                            .map { it.relativeTo(root).invariantSeparatorsPath }
                    else emptyList()
                }
                .toSet()
        val packaged = mutableSetOf<String>()
        zipTree(tasks.shadowJar.get().archiveFile.get().asFile).visit {
            if (!isDirectory && relativePath.pathString.endsWith(".class")) packaged += relativePath.pathString
        }
        val missing = expected - packaged
        check(missing.isEmpty()) {
            "Production plugin JAR is missing compiled classes: ${missing.sorted().joinToString()}"
        }
        check("kotlin/jvm/internal/Intrinsics.class" in packaged) {
            "Production plugin JAR is missing the Kotlin standard library"
        }
    }
}

tasks.test {
    failOnNoDiscoveredTests = false
    dependsOn(regressionTests.keys)
}

tasks.check { dependsOn("pluginJarContentsRegressionTest") }

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    from(rootProject.file("../../LICENSE")) { into("META-INF") }
    from(rootProject.file("../../THIRD_PARTY_NOTICES.md")) { into("META-INF") }
    filesMatching("plugin.yml") { expand(props) }
}

// One-off verified-history import. Preview by default; run against a stopped server.
tasks.register<JavaExec>("eatingHistory") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass.set("crabcraft.net.crabUtilities.awards.EatingHistoryImport")
    doFirst {
        args(project.property("world").toString(), project.property("history").toString())
        if (project.hasProperty("applyHistory")) args("--apply")
        else if (project.hasProperty("exportHistory")) args("--export")
    }
}
