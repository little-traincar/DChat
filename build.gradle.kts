plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // 第四期红线例外：Chaquopy（唯一获批的第三方 Gradle 插件）
    // 16.1.0：与 AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21 兼容性更好；
    // 17.2.0 在本项目 Kotlin 2.0.21 + Gradle 8.9 下 compileReleaseKotlin 出现 Gradle state-lock 冲突。
    id("com.chaquo.python") version "16.1.0" apply false
}
