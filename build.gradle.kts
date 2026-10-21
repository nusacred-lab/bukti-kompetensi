plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
allprojects {
    group = "id.nusacred"
    version = "0.2.0-p2"
    dependencyLocking { lockAllConfigurations() }
}
