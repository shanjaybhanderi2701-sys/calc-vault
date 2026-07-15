pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "CalculatorVault"

include(":app")
// Shared Media3 video-player kit, vendored via `git subtree` from the canonical
// `shanjaybhanderi2701-sys/player-kit` repo (APP-409 home decision; APP-413 CalcVault consumer).
include(":playerkit")
