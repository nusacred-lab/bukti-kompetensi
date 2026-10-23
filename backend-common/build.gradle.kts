plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared-contracts"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.zxing.core)
    implementation(libs.nimbus.jose)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.test)
    testImplementation(kotlin("test-junit"))
}

// Explicit private acceptance runner; excluded from ordinary unit tests and distributions.
tasks.register<JavaExec>("realPolicyProof") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("id.nusacred.common.RealPolicyProof")
}
