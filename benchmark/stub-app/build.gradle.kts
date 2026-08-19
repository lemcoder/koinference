plugins {
    // Version named here rather than through the root catalog: an included build does not share
    // the parent's version catalog, and keeping it independent is the point — this build must
    // stay free of the Kotlin plugin.
    id("com.android.application") version "9.2.1"
}

// An empty APK, and it stays empty.
//
// The benchmark itself is :benchmark:core's device test, which AGP builds as a self-instrumenting
// test APK: it targets its own package, so nothing in this APK is ever executed. It exists only
// because `gcloud firebase test android run --type instrumentation` requires an app under test.
android {
    namespace = "io.github.lemcoder.koinference.benchmark.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lemcoder.koinference.benchmark.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Signed with the debug key: FTL needs an installable APK and there is nothing here to
        // protect. Not debuggable, so no debugger overhead exists on the device during a run.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}
