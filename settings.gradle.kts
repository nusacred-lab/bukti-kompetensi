pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    // Kotlin/Wasm adds a restricted Node distribution repository during setup.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories { google(); mavenCentral() }
}
rootProject.name = "competency-proof"
include(":custodian-backend", ":android-wallet", ":web-portals")
