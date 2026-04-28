plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper")        version "2.3.1"
    id("com.gradleup.shadow")           version "8.3.10"
}

group = "io.tessera"
version = "0.1.0-SNAPSHOT"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.inventivetalent.org/repository/public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")

    implementation("org.mineskin:java-client:3.2.5")
    implementation("org.mineskin:java-client-jsoup:3.2.5")
    implementation("com.google.code.gson:gson:2.11.0")
    // JOML is bundled by Paper at runtime, but the bake task runs without Paper
    // and needs it on the classpath. Use implementation; the shadow jar will
    // include it but the runtime plugin just sees Paper's copy first.
    implementation("org.joml:joml:1.10.5")

    // MockBukkit boots a fake Bukkit runtime in-JVM so we can integration-test
    // listeners/commands without spinning up a real Paper server. We use the
    // v1.21 artifact (transitive paper-api 1.21.1-R0.1-SNAPSHOT) to avoid a
    // classpath clash with the project's paper-api 1.21.4 — the older v1.20
    // artifact pulls 1.20.6 and breaks Bukkit's service-loader init.
    testImplementation("com.github.seeseemelk:MockBukkit-v1.21:3.133.2")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
        showStandardStreams = false
    }
}

tasks.runServer {
    minecraftVersion("1.21.4")
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.mineskin",    "io.tessera.shaded.mineskin")
    relocate("com.google.gson", "io.tessera.shaded.gson")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

val tesseraBake by tasks.registering(JavaExec::class) {
    group = "tessera"
    description = "Pre-bake MineSkin player-head textures for blocks listed in bake-blocks.txt."
    mainClass.set("io.tessera.skin.bake.BakeMain")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(
        "--input", "bake-blocks.txt",
        "--out",   "src/main/resources/heads.json",
        "--cache", "build/tessera-cache"
    )
    environment("MINESKIN_API_KEY", System.getenv("MINESKIN_API_KEY") ?: "")
}
