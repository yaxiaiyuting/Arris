// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("com.android.test") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 版本发布，必须显式声明这个插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
