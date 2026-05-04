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

// paperweight by default puts the mojang-mapped paper-server jar onto both
// compileOnly and testImplementation. Its META-INF/services entry registers
// io.papermc.paper.ServerBuildInfoImpl, whose constructor calls
// net.minecraft.SharedConstants.getCurrentVersion() — that fails outside a
// real Paper boot and torpedoes MockBukkit.mock() with a chained
// ServiceConfigurationError. Restricting the dependency to compileOnly keeps
// main compilation against the 1.21.4 dev bundle while letting MockBukkit's
// transitive paper-api 1.21.1 provide the Bukkit surface for tests.
paperweight {
    addServerDependencyTo.set(setOf(configurations.compileOnly.get()))
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
    implementation("org.bstats:bstats-bukkit:3.2.1")

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

    relocate("org.bstats", "io.tessera.shaded.bstats")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

val tesseraBake by tasks.registering(JavaExec::class) {
    group = "tessera"
    description = "Pre-bake MineSkin player-head textures for blocks listed in bake-blocks.txt. Output goes to heads-<N>.json (override chunk size with -PgridN=<N>)."
    mainClass.set("io.tessera.skin.bake.BakeMain")
    classpath = sourceSets["main"].runtimeClasspath
    val gridN = (project.findProperty("gridN") as? String) ?: "4"
    args = listOf(
        "--input", "bake-blocks.txt",
        "--out",   "src/main/resources/heads-$gridN.json",
        "--cache", "build/tessera-cache",
        "--gridN", gridN
    )
    environment("MINESKIN_API_KEY", System.getenv("MINESKIN_API_KEY") ?: "")
}
