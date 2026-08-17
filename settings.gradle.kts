pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "koinference"
include(":core")
include(":backends:llamacpp")
include(":backends:litertlm")
include(":benchmark:core")

// A separate build, not a module. Firebase Test Lab requires an app APK next to the test APK,
// and `com.android.application` cannot be applied anywhere in this build: AGP 9 sees the Kotlin
// plugin on the build classpath and tries to create a KotlinAndroidTarget, which reaches for the
// variant API that AGP 9 removed. An included build has its own plugin classpath and no Kotlin
// on it, so the stub compiles.
includeBuild("benchmark/stub-app")
