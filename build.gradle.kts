// Top-level build file where you can add configuration options common to all sub-projects/modules.
// 根项目 build.gradle.kts
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // 使用最新版本的 Android Gradle 插件
        classpath("com.android.tools.build:gradle:8.8.0") // 截至 2024 年 1 月最新稳定版
        // 确保 Kotlin 插件版本兼容（如果项目使用 Kotlin）
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
    }
}

// 可选：统一管理所有模块的版本
plugins {
    id("com.android.application") version "8.8.0" apply false
    id("com.android.library") version "8.8.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}