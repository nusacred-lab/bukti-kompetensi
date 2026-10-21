plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "id.nusacred.wallet"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "id.nusacred.wallet"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.2.0-p2"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
}
