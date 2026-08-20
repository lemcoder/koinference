plugins {
    // No Kotlin plugin: AGP 9 compiles Kotlin itself, and applying org.jetbrains.kotlin.android
    // on top is rejected outright ("no longer required for Kotlin support since AGP 9.0").
    id("com.android.application") version "9.2.1"
    // A compiler plugin, not the kotlin-android plugin AGP rejects: without it @Serializable
    // classes compile but generate no serializer, and every `.serializer()` is unresolved.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
}

// A real app, in its own build.
//
// It cannot live in the repository's main build: AGP 9 sees the Kotlin Multiplatform plugin on
// that build's classpath and fails while creating a KotlinAndroidTarget, which reaches for the
// variant API AGP 9 removed. Here the classpath is this build's own, so the application plugin
// applies cleanly, and the library modules arrive through the composite in settings.gradle.kts.
//
// Two jobs: host the inference service, and be the APK Firebase Test Lab installs alongside the
// instrumentation test APK.
android {
    namespace = "io.github.lemcoder.koinference.benchmark.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lemcoder.koinference.benchmark.app"
        // 31 because :backends:llamacpp declares it: its ggml build requires ARM dotprod and has
        // no run-time fallback.
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Signed with the debug key: FTL needs an installable APK and there is nothing here to
        // protect. Not debuggable, so no debugger overhead exists while anything is measured.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation("io.github.lemcoder:koinference-benchmark-core")

    // CIO rather than Netty or OkHttp: no servlet stack, no reflection, and it is the engine
    // Ktor supports on Android without dragging in a JVM-only server.
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
