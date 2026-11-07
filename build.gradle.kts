plugins {
    java
}

group = "com.extantape"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.onarandombox.com/multiverse-releases")
    maven("https://repo.bluecolored.de/releases")
    maven("https://jitpack.io") {
        content { includeGroup("com.github.MilkBowl") }
    }
}

val paperApi = "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT"

// The test-only plugin the bot suites install: vetoes a gate and stands in for an economy.
val hook = sourceSets.create("hook") {
    java.setSrcDirs(listOf("test/hook/src"))
    resources.setSrcDirs(emptyList<String>())
}

dependencies {
    compileOnly(paperApi)
    compileOnly("org.mvplugins.multiverse.core:multiverse-core:5.6.2")
    compileOnly("de.bluecolored:bluemap-api:2.7.7")

    "hookCompileOnly"(paperApi)
    "hookCompileOnly"(sourceSets.main.get().output)
    "hookCompileOnly"("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveBaseName.set("Waystation")
    from("LICENSE")
}

tasks.register<Jar>("hookJar") {
    description = "Builds the test-only plugin the bot suites install."
    archiveFileName.set("DenyHook.jar")
    destinationDirectory.set(layout.buildDirectory.dir("test-hook"))
    from(hook.output)
    from("test/hook/plugin.yml")
}
