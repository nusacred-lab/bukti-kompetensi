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
        testInstrumentationRunner = "id.nusacred.wallet.DeviceSecurityProof"
        versionCode = 2
        versionName = "0.5.0-p5"
        manifestPlaceholders["appAuthRedirectScheme"] = "id.nusacred.wallet"
        buildConfigField("String", "WALLET_ORIGIN", "\"https://wallet-p2.demo.inoerawan.id\"")
        buildConfigField("String", "OIDC_ISSUER", "\"https://auth-p2.demo.inoerawan.id/realms/competency-proof-p2\"")
        buildConfigField("String", "OIDC_REDIRECT_URI", "\"https://wallet-p2.demo.inoerawan.id/auth/android/callback\"")
    }
    val signingDirectory = providers.environmentVariable("P2_SIGNING_DIRECTORY").orNull?.let(::file)
    if (signingDirectory != null) signingConfigs.create("p2Candidate") {
        storeFile = signingDirectory.resolve("wallet-candidate.p12")
        storePassword = signingDirectory.resolve("store-password").readText().trim()
        keyPassword = storePassword
        keyAlias = "wallet-candidate"
    }
    buildTypes {
        release { if (signingDirectory != null) signingConfig = signingConfigs.getByName("p2Candidate") }
        debug {
            applicationIdSuffix = if(providers.gradleProperty("deviceSecurityProof").orNull == "true") ".securityproof" else ".debug"
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
    implementation(project(":shared-contracts"))
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.appauth)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
}

android.sourceSets.getByName("main").kotlin.srcDir("../ui-kit/src")
