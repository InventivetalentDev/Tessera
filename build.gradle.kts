import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.TextReportRenderer

plugins {
    java
    id("io.papermc.paperweight.userdev")            version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper")                    version "2.3.1"
    id("com.gradleup.shadow")                       version "8.3.10"
    id("com.github.jk1.dependency-license-report")  version "2.9"
}

group = "org.inventivetalent.tessera"
version = "26.5.5-SNAPSHOT"

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
    maven("https://repo.inventivetalent.org/repository/snapshots/")
    // PacketEvents — cross-platform packet API. Used at runtime on Spigot
    // (where the Paper BlockBreakProgressUpdateEvent doesn't exist) to
    // observe digging packets, and as the source-of-truth for the packet
    // display transport when running on non-Paper servers.
    maven("https://repo.codemc.io/repository/maven-releases/")
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

    implementation("org.mineskin:java-client:3.2.6-SNAPSHOT")
    implementation("org.mineskin:java-client-jsoup:3.2.6-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.11.0")
    // JOML is bundled by Paper at runtime, but the bake task runs without Paper
    // and needs it on the classpath. Use implementation; the shadow jar will
    // include it but the runtime plugin just sees Paper's copy first.
    implementation("org.joml:joml:1.10.5")
    implementation("org.bstats:bstats-bukkit:3.2.1")
    // Caffeine: in-memory LRU for skin payloads loaded from .tsra files. The
    // registry only holds chunk→hash maps eagerly; the heavy base64
    // value/signature blobs ride this cache and load on demand from disk.
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // PacketEvents — cross-platform packet API. compileOnly because it's a
    // softdepend: server admin installs it as a separate plugin. On Paper
    // the plugin still works without it; on Spigot it's required for the
    // mining-progress source and packet-display transport.
    compileOnly("com.github.retrooper:packetevents-spigot:2.7.0")

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

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
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

// Scan the runtimeClasspath (= what shadow bundles into the plugin jar) for
// per-dependency license metadata and dump a single plain-text report. The
// shadowJar step below copies that report into META-INF/ so the jar carries
// attribution for everything it actually ships. Regenerated on every build,
// so adding/removing a dep keeps the bundled notice in sync.
licenseReport {
    outputDir = layout.buildDirectory.dir("reports/dependency-license").get().asFile.absolutePath
    configurations = arrayOf("runtimeClasspath")
    renderers = arrayOf(TextReportRenderer("THIRD-PARTY-LICENSES.txt"))
    filters = arrayOf(LicenseBundleNormalizer())
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.mineskin",    "org.inventivetalent.tessera.shaded.mineskin")
    relocate("com.google.gson", "org.inventivetalent.tessera.shaded.gson")
    relocate("com.github.benmanes.caffeine", "org.inventivetalent.tessera.shaded.caffeine")

    relocate("org.bstats", "org.inventivetalent.tessera.shaded.bstats")

    dependsOn(tasks.named("generateLicenseReport"))
    from(layout.buildDirectory.file("reports/dependency-license/THIRD-PARTY-LICENSES.txt")) {
        into("META-INF")
    }
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

val tesseraConvertHeads by tasks.registering(JavaExec::class) {
    group = "tessera"
    description = "Convert the legacy heads-<N>.json bundled resource into the new heads-<N>.ztsra format. One-shot helper for the v1 -> v2 migration; the runtime cache file migrates automatically on first boot."
    mainClass.set("org.inventivetalent.tessera.skin.store.HeadsJsonToTsraConverter")
    classpath = sourceSets["main"].runtimeClasspath
    val gridN = (project.findProperty("gridN") as? String) ?: "4"
    val deleteInput = (project.findProperty("deleteInput") as? String) ?: "false"
    args = listOf(
        "--in", "src/main/resources/heads-$gridN.json",
        "--out", "src/main/resources/heads-$gridN.ztsra",
        "--gridN", gridN,
        "--delete", deleteInput
    )
}

val tesseraBake by tasks.registering(JavaExec::class) {
    group = "tessera"
    description = "Pre-bake MineSkin player-head textures for blocks listed in bake-blocks.txt. Output goes to heads-<N>.ztsra (override chunk size with -PgridN=<N>)."
    mainClass.set("org.inventivetalent.tessera.skin.bake.BakeMain")
    classpath = sourceSets["main"].runtimeClasspath
    val gridN = (project.findProperty("gridN") as? String) ?: "4"
    args = listOf(
        "--input", "bake-blocks.txt",
        "--out",   "src/main/resources/heads-$gridN.ztsra",
        "--cache", "tessera-cache",
        "--gridN", gridN
    )
    environment("MINESKIN_API_KEY", System.getenv("MINESKIN_API_KEY") ?: "")
}
