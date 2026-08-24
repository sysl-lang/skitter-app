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

// **Not your application's name** — this is what Gradle calls the build, and it appears in nothing a
// user of the app ever sees. Your two names are in `gradle.properties`.
rootProject.name = "skitter-app"

include(":app")
