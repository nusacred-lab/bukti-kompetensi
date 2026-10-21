@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
plugins { alias(libs.plugins.kotlin.multiplatform) }
kotlin {
    jvm()
    wasmJs { browser() }
    sourceSets { jvmTest.dependencies { implementation(kotlin("test-junit")) } }
}
