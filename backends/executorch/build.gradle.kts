plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

// Android only, and one target is the honest count: ExecuTorch publishes `executorch-android`, an
// AAR with arm64-v8a and x86_64 JNI libraries and a Kotlin-facing `LlmModule`. There is no JVM or
// Kotlin/Native artifact to consume, so the other targets are not declared rather than declared and
// left throwing.
kotlin {
    android {
        namespace = "io.github.lemcoder.koinference.executorch"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

        // The runtime's own logic needs no engine, so it runs here too — which is what compiles the
        // Android actual against it.
        withHostTest {}

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.executorch.android)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // No generated accessor: withDeviceTest creates this source set while this block configures.
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}
