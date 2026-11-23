// A build of its own, not part of the app: it compiles the audio engine's sources for the
// desktop JVM so they can run without a phone. See run.sh.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "audio-test"
