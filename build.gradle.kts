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
    // Patch the Android lint/emulator tooling graph as well as shipped runtimes.
    // Preserve Netty's minor family; never downgrade an already newer patch.
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val requestedVersion = requested.version.orEmpty()
            if (requested.group == "io.netty" && requestedVersion.matches(Regex("4\\.1\\.\\d+\\.Final")) &&
                requestedVersion.split('.')[2].toInt() < 138) {
                useVersion("4.1.138.Final")
                because("Reviewed security patch for Android emulator tooling")
            }
            if (requested.group == "org.bouncycastle" && requestedVersion.matches(Regex("1\\.\\d+")) &&
                requestedVersion.substringAfter('.').toInt() < 86) {
                useVersion("1.86")
                because("Reviewed security patch for Android lint tooling")
            }
        }
    }
}
