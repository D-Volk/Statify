plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
    java
}

group = "ru.dvolk"
version = "1.0.1"
val archivesBaseName = "statify-fabric"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val minecraftVersion = "1.21.8"
val yarnMappings = "1.21.8+build.1"
val loaderVersion = "0.17.2"
val fabricApiVersion = "0.136.1+1.21.8"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" } // Placeholder API by patbox
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Placeholder API (optional at runtime — mod stays useful without it)
    modImplementation("eu.pb4:placeholder-api:2.7.2+1.21.8")

    // Fabric permissions API — совместим с LuckPerms и т.п. Опционален в runtime.
    modImplementation("me.lucko:fabric-permissions-api:0.4.2-patbox.1")

    // JDBC (bundle via Loom's include so the mod ships as one jar)
    include(implementation("com.zaxxer:HikariCP:5.1.0")!!)
    include(implementation("org.mariadb.jdbc:mariadb-java-client:3.3.3")!!)
    include(implementation("com.mysql:mysql-connector-j:8.4.0")!!)
    include(implementation("org.slf4j:slf4j-api:2.0.13")!!)
    include(implementation("org.yaml:snakeyaml:2.2")!!)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${archivesBaseName}" }
    }
}
