pluginManagement {
  repositories {
  maven {
    setUrl("https://jitpack.io")
  }
  maven {
    setUrl("https://maven.aliyun.com/nexus/content/groups/public/")
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