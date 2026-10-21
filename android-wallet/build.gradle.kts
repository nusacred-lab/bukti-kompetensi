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
        manifestPlaceholders["appAuthRedirectScheme"] = "id.nusacred.wallet"
        buildConfigField("String", "WALLET_ORIGIN", "\"https://wallet-p2.demo.inoerawan.id\"")
        buildConfigField("String", "OIDC_ISSUER", "\"https://auth-p2.demo.inoerawan.id/realms/competency-proof-p2\"")
        buildConfigField("String", "OIDC_REDIRECT_URI", "\"id.nusacred.wallet:/oauth2redirect\"")
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appAuthRedirectScheme"] = "id.nusacred.wallet.debug"
            buildConfigField("String", "OIDC_REDIRECT_URI", "\"id.nusacred.wallet.debug:/oauth2redirect\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation(libs.appauth)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
}
