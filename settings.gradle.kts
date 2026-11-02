pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云 Google 镜像：本机 dl.google.com 常 TLS 断连（拉 Compose 1.8 / media3 1.9 时踩过）
        maven("https://maven.aliyun.com/repository/google") { content { includeGroupByRegex("androidx\\..*") } }
        google()
        mavenCentral()
    }
}

rootProject.name = "Arris"
include(":app")
include(":benchmark")

// Kanesumi-sec-a 通过组合构建接入。这样 Kanesumi 侧源码改动不需要 publish 就能被
// Arris 增量构建看到,迁移期间来回改两边 API 最省事。
// 显式 dependencySubstitution 让 Kanesumi 无需配 maven-publish 或设 group/version。
// 未来 Kanesumi 上 Maven Central 时,只需删掉整个 includeBuild 块,
// app/build.gradle.kts 里的坐标一字不改就能切。
includeBuild("../Kanesumi-sec-a") {
    dependencySubstitution {
        substitute(module("io.github.takahashirinta:kanesumi-core"))
            .using(project(":kanesumi-core"))
        substitute(module("io.github.takahashirinta:kanesumi-anim"))
            .using(project(":kanesumi-anim"))
        substitute(module("io.github.takahashirinta:kanesumi-controls"))
            .using(project(":kanesumi-controls"))
        substitute(module("io.github.takahashirinta:kanesumi-structure"))
            .using(project(":kanesumi-structure"))
    }
}
