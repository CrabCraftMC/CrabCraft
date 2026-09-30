plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

base {
    archivesName.set("CrabUtilities-Shared")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.87-stable")
    implementation("redis.clients:jedis:5.1.0")
}