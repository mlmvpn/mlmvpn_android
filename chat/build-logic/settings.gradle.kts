// MLMCHAT: Telegram's buildSrc, brought into MLMVPN as an included build (settings.gradle,
// pluginManagement) so its plugins and dependencies stay off the rest of the app's build classpath.
// Repositories live here rather than in build.gradle.kts, where a build environment's init script
// can see them (Google Maven is not reachable from Iran).
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "telegram-build-logic"
