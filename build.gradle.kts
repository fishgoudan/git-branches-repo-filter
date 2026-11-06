plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "com.github.repofilter"
version = "0.1.4"

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
    intellijPlatform {
        // 本地 IDE 依赖通过本地 Ivy 仓库解析，必须注册（其中包含 localPlatformArtifacts）。
        defaultRepositories()
    }
}

val localIdePath = providers.gradleProperty("localIdePath").getOrElse("D:/IntelliJ IDEA 2026.2.3")

dependencies {
    intellijPlatform {
        local(localIdePath)
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}