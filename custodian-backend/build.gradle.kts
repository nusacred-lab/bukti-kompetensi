plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("id.nusacred.custodian.MainKt") }
dependencies {
    implementation(libs.nimbus.jose)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.test)
    testImplementation(kotlin("test-junit"))
}
