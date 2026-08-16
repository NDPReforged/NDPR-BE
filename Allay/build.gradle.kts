plugins {
    id("java-library")
    // Gradle 8/9 通用；旧版 johnrengelman-shadow 不兼容 Gradle 9
    id("com.gradleup.shadow") version "9.6.1"
}

group = "ndpr"
description = "NDPReforged 封禁系统客户端（跨服联防 / HWID 验证），NDPReforged 封禁系统基岩版客户端"
version = "2.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Allay API（Maven Central：https://central.sonatype.com/artifact/org.allaymc.allay/api）
    compileOnly("org.allaymc.allay:api:0.29.0")
    // SQLite 驱动（打包进插件 jar）
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
}

// 共享 Java 核心（gomint / Allay / Nukkit 复用同一份源码）
sourceSets {
    main {
        java {
            srcDirs("src/main/java", "../java-core/src/main/java")
        }
        resources {
            srcDirs("src/main/resources", "../java-core/src/main/resources")
        }
    }
}

tasks.withType(JavaCompile::class.java).configureEach {
    options.encoding = "UTF-8"
}

// 插件描述文件由 src/main/resources/plugin.json 提供（无需 AllayGradle）
tasks.shadowJar {
    archiveFileName.set("ndpr-allay-2.1.0.jar")
    relocate("org.sqlite", "ndpr.shadow.sqlite")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
