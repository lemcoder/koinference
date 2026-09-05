plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
}

// ONNX Runtime publishes both legs: an Android AAR and a JVM jar with desktop natives. So this
// backend has a host leg the other published-artifact backends lack, which is where its tokenizer
// and pooling get tested without a device.
kotlin {
    jvm()

    android {
        namespace = "io.github.lemcoder.koinference.onnx"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()

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

        jvmMain.dependencies {
            implementation(libs.onnxruntime.jvm)
        }

        androidMain.dependencies {
            implementation(libs.onnxruntime.android)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}
