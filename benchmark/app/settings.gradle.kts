pluginManagement {
    repositories {
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

// The library modules come from the repository's main build. Included the other way round —
// this build includes that one — because the reverse would be a cycle, and because an
// application module cannot live in the main build: AGP 9 sees the Kotlin Multiplatform plugin
// on its classpath and fails creating a KotlinAndroidTarget.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("io.github.lemcoder:koinference-benchmark-core"))
            .using(project(":benchmark:core"))
    }
}

rootProject.name = "koinference-benchmark-app"
