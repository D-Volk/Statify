plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "ru.dvolk"
version = "1.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Velocity API 3.x требует Java 17+.
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")

    // Shaded runtime deps
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.3.3")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("org.yaml:snakeyaml:2.2")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.shadowJar {
    archiveClassifier.set("")
    val shadeBase = "ru.dvolk.statify.velocity.libs"
    relocate("com.zaxxer.hikari", "$shadeBase.hikari")
    relocate("org.mariadb.jdbc", "$shadeBase.mariadb")
    relocate("com.mysql", "$shadeBase.mysql")
    relocate("com.google.protobuf", "$shadeBase.protobuf")
    relocate("org.yaml.snakeyaml", "$shadeBase.snakeyaml")
    minimize {
        exclude(dependency("org.mariadb.jdbc:.*"))
        exclude(dependency("com.mysql:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
