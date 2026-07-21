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

rootProject.name = "kemi-bt-board"
include(":app")

val libDir = file("xunfei-auth-lib")
if (libDir.exists() && libDir.isDirectory) {
    include(":xunfei-auth-lib")
}
