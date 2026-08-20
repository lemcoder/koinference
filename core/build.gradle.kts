plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.github.lemcoder.koinference"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    macosArm64()
    macosX64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: StreamingTextRuntime and RuntimeGuard have Flow in their
            // signatures, so a consumer of :core alone cannot call them without coroutines.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // Konsist is JVM-only and scans the whole repository from here. It is a test dependency, so
        // it does not reach the published artifact; :core is simply the module with a jvm target and
        // no native prerequisites, which makes `:core:jvmTest` the cheapest place to run it.
        jvmTest.dependencies {
            implementation(libs.konsist)
        }
    }
}
