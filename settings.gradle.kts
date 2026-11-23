pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ModulAI"
include(":app", ":core:runtime", ":core:device", ":core:models", ":core:characters", ":core:agent", ":core:code", ":core:skills",
    ":engines:llm-llamacpp", ":engines:image-sdcpp", ":engines:audio")
