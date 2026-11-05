// Plugins a nivel de proyecto: las versiones se fijan aquí y se aplican en :app.
// Combinación estable elegida (noviembre 2026):
//   AGP 8.7.3 + Gradle 8.10.2 (wrapper) + Kotlin 2.0.21 + Compose BOM 2024.10.01
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
