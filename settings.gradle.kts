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
include(":benchmark:android")
