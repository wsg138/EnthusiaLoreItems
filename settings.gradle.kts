pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "EnthusiaLoreItems"

include(
    "domain",
    "application",
    "api",
    "adapters-sqlite",
    "adapters-paper",
    "plugin",
    "architecture-tests",
)
