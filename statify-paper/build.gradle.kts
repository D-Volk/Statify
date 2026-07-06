plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "ru.dvolk"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    // Paper API — 1.20.4 as base; 1.21.x is source-compatible for the APIs we use.
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")

    // Shaded runtime deps
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.3.3")
    implementation("com.mysql:mysql-connector-j:8.4.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    val shadeBase = "ru.dvolk.statify.paper.libs"
    relocate("com.zaxxer.hikari", "$shadeBase.hikari")
    relocate("org.mariadb.jdbc", "$shadeBase.mariadb")
    relocate("com.mysql", "$shadeBase.mysql")
    relocate("com.google.protobuf", "$shadeBase.protobuf")
    minimize {
        exclude(dependency("org.mariadb.jdbc:.*"))
        exclude(dependency("com.mysql:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
