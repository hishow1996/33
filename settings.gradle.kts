pluginManagement {
    repositories {
        google()
        // Maven Central mirror for Kotlin compiler artifacts when Central redirects to
        // an unavailable upstream release asset.
        maven {
            url = uri("https://maven.wso2.org/nexus/content/repositories/central/")
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
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
        // Keep the mirror scoped to Kotlin artifacts to avoid widening dependency sources.
        maven {
            url = uri("https://maven.wso2.org/nexus/content/repositories/central/")
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "TranslateTube"
include(":app")
