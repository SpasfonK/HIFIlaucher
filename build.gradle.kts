// Versions that are known to work together:
//   Android Gradle Plugin 8.7.x needs Gradle 8.9+ and JDK 17.
//   Kotlin 2.0.x ships the Compose compiler as a Gradle plugin (no composeOptions needed).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
