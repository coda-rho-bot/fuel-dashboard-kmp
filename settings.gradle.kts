rootProject.name = "fuel-dashboard-kmp"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

fun MavenArtifactRepository.androidxAndGoogleOnly() {
    mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
    }
}

pluginManagement {
    repositories {
        google()

        val forgejoToken: String? = providers.gradleProperty("forgejo.token").orNull ?: System.getenv("FORGEJO_TOKEN")

        // Resolve Angus Gradle Tools plugin markers from Forgejo Maven Registry
        maven {
            url = uri("https://git.angussoftware.dev/api/packages/rhomancer/maven")
            credentials {
                username = "rhomancer"
                password = forgejoToken
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val forgejoToken: String? = providers.gradleProperty("forgejo.token").orNull ?: System.getenv("FORGEJO_TOKEN")

dependencyResolutionManagement {
    repositories {
        google {
            androidxAndGoogleOnly()
        }
        mavenCentral()

        // Forgejo Maven Registry: serves both angus-gradle-tools and Angus-Software-Theming
        maven {
            url = uri("https://git.angussoftware.dev/api/packages/rhomancer/maven")
            credentials {
                username = "rhomancer"
                password = forgejoToken
            }
        }
    }
}

include(":composeApp")
