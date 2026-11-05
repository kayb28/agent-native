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
        // Espejo con CDN público de Maven Central (cache-redirector de JetBrains):
        // resuelve antes que mavenCentral y evita los 429 que algunas IPs reciben de Central.
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
        mavenCentral()
    }
}

rootProject.name = "AgentNative"
include(":app")
