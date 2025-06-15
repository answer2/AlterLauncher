pluginManagement {
    repositories {

        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }


        maven {
            setUrl("https://maven.aliyun.com/nexus/content/groups/public/")
        }

        // JitPack 远程仓库：https://jitpack.io
        maven {
            setUrl("https://jitpack.io")
        }
        gradlePluginPortal()
        google()
        mavenCentral()

    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            setUrl("https://jitpack.io")
        }
        maven {
            setUrl("https://maven.aliyun.com/nexus/content/groups/public/")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "AlterLauncher"

include(":app", ":core", ":cpp", ":api", "stub")