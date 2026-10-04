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
        // gomobile 产出的 .aar 通过本地 libs 目录引入，无需额外仓库
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "KugoMobile"
include(":app")
