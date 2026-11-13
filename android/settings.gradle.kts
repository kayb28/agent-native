pluginManagement {
    repositories {
        google()
        // Espejos CDN de JetBrains (mismos que en dependencyResolutionManagement):
        // evitan los 429 de Maven Central / Gradle Plugin Portal en builds locales
        // desde IPs compartidas.
        maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
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
