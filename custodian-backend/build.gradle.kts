plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("id.nusacred.custodian.MainKt") }
dependencies {
    implementation(project(":backend-common"))
    implementation(project(":shared-contracts"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.nimbus.jose)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.test)
    testImplementation(kotlin("test-junit"))
}

// Extract the reviewed native library into the image, keeping /tmp mounted noexec.
val sqliteNative by tasks.registering(Copy::class) {
    from({ zipTree(configurations.runtimeClasspath.get().single { it.name.startsWith("sqlite-jdbc-") }) }) {
        include("org/sqlite/native/Linux/x86_64/libsqlitejdbc.so")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("sqlite-native"))
}
distributions.main {
    contents { from(sqliteNative) { into("native") } }
}

// Never runs during ordinary tests or CI; requires explicitly provisioned private fork services.
tasks.register<JavaExec>("realBusinessProof") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("id.nusacred.custodian.RealBusinessProof")
}
