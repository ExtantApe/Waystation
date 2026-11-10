plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.extantape"
version = "0.6.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.onarandombox.com/multiverse-releases")
    maven("https://repo.bluecolored.de/releases")
    maven("https://jitpack.io") {
        content { includeGroup("com.github.MilkBowl"); includeGroup("com.github.ExtantApe") }
    }
}

// 26.1 is the oldest Paper this supports. From 26.1 on, Paper versions its API as <mc version>.build.<n>.
val paperApi = "io.papermc.paper:paper-api:26.1.2.build.+"

// The test-only plugin the bot suites install: vetoes a gate and stands in for an economy.
val hook = sourceSets.create("hook") {
    java.setSrcDirs(listOf("test/hook/src"))
    resources.setSrcDirs(emptyList<String>())
}

dependencies {
    compileOnly(paperApi)
    compileOnly("org.mvplugins.multiverse.core:multiverse-core:5.8.2")
    compileOnly("de.bluecolored:bluemap-api:2.7.7")
    // The shared command format (--json, describe), shaded in below.
    implementation("com.github.ExtantApe:Signalbox:v0.3.1")

    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "hookCompileOnly"(paperApi)
    "hookCompileOnly"(sourceSets.main.get().output)
    "hookCompileOnly"("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

// Signalbox goes inside the jar, relocated, so it can never clash with another plugin's copy. The shaded jar is the
// only plugin jar built.
tasks.shadowJar {
    archiveBaseName.set("Waystation")
    archiveClassifier.set("")
    from("LICENSE")
    relocate("com.extantape.signalbox", "com.extantape.waystation.libs.signalbox")
}
tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }

tasks.register<Jar>("hookJar") {
    description = "Builds the test-only plugin the bot suites install."
    archiveFileName.set("DenyHook.jar")
    destinationDirectory.set(layout.buildDirectory.dir("test-hook"))
    from(hook.output)
    from("test/hook/plugin.yml")
}
