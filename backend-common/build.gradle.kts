plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared-contracts"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.nimbus.jose)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.test)
    testImplementation(kotlin("test-junit"))
}
