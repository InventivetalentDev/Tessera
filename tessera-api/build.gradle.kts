// Stable, public extension API for Tessera. Interface + value types only;
// no plugin internals, no MineSkin/shaded deps. Third-party plugins compile
// against this artifact (compileOnly) and reach the live implementation at
// runtime via Bukkit's ServicesManager — so they ship zero block data and
// pull the pre-baked heads from whatever the host server has configured.
plugins {
    java
    `maven-publish`
}

group = "org.inventivetalent.tessera"
version = rootProject.version

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

// Records here carry self-evident component params; don't fail the published
// javadoc jar on doclint's missing-@param/@return noise.
tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Provides org.bukkit.* (BlockData, events, ServicesManager), JOML
    // (Vector3f/Quaternionf) and org.jetbrains.annotations — all supplied by
    // Paper at runtime, so compileOnly here and on the consumer side too.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "tessera-api"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "inventive-repo"
            val releases = "https://repo.inventivetalent.org/repository/maven-releases/"
            val snapshots = "https://repo.inventivetalent.org/repository/maven-snapshots/"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshots else releases)
            credentials {
                username = (project.findProperty("inventiveRepoUser") as String?)
                    ?: System.getenv("INVENTIVE_REPO_USER")
                password = (project.findProperty("inventiveRepoPassword") as String?)
                    ?: System.getenv("INVENTIVE_REPO_PASSWORD")
            }
        }
    }
}
